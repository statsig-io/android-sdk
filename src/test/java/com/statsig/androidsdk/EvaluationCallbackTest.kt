package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EvaluationCallbackTest {

    private lateinit var app: Application
    private var flushedLogs: String = ""
    private var initUser: StatsigUser? = null
    private var client: StatsigClient = StatsigClient()
    private lateinit var network: StatsigNetwork
    private lateinit var testSharedPrefs: TestSharedPreferences
    private var checkedGate = ""
    private var checkedConfig = ""
    private var checkedLayer = ""

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()

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
        val now = System.currentTimeMillis()
        user.customIDs = mapOf("random_id" to "abcde")

        fun evalCallback(config: BaseConfig) {
            if (config is FeatureGate) {
                checkedGate = config.getName()
            } else if (config is DynamicConfig) {
                checkedConfig = config.getName()
            } else if (config is Layer) {
                checkedLayer = config.getName()
            }
        }

        val evalCallback: (BaseConfig) -> Unit = ::evalCallback

        TestUtil.startStatsigAndWait(app, user, StatsigOptions(overrideStableID = "custom_stable_id", evaluationCallback = evalCallback), network = network)
        client = Statsig.client

        assertTrue(client.checkGate("always_on"))
        assertEquals("always_on", checkedGate)
        assertTrue(client.checkGateWithExposureLoggingDisabled("always_on_v2"))
        assertEquals("always_on_v2", checkedGate)
        assertFalse(client.checkGateWithExposureLoggingDisabled("a_different_gate"))
        assertEquals("a_different_gate", checkedGate)
        assertFalse(client.checkGate("always_off"))
        assertEquals("always_off", checkedGate)
        assertFalse(client.checkGate("not_a_valid_gate_name"))
        assertEquals("not_a_valid_gate_name", checkedGate)

        client.getConfig("test_config")
        assertEquals("test_config", checkedConfig)

        client.getConfigWithExposureLoggingDisabled("a_different_config")
        assertEquals("a_different_config", checkedConfig)

        client.getConfig("not_a_valid_config")
        assertEquals("not_a_valid_config", checkedConfig)

        client.getExperiment("exp")
        assertEquals("exp", checkedConfig)

        client.getExperimentWithExposureLoggingDisabled("exp_other")
        assertEquals("exp_other", checkedConfig)

        client.getLayer("layer")
        assertEquals("layer", checkedLayer)

        client.getLayerWithExposureLoggingDisabled("layer_other")
        assertEquals("layer_other", checkedLayer)

        // check a few previously checked gate and config; they should not result in exposure logs due to deduping logic
        client.checkGate("always_on")
        assertEquals("always_on", checkedGate)
        client.getConfig("test_config")
        assertEquals("test_config", checkedConfig)
        client.getExperiment("exp")
        assertEquals("exp", checkedConfig)

        client.shutdown()
    }
}
