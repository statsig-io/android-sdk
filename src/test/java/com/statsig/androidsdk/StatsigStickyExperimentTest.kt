package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigStickyExperimentTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: SharedPreferences

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()
        TestUtil.mockHashing()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)

        // Because we are going through the static Statsig interface,
        // we need to ensure no other test has initialized the client
        Statsig.client = StatsigClient()
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    private fun copyConfig(
        config: APIDynamicConfig,
        value: Map<String, Any> = config.value,
        isUserInExperiment: Boolean = config.isUserInExperiment,
        isExperimentActive: Boolean = config.isExperimentActive,
        allocatedExperimentName: String? = config.allocatedExperimentName,
    ): APIDynamicConfig {
        return APIDynamicConfig(
            config.name,
            value,
            config.ruleID,
            isExperimentActive = isExperimentActive,
            isUserInExperiment = isUserInExperiment,
            allocatedExperimentName = allocatedExperimentName,
        )
    }

    @Test
    fun testStickyBucketing() = runBlocking {
        val expConfig = APIDynamicConfig(
            "exp!",
            mapOf(
                "key" to "exp_v1",
            ),
            "default",
            isUserInExperiment = true,
            isExperimentActive = true,
        )
        val newExpConfig = APIDynamicConfig(
            "new_exp!",
            mapOf(
                "key" to "new_exp_v1",
            ),
            "default",
            isUserInExperiment = true,
            isExperimentActive = true,
        )
        val layerConfig = APIDynamicConfig(
            "layer!",
            mapOf(
                "key" to "layer_v1",
            ),
            "default",
            isUserInExperiment = true,
            isExperimentActive = true,
            allocatedExperimentName = "exp!",
        )

        // 1. Saves sticky value and returns latest

        initialize(
            mapOf(
                "exp!" to expConfig,
            ),
            mapOf(
                "layer!" to layerConfig,
            ),
        )

        var exp = Statsig.getExperiment("exp", keepDeviceValue = true)
        assertEquals("exp_v1", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)

        var layer = Statsig.getLayer("layer", keepDeviceValue = true)
        assertEquals("layer_v1", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Network, layer.getEvaluationDetails().reason)

        Statsig.shutdown()

        // 2. Drops user from experiment, returns the original sticky value

        initialize(
            mapOf(
                "exp!" to copyConfig(expConfig, mapOf("key" to "exp_v2"), false),
            ),
            mapOf(
                "layer!" to copyConfig(layerConfig, mapOf("key" to "layer_v2"), false),
            ),
        )

        exp = Statsig.getExperiment("exp", keepDeviceValue = true)
        assertEquals("exp_v1", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Sticky, exp.getEvaluationDetails().reason)

        layer = Statsig.getLayer("layer", keepDeviceValue = true)
        assertEquals("layer_v1", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Sticky, layer.getEvaluationDetails().reason)

        Statsig.shutdown()

        // 3. Deactivates experiment, returns the latest value

        initialize(
            mapOf(
                "exp!" to copyConfig(
                    expConfig,
                    mapOf("key" to "exp_v3"),
                    isUserInExperiment = false,
                    isExperimentActive = false,
                ),
                "new_exp!" to copyConfig(newExpConfig, mapOf("key" to "exp_v3")),
            ),
            mapOf(
                "layer!" to copyConfig(
                    layerConfig,
                    mapOf("key" to "layer_v3"),
                    isUserInExperiment = true,
                    isExperimentActive = true,
                    allocatedExperimentName = "new_exp!",
                ),
            ),
        )

        exp = Statsig.getExperiment("exp", keepDeviceValue = true)
        assertEquals("exp_v3", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)

        layer = Statsig.getLayer("layer", keepDeviceValue = true)
        assertEquals("layer_v3", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Network, layer.getEvaluationDetails().reason)

        Statsig.shutdown()

        // 4. Drops user from the experiments, returns second sticky value

        initialize(
            mapOf(
                "exp!" to copyConfig(
                    expConfig,
                    mapOf("key" to "exp_v4"),
                    isUserInExperiment = false,
                    isExperimentActive = true,
                ),
                "new_exp!" to copyConfig(
                    newExpConfig,
                    mapOf("key" to "new_exp_v4"),
                    isUserInExperiment = false,
                    isExperimentActive = true,
                ),
            ),
            mapOf(
                "layer!" to copyConfig(
                    layerConfig,
                    mapOf("key" to "layer_v4"),
                    isUserInExperiment = false,
                    isExperimentActive = true,
                    allocatedExperimentName = "new_exp!",
                ),
            ),
        )

        exp = Statsig.getExperiment("exp", keepDeviceValue = true)
        assertEquals("exp_v4", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)

        layer = Statsig.getLayer("layer", keepDeviceValue = true)
        assertEquals("layer_v3", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Sticky, layer.getEvaluationDetails().reason)

        Statsig.shutdown()

        // 5. Drops all stickyness when user doesn't request it

        initialize(
            mapOf(
                "exp!" to copyConfig(expConfig, mapOf("key" to "exp_v5"), isUserInExperiment = true, isExperimentActive = false),
            ),
            mapOf(
                "layer!" to copyConfig(layerConfig, mapOf("key" to "layer_v5"), isUserInExperiment = true, isExperimentActive = false),
            ),
        )

        exp = Statsig.getExperiment("exp", keepDeviceValue = false)
        assertEquals("exp_v5", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)

        layer = Statsig.getLayer("layer", keepDeviceValue = false)
        assertEquals("layer_v5", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Network, layer.getEvaluationDetails().reason)

        // 6. Only sets sticky values when experiment is active

        Statsig.getExperiment("exp", keepDeviceValue = true)
        Statsig.getLayer("layer", keepDeviceValue = true)
        Statsig.shutdown()

        initialize(
            mapOf(
                "exp!" to copyConfig(expConfig, mapOf("key" to "exp_v6"), isUserInExperiment = true, isExperimentActive = true),
            ),
            mapOf(
                "layer!" to copyConfig(layerConfig, mapOf("key" to "layer_v6"), isUserInExperiment = true, isExperimentActive = true),
            ),
        )

        exp = Statsig.getExperiment("exp", keepDeviceValue = true)
        assertEquals("exp_v6", exp.getString("key", "ERR"))
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)

        layer = Statsig.getLayer("layer", keepDeviceValue = true)
        assertEquals("layer_v6", layer.getString("key", "Err"))
        assertEquals(EvaluationReason.Network, layer.getEvaluationDetails().reason)

        Statsig.shutdown()
    }

    @Test
    fun testKeepDeviceValueFalseDoesNotWriteToSharedPrefs() = runBlocking {
        val expConfig = APIDynamicConfig(
            "exp!",
            mapOf("key" to "exp_v_no_write"),
            "default",
            isUserInExperiment = true,
            isExperimentActive = true,
        )

        val layerConfig = APIDynamicConfig(
            "layer!",
            mapOf(
                "key" to "layer_v1",
            ),
            "default",
            isUserInExperiment = true,
            isExperimentActive = true,
            allocatedExperimentName = "exp!",
        )

        initialize(
            configs = mapOf("exp!" to expConfig),
            layers = mapOf(
                "layer!" to copyConfig(
                    layerConfig,
                    mapOf("key" to "layer_v4"),
                    isUserInExperiment = false,
                    isExperimentActive = true,
                    allocatedExperimentName = "new_exp!",
                ),
            ),
        )

        // Empty existing values
        testSharedPrefs.edit().clear().apply()

        Statsig.getExperiment("exp", keepDeviceValue = false)
        Statsig.getLayer("layer", keepDeviceValue = false)

        // Ensure that 'keepDeviceValue = false' prevents a write
        assertThat(testSharedPrefs.all).isEmpty()

        Statsig.shutdown()
    }

    private fun initialize(
        configs: Map<String, APIDynamicConfig>,
        layers: Map<String, APIDynamicConfig>,
    ) = runBlocking {
        TestUtil.startStatsigAndWait(
            app,
            network = TestUtil.mockNetwork(
                featureGates = mapOf(),
                dynamicConfigs = configs,
                layerConfigs = layers,
            ),
        )
    }
}
