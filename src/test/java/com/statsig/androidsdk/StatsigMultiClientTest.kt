package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigMultiClientTest {
    private lateinit var app: Application
    private var flushedLogs1: MutableList<LogEventData> = mutableListOf()
    private var flushedLogs2: MutableList<LogEventData> = mutableListOf()
    private var flushedLogs3: MutableList<LogEventData> = mutableListOf()
    private val sdkKey1 = "client-apikey1"
    private val sdkKey2 = "client-apikey2"
    private val sdkKey3 = "client-apikey3"
    private var client1: StatsigClient = spyk(StatsigClient())
    private var client2: StatsigClient = spyk(StatsigClient())
    private var client3: StatsigClient = spyk(StatsigClient())
    private lateinit var network1: StatsigNetwork
    private lateinit var network2: StatsigNetwork
    private lateinit var network3: StatsigNetwork
    private lateinit var brokenNetwork: StatsigNetwork
    private lateinit var testSharedPrefs: SharedPreferences
    private val user = StatsigUser("testUser")

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)

        TestUtil.mockHashing()

        network1 =
            TestUtil.mockNetwork(
                featureGates = mockFeatureGatesResponse(
                    1
                ),
                dynamicConfigs = mockDynamicConfigs(1),
                onLog = {
                    flushedLogs1.add(it)
                }
            )
        network2 =
            TestUtil.mockNetwork(
                featureGates = mockFeatureGatesResponse(
                    2
                ),
                dynamicConfigs = mockDynamicConfigs(2),
                onLog = {
                    flushedLogs2.add(it)
                }
            )
        network3 =
            TestUtil.mockNetwork(
                featureGates = mockFeatureGatesResponse(
                    3
                ),
                dynamicConfigs = mockDynamicConfigs(3),
                onLog = {
                    flushedLogs3.add(it)
                }
            )
        brokenNetwork = TestUtil.mockBrokenNetwork()
    }

    @Test
    fun testInitializeMultiple() {
        TestUtil.startStatsigClientAndWait(app, client1, sdkKey = sdkKey1, user, network = network1)
        TestUtil.startStatsigClientAndWait(app, client2, sdkKey = sdkKey2, user, network = network2)
        TestUtil.startStatsigClientAndWait(app, client3, sdkKey = sdkKey3, user, network = network3)
        assert(client1.isInitialized())
        assert(client2.isInitialized())
        assert(client3.isInitialized())
        assert(!client1.checkGate("gate_1"))
        assert(client2.checkGate("gate_1"))
        assert(!client3.checkGate("gate_1"))
        assert(client1.checkGate("gate_2"))
        assert(!client2.checkGate("gate_2"))
        assert(client3.checkGate("gate_2"))

        assert(client1.checkGate("always_on_1"))
        assert(client2.checkGate("always_on_2"))
        assert(client3.checkGate("always_on_3"))
//         Check not existing gate
        assert(!client3.checkGate("always_on_2"))
        assert(!client2.checkGate("always_on_1"))

        // Dynamic config
        val config1 = client1.getConfig("test_config")
        assert(config1.getEvaluationDetails().reason == EvaluationReason.Network)
        assert(config1.getString("string", "DEFAULT") == "test_1")
        assert(config1.getInt("number", 0) === 42)
        assert(config1.getInt("otherNumber", 0) === 17)

        val config2 = client2.getConfig("test_config")
        assert(config2.getEvaluationDetails().reason == EvaluationReason.Network)
        assert(config2.getString("string", "DEFAULT") == "test_2")
        assert(config2.getInt("number", 0) === 84)
        assert(config2.getInt("otherNumber", 0) === 34)

        val config3 = client3.getConfig("test_config")
        assert(config3.getEvaluationDetails().reason == EvaluationReason.Network)
        assert(config3.getString("string", "DEFAULT") == "test_3")
        assert(config3.getInt("number", 0) === 126)
        assert(config3.getInt("otherNumber", 0) === 51)

        val notValidConfig = client3.getConfig("experiment_1")
        assert(notValidConfig.getEvaluationDetails().reason == EvaluationReason.Unrecognized)
        val validConfig = client1.getConfig("experiment_1")
        assert(validConfig.getEvaluationDetails().reason == EvaluationReason.Network)
        assert(validConfig.getString("string", "DEFAULT") == "other_test")

        client1.shutdown()
        client2.shutdown()
        client3.shutdown()

        assert(flushedLogs1[0].events.size == 6)
        assert(flushedLogs1[0].events[0].eventName == "statsig::diagnostics")
        assert(flushedLogs1[0].events[1].eventName == "statsig::gate_exposure")
        assert(flushedLogs1[0].events[2].eventName == "statsig::gate_exposure")
        assert(flushedLogs1[0].events[3].eventName == "statsig::gate_exposure")
        assert(flushedLogs1[0].events[4].eventName == "statsig::config_exposure")
        assert(flushedLogs1[0].events[5].eventName == "statsig::config_exposure")

        assert(flushedLogs2[0].events.size == 6)
        assert(flushedLogs2[0].events[0].eventName == "statsig::diagnostics")
        assert(flushedLogs2[0].events[1].eventName == "statsig::gate_exposure")
        assert(flushedLogs2[0].events[2].eventName == "statsig::gate_exposure")
        assert(flushedLogs2[0].events[3].eventName == "statsig::gate_exposure")
        assert(flushedLogs2[0].events[4].eventName == "statsig::gate_exposure")
        assert(flushedLogs2[0].events[5].eventName == "statsig::config_exposure")

        assert(flushedLogs3[0].events.size == 7)
        assert(flushedLogs3[0].events[0].eventName == "statsig::diagnostics")
        assert(flushedLogs3[0].events[1].eventName == "statsig::gate_exposure")
        assert(flushedLogs3[0].events[2].eventName == "statsig::gate_exposure")
        assert(flushedLogs3[0].events[3].eventName == "statsig::gate_exposure")
        assert(flushedLogs3[0].events[4].eventName == "statsig::gate_exposure")
        assert(flushedLogs3[0].events[5].eventName == "statsig::config_exposure")
        assert(flushedLogs3[0].events[6].eventName == "statsig::config_exposure")

        try {
            client1.checkGate("gate_1")
            assert(false)
        } catch (e: Exception) {
            assert(e is IllegalStateException)
            e.message?.let { assert(it.contains("The SDK must be initialized prior to invoking")) }
        }
        try {
            client1.logEvent("log")
            assert(false)
        } catch (e: Exception) {
            assert(e is IllegalStateException)
            e.message?.let { assert(it.contains("The SDK must be initialized prior to invoking")) }
        }
        try {
            client1.getLayer("gate_1")
            assert(false)
        } catch (e: Exception) {
            assert(e is IllegalStateException)
            e.message?.let { assert(it.contains("The SDK must be initialized prior to invoking")) }
        }
    }

    @Test
    fun testCache() = runBlocking {
        TestUtil.startStatsigClientAndWait(app, client1, sdkKey = sdkKey1, user, network = network1)
        TestUtil.startStatsigClientAndWait(app, client2, sdkKey = sdkKey2, user, network = network2)
        TestUtil.startStatsigClientAndWait(app, client3, sdkKey = sdkKey3, user, network = network3)
        client1.shutdown()
        client2.shutdown()
        client3.shutdown()
        TestUtil.startStatsigClientAndWait(
            app,
            client1,
            sdkKey = sdkKey1,
            user,
            network = brokenNetwork
        )
        TestUtil.startStatsigClientAndWait(
            app,
            client2,
            sdkKey = sdkKey2,
            user,
            network = brokenNetwork
        )
        TestUtil.startStatsigClientAndWait(
            app,
            client3,
            sdkKey = sdkKey3,
            user,
            network = brokenNetwork
        )
        val config1 = client1.getConfig("test_config")
        assert(config1.getEvaluationDetails().reason == EvaluationReason.Cache)
        assert(config1.getString("string", "DEFAULT") == "test_1")
        assert(config1.getInt("number", 0) === 42)
        assert(config1.getInt("otherNumber", 0) === 17)

        val config2 = client2.getConfig("test_config")
        assert(config2.getEvaluationDetails().reason == EvaluationReason.Cache)
        assert(config2.getString("string", "DEFAULT") == "test_2")
        assert(config2.getInt("number", 0) === 84)
        assert(config2.getInt("otherNumber", 0) === 34)

        val config3 = client3.getConfig("test_config")
        assert(config3.getEvaluationDetails().reason == EvaluationReason.Cache)
        assert(config3.getString("string", "DEFAULT") == "test_3")
        assert(config3.getInt("number", 0) === 126)
        assert(config3.getInt("otherNumber", 0) === 51)

        // Other project configs
        val invalidConfig1 = client1.getConfig("experiment_2")
        assert(invalidConfig1.getEvaluationDetails().reason == EvaluationReason.Unrecognized)
        val invalidConfig2 = client2.getConfig("experiment_1")
        assert(invalidConfig2.getEvaluationDetails().reason == EvaluationReason.Unrecognized)
        val invalidConfig3 = client3.getConfig("experiment_2")
        assert(invalidConfig3.getEvaluationDetails().reason == EvaluationReason.Unrecognized)
    }

    @Test
    fun testReinitialize() = runBlocking {
        TestUtil.startStatsigClientAndWait(app, client1, sdkKey = sdkKey1, user, network = network1)
        assert(client1.checkGate("always_on_1"))
        var config1 = client1.getConfig("test_config")
        assert(config1.getString("string", "DEFAULT") == "test_1")
        assert(config1.getInt("number", 0) === 42)
        assert(config1.getInt("otherNumber", 0) === 17)
        client1.shutdown()
        assert(!client1.isInitialized())
        // Reinitialize we should say different result
        TestUtil.startStatsigClientAndWait(app, client1, sdkKey = sdkKey1, user, network = network2)
        config1 = client1.getConfig("test_config")
        assert(config1.getString("string", "DEFAULT") == "test_2")
        assert(config1.getInt("number", 0) === 84)
        assert(config1.getInt("otherNumber", 0) === 34)
    }

    @Test
    fun testInitializeMultipleInstancesWithSameKey() = runBlocking {
        TestUtil.startStatsigClientAndWait(app, client1, sdkKey = sdkKey1, user, network = network1)
        val client11 = StatsigClient()
        TestUtil.startStatsigClientAndWait(
            app,
            client11,
            sdkKey = sdkKey1,
            user,
            network = network2
        )
        assert(!client1.checkGate("gate_1"))
        assert(client11.checkGate("gate_1"))
        client1.shutdown()
        TestUtil.startStatsigClientAndWait(
            app,
            client1,
            sdkKey = sdkKey1,
            user,
            network = brokenNetwork
        )
        assert(client1.checkGate("gate_1"))
    }

    private fun mockFeatureGatesResponse(key: Int): Map<String, APIFeatureGate> = mapOf(
        // Common name
        "gate_1!" to
            APIFeatureGate(
                "gate_1!",
                key % 2 === 0,
                "gate_1_rule",
                "gate_1_group",
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    ),
                    mapOf(
                        "gate" to "dependent_gate_2",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_2"
                    )
                )
            ),
        "gate_2!" to
            APIFeatureGate(
                "gate_2!",
                key % 2 === 1,
                "gate_2_rule",
                "gate_2_group",
                arrayOf()
            ),
        "always_on_$key!" to
            APIFeatureGate(
                "always_on_$key!",
                true,
                "always_on_rule_id",
                "always_on_group",
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    ),
                    mapOf(
                        "gate" to "dependent_gate_2",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_2"
                    )
                )
            )
    )

    private fun mockDynamicConfigs(key: Int): Map<String, APIDynamicConfig> = mapOf(
        "test_config!" to APIDynamicConfig(
            "test_config!",
            mutableMapOf(
                "string" to "test_$key",
                "number" to 42 * key,
                "otherNumber" to 17 * key
            ),
            "default",
            null,
            arrayOf(
                mapOf(
                    "gate" to "dependent_gate",
                    "gateValue" to "true",
                    "ruleID" to "rule_id_1"
                )
            )
        ),
        "layer_exp!" to APIDynamicConfig(
            "layer_exp!",
            mutableMapOf("string" to "test", "number" to 42 * key),
            "exp_rule",
            "exp_group",
            arrayOf(),
            isExperimentActive = true,
            isUserInExperiment = true
        ),
        "experiment_$key!" to APIDynamicConfig(
            "experiemnt_$key",
            mutableMapOf(
                "string" to "other_test",
                "number" to 7742 / key,
                "otherNumber" to 8817 / key
            ),
            "exp_rule",
            "exp_group",
            arrayOf(),
            isExperimentActive = true,
            isUserInExperiment = true
        ),
        "layer_exp_$key!" to APIDynamicConfig(
            "layer_exp_$key",
            mutableMapOf("string" to "test", "number" to 42 * key),
            "exp_rule",
            "exp_group",
            arrayOf(),
            isExperimentActive = true,
            isUserInExperiment = true
        )
    )
}
