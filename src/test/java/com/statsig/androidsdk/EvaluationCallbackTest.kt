package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EvaluationCallbackTest {

    private lateinit var app: Application
    private var flushedLogs: String = ""
    private var initUser: StatsigUser? = null
    private var client: StatsigClient = StatsigClient()
    private lateinit var network: StatsigNetwork
    private lateinit var testSharedPrefs: SharedPreferences
    private var checkedGate = ""
    private var checkedGateCount = 0
    private var checkedConfig = ""
    private var checkedConfigCount = 0
    private var checkedLayer = ""
    private var checkedLayerCount = 0

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)

        TestUtil.mockHashing()

        network = TestUtil.mockNetwork(captureUser = { user ->
            initUser = user
        })

        coEvery {
            network.apiPostLogs(any(), any(), any())
        } answers {
            flushedLogs = secondArg()
        }
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testInitialize() {
        val user = StatsigUser("123")
        user.customIDs = mapOf("random_id" to "abcde")

        fun evalCallback(config: BaseConfig) {
            if (config is FeatureGate) {
                checkedGate = config.getName()
                checkedGateCount++
            } else if (config is DynamicConfig) {
                checkedConfig = config.getName()
                checkedConfigCount++
            } else if (config is Layer) {
                checkedLayer = config.getName()
                checkedLayerCount++
            }
        }

        val evalCallback: (BaseConfig) -> Unit = ::evalCallback

        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(
                overrideStableID = "custom_stable_id",
                evaluationCallback = evalCallback
            ),
            network = network
        )
        client = Statsig.client

        assertTrue(client.checkGate("always_on"))
        assertEquals("always_on", checkedGate)
        assertEquals(1, checkedGateCount)
        assertTrue(client.checkGateWithExposureLoggingDisabled("always_on_v2"))
        assertEquals("always_on_v2", checkedGate)
        assertEquals(2, checkedGateCount)
        assertFalse(client.checkGateWithExposureLoggingDisabled("a_different_gate"))
        assertEquals("a_different_gate", checkedGate)
        assertEquals(3, checkedGateCount)
        assertFalse(client.checkGate("always_off"))
        assertEquals("always_off", checkedGate)
        assertEquals(4, checkedGateCount)
        assertFalse(client.checkGate("not_a_valid_gate_name"))
        assertEquals("not_a_valid_gate_name", checkedGate)
        assertEquals(5, checkedGateCount)

        client.getConfig("test_config")
        assertEquals("test_config", checkedConfig)
        assertEquals(1, checkedConfigCount)

        client.getConfigWithExposureLoggingDisabled("a_different_config")
        assertEquals("a_different_config", checkedConfig)
        assertEquals(2, checkedConfigCount)

        client.getConfig("not_a_valid_config")
        assertEquals("not_a_valid_config", checkedConfig)
        assertEquals(3, checkedConfigCount)

        client.getExperiment("exp")
        assertEquals("exp", checkedConfig)
        assertEquals(4, checkedConfigCount)

        client.getExperimentWithExposureLoggingDisabled("exp_other")
        assertEquals("exp_other", checkedConfig)
        assertEquals(5, checkedConfigCount)

        client.getLayer("layer")
        assertEquals("layer", checkedLayer)
        assertEquals(1, checkedLayerCount)

        client.getLayerWithExposureLoggingDisabled("layer_other")
        assertEquals("layer_other", checkedLayer)
        assertEquals(2, checkedLayerCount)

        // check a few previously checked gate and config; they should not result in exposure logs due to deduping logic
        client.checkGate("always_on")
        assertEquals("always_on", checkedGate)
        assertEquals(6, checkedGateCount)
        client.getConfig("test_config")
        assertEquals("test_config", checkedConfig)
        assertEquals(6, checkedConfigCount)
        client.getExperiment("exp")
        assertEquals("exp", checkedConfig)
        assertEquals(7, checkedConfigCount)

        client.shutdown()
    }
}
