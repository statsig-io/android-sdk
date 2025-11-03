package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.time.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigTest {

    private lateinit var app: Application
    private var flushedLogs: String = ""
    private var initUser: StatsigUser? = null
    private var client: StatsigClient = StatsigClient()
    private lateinit var network: StatsigNetwork

    private lateinit var dispatcher: TestDispatcher

    @Before
    internal fun setup() {
        dispatcher = TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()

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
        val now = System.currentTimeMillis()
        user.customIDs = mapOf("random_id" to "abcde")

        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(overrideStableID = "custom_stable_id"),
            network = network
        )
        client = Statsig.client

        assertEquals(
            Gson().toJson(initUser?.customIDs),
            Gson().toJson(mapOf("random_id" to "abcde"))
        )
        assertTrue(client.checkGate("always_on"))
        assertTrue(client.checkGateWithExposureLoggingDisabled("always_on_v2"))
        assertFalse(client.checkGateWithExposureLoggingDisabled("a_different_gate"))
        assertFalse(client.checkGate("always_off"))
        assertFalse(client.checkGate("not_a_valid_gate_name"))

        val config = client.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals("test_config", config.getName())
        assertEquals(42, config.getInt("number", 0))
        assertEquals(
            "default string instead",
            config.getString("otherNumber", "default string instead")
        )
        assertEquals("default", config.getRuleID())
        assertNull(config.getGroupName())

        val configNoExposure = client.getConfigWithExposureLoggingDisabled("a_different_config")
        assertEquals("fallback", configNoExposure.getString("string", "fallback"))

        val invalidConfig = client.getConfig("not_a_valid_config")
        assertEquals("", invalidConfig.getRuleID())
        assertNull(config.getGroupName())
        assertEquals("not_a_valid_config", invalidConfig.getName())

        val exp = client.getExperiment("exp")
        assertEquals("exp", exp.getName())
        assertEquals(42, exp.getInt("number", 0))
        assertEquals("exp_rule", exp.getRuleID())
        assertEquals("exp_group", exp.getGroupName())
        val expNoExposure = client.getExperimentWithExposureLoggingDisabled("exp_other")
        assertEquals("exp_other", expNoExposure.getName())
        assertEquals(0, expNoExposure.getInt("number", 0))

        client.logEvent("test_event1", 1.toDouble(), mapOf("key" to "value"))
        client.logEvent("test_event2", mapOf("key" to "value2"))
        client.logEvent("test_event3", "1")

        // check a few previously checked gate and config; they should not result in exposure logs due to deduping logic
        client.checkGate("always_on")
        client.checkGate("always_on")
        client.checkGate("always_on")
        client.getConfig("test_config")
        client.getConfig("test_config")
        client.getConfig("test_config")
        client.getExperiment("exp")
        client.getExperiment("exp")
        client.getExperiment("exp")

        client.manuallyLogGateExposure("nonexistent_gate")
        client.manuallyLogExperimentExposure("nonexistent_exp", false)
        client.manuallyLogConfigExposure("nonexistent_config")
        client.manuallyLogLayerParameterExposure("nonexistent_layer", "param", false)

        client.shutdown()

        var parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)
        assertEquals(15, parsedLogs.events.count())
        // first 2 are exposures pre initialize() completion
        assertEquals("custom_stable_id", parsedLogs.statsigMetadata.stableID)
        assertEquals("Android", (parsedLogs.statsigMetadata as StatsigMetadata).systemName)
        assertEquals("Android", (parsedLogs.statsigMetadata as StatsigMetadata).deviceOS)
        assert((parsedLogs.statsigMetadata as StatsigMetadata).locale.toString().startsWith("en"))
        assert((parsedLogs.statsigMetadata as StatsigMetadata).language.toString().startsWith("en"))

        // validate diagnostics
        assertEquals(parsedLogs.events[0].eventName, "statsig::diagnostics")
        parsedLogs =
            LogEventData(
                parsedLogs.events.filter { it ->
                    it.eventName != "statsig::diagnostics"
                } as ArrayList<LogEvent>,
                parsedLogs.statsigMetadata
            )

        // validate gate exposure
        assertEquals(parsedLogs.events[0].eventName, "statsig::gate_exposure")
        assertEquals(parsedLogs.events[0].user!!.userID, "123")
        assertEquals(parsedLogs.events[0].metadata!!["gate"], "always_on")
        assertEquals(parsedLogs.events[0].metadata!!["gateValue"], "true")
        assertEquals(parsedLogs.events[0].metadata!!["ruleID"], "always_on_rule_id")
        assertEquals(parsedLogs.events[0].metadata!!["reason"], "Network")
        assertEquals(parsedLogs.events[0].metadata!!["isManualExposure"], null)

        var evalTime = parsedLogs.events[1].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(
            Gson().toJson(parsedLogs.events[0].secondaryExposures),
            Gson().toJson(
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

        // validate non-existent gate's evaluation reason
        assertEquals(parsedLogs.events[2].metadata!!["reason"], "Unrecognized")

        // validate config exposure
        assertEquals(parsedLogs.events[3].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[3].user!!.userID, "123")
        assertEquals(parsedLogs.events[3].metadata!!["config"], "test_config")
        assertEquals(parsedLogs.events[3].metadata!!["ruleID"], "default")
        assertEquals(parsedLogs.events[3].metadata!!["reason"], "Network")
        assertEquals(parsedLogs.events[3].metadata!!["isManualExposure"], null)
        evalTime = parsedLogs.events[3].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(
            Gson().toJson(parsedLogs.events[3].secondaryExposures),
            Gson().toJson(
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    )
                )
            )
        )

        // validate exp exposure
        assertEquals(parsedLogs.events[5].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[5].user!!.userID, "123")
        assertEquals(parsedLogs.events[5].metadata!!["config"], "exp")
        assertEquals(parsedLogs.events[5].metadata!!["ruleID"], "exp_rule")
        assertEquals(parsedLogs.events[5].metadata!!["reason"], "Network")
        assertEquals(parsedLogs.events[5].metadata!!["isManualExposure"], null)
        evalTime = parsedLogs.events[5].metadata!!["time"]!!.toLong()
        assertTrue(evalTime >= now && evalTime < now + 2000)
        assertEquals(parsedLogs.events[5].secondaryExposures?.count() ?: 1, 0)

        // Validate custom logs
        assertEquals(parsedLogs.events[6].eventName, "test_event1")
        assertEquals(parsedLogs.events[6].user!!.userID, "123")
        assertEquals(parsedLogs.events[6].value, 1.0)
        assertEquals(
            Gson().toJson(parsedLogs.events[6].metadata),
            Gson().toJson(mapOf("key" to "value"))
        )
        assertNull(parsedLogs.events[6].secondaryExposures)

        assertEquals(parsedLogs.events[7].eventName, "test_event2")
        assertEquals(parsedLogs.events[7].user!!.userID, "123")
        assertEquals(parsedLogs.events[7].value, null)
        assertEquals(
            Gson().toJson(parsedLogs.events[7].metadata),
            Gson().toJson(mapOf("key" to "value2"))
        )
        assertNull(parsedLogs.events[7].secondaryExposures)

        assertEquals(parsedLogs.events[8].eventName, "test_event3")
        assertEquals(parsedLogs.events[8].user!!.userID, "123")
        assertEquals(parsedLogs.events[8].value, "1")
        assertNull(parsedLogs.events[8].metadata)
        assertNull(parsedLogs.events[8].secondaryExposures)

        assertEquals(parsedLogs.events[9].eventName, "statsig::gate_exposure")
        assertEquals(parsedLogs.events[9].metadata!!["isManualExposure"], "true")
        assertEquals(parsedLogs.events[10].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[10].metadata!!["isManualExposure"], "true")
        assertEquals(parsedLogs.events[11].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[11].metadata!!["isManualExposure"], "true")
        assertEquals(parsedLogs.events[12].eventName, "statsig::layer_exposure")
        assertEquals(parsedLogs.events[12].metadata!!["isManualExposure"], "true")
    }

    @Test
    fun testInitializeWithOptOutNonSdkMetadata() {
        val user = StatsigUser("123")
        user.customIDs = mapOf("random_id" to "abcde")

        // Initialize Statsig with optOutNonSdkMetadata set to true
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(optOutNonSdkMetadata = true, overrideStableID = "custom_stable_id"),
            network = network
        )
        client = Statsig.client
        assertTrue(client.checkGate("always_on"))

        client.shutdown()

        var parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)
        assertNull(parsedLogs.statsigMetadata.appIdentifier)
        assertNull(parsedLogs.statsigMetadata.appVersion)
        assertNull(parsedLogs.statsigMetadata.deviceModel)
        assertNull(parsedLogs.statsigMetadata.locale)
        assertNull(parsedLogs.statsigMetadata.language)
        assertNull(parsedLogs.statsigMetadata.deviceOS)
        assertNull(parsedLogs.statsigMetadata.systemVersion)
        assertNull(parsedLogs.statsigMetadata.systemName)
    }

    @Test
    fun testReinitialize() = runBlocking {
        var countdown = CountDownLatch(1)
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                countdown.countDown()
            }

            override fun onStatsigUpdateUser() {
                fail("Statsig.onStatsigUpdateUser should not have been called")
            }
        }

        var user: StatsigUser? = null
        Statsig.client.statsigNetwork = TestUtil.mockNetwork(captureUser = {
            user = it
        })
        Statsig.initializeAsync(app, "client-sdkkey", StatsigUser("jkw"), callback)

        countdown.await(1L, TimeUnit.SECONDS)
        countdown = CountDownLatch(1)

        assertTrue(Statsig.client.isInitialized())
        assertEquals("jkw", user?.userID)
    }

    @Test
    fun testUserValidator() = runBlocking {
        val options = StatsigOptions()
        var userValidatorCalled = 0
        val updateCountdown = CountDownLatch(1)
        options.userObjectValidator = { user: StatsigUser ->
            user.custom = mapOf("hey" to "hey")
            userValidatorCalled++
        }
        TestUtil.startStatsigAndWait(app, StatsigUser("jkw"), options, network = network)
        assertEquals(initUser?.userID, "jkw")
        assertEquals(initUser?.custom, mapOf("hey" to "hey"))
        Statsig.logEvent("event_1")
        Statsig.updateUser(StatsigUser("test-user-1"))
        assertEquals(initUser?.userID, "test-user-1")
        assertEquals(initUser?.custom, mapOf("hey" to "hey"))
        Statsig.logEvent("event_1")
        Statsig.updateUserAsync(
            StatsigUser("test-user-2"),
            object : IStatsigCallback {
                override fun onStatsigUpdateUser() {
                    updateCountdown.countDown()
                }
            }
        )
        updateCountdown.await(1, TimeUnit.SECONDS)
        assertEquals(initUser?.userID, "test-user-2")
        assertEquals(initUser?.custom, mapOf("hey" to "hey"))
        Statsig.logEvent("event_1")
        Statsig.shutdown()
        assertEquals(userValidatorCalled, 3)
        var parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)
        assertEquals(parsedLogs.events[0].user?.custom, mapOf("hey" to "hey"))
        assertEquals(parsedLogs.events[1].user?.custom, mapOf("hey" to "hey"))
        assertEquals(parsedLogs.events[2].user?.custom, mapOf("hey" to "hey"))
    }

    @Test
    fun testInitializationTimeout() = runBlocking {
        val user = StatsigUser("test-user")
        val timeout = 800L
        val option = StatsigOptions(initTimeoutMs = timeout)
        val setupMethod = client.javaClass.getDeclaredMethod(
            "setup",
            Application::class.java,
            String::class.java,
            StatsigUser::class.java,
            StatsigOptions::class.java
        )
        setupMethod.isAccessible = true
        setupMethod.invoke(
            client,
            app,
            "client-key",
            user,
            option
        )
        // initialize function blocking timeout
        val network = spyk(client.statsigNetwork)
        client.statsigNetwork = network
        coEvery {
            network["initializeImpl"](
                allAny<String>(),
                allAny<StatsigUser>(),
                allAny<Long>(),
                allAny<StatsigMetadata>(),
                allAny<ContextType>(),
                allAny<Diagnostics>(),
                allAny<Int>(),
                allAny<Int>(),
                allAny<HashAlgorithm>(),
                allAny<Map<String, String>>(),
                allAny<String>(),
                allAny<List<String>>()
            )
        } coAnswers {
            Thread.sleep(timeout) // Block the thread
            InitializeResponse.FailedInitializeResponse(
                InitializeFailReason.InternalError,
                Exception("eXAMPLE")
            )
        }
        val scope = client.javaClass.getDeclaredField("statsigScope")
        scope.isAccessible = true
        val statsigScope = scope.get(client)

        try {
            client.statsigNetwork.initialize(
                api = option.api,
                user = user,
                sinceTime = 0,
                metadata = StatsigMetadata(),
                statsigScope as CoroutineScope,
                ContextType.INITIALIZE,
                diagnostics = null,
                hashUsed = HashAlgorithm.DJB2,
                previousDerivedFields = mapOf(),
                fullChecksum = null
            )
        } catch (e: Exception) {
            assert(e is TimeoutCancellationException)
            assert(e.message!!.contains("Timed out waiting for $timeout ms"))
            return@runBlocking
        }
        assert(false)
    }

    @Test
    fun testObjectBasedManualExposureMethods() {
        val user = StatsigUser("test_user")
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(overrideStableID = "test_stable_id"),
            network = network
        )
        client = Statsig.client

        val gate = client.getFeatureGateWithExposureLoggingDisabled("always_on")
        val config = client.getConfigWithExposureLoggingDisabled("test_config")
        val experiment = client.getExperimentWithExposureLoggingDisabled("exp")
        val layer = client.getLayerWithExposureLoggingDisabled("allocated_layer")

        client.manuallyLogGateExposure(gate)
        client.manuallyLogConfigExposure(config)
        client.manuallyLogExperimentExposure(experiment)
        client.manuallyLogLayerParameterExposure(layer, "string")

        client.shutdown()

        val parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)

        val manualExposureLogs = parsedLogs.events.filter {
            it.metadata?.get("isManualExposure") == "true"
        }
        assertEquals("Should have 4 manual exposure logs", 4, manualExposureLogs.size)

        val gateLog = manualExposureLogs.find { it.eventName == "statsig::gate_exposure" }
        assertNotNull("Gate exposure log should exist", gateLog)
        assertEquals("always_on", gateLog!!.metadata!!["gate"])
        assertEquals("true", gateLog.metadata!!["isManualExposure"])

        val configLog = manualExposureLogs.find {
            it.eventName == "statsig::config_exposure" && it.metadata!!["config"] == "test_config"
        }
        assertNotNull("Config exposure log should exist", configLog)
        assertEquals("test_config", configLog!!.metadata!!["config"])
        assertEquals("true", configLog.metadata!!["isManualExposure"])

        val expLog = manualExposureLogs.find {
            it.eventName == "statsig::config_exposure" && it.metadata!!["config"] == "exp"
        }
        assertNotNull("Experiment exposure log should exist", expLog)
        assertEquals("exp", expLog!!.metadata!!["config"])
        assertEquals("true", expLog.metadata!!["isManualExposure"])

        val layerLog = manualExposureLogs.find { it.eventName == "statsig::layer_exposure" }
        assertNotNull("Layer exposure log should exist", layerLog)
        assertEquals("allocated_layer", layerLog!!.metadata!!["config"])
        assertEquals("string", layerLog.metadata!!["parameterName"])
        assertEquals("true", layerLog.metadata!!["isManualExposure"])
    }

    @Test
    fun testObjectBasedManualExposureWithNonExistentObjects() {
        val user = StatsigUser("test_user")
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(overrideStableID = "test_stable_id"),
            network = network
        )
        client = Statsig.client

        val nonExistentGate = client.getFeatureGateWithExposureLoggingDisabled("nonexistent_gate")
        val nonExistentConfig = client.getConfigWithExposureLoggingDisabled("nonexistent_config")
        val nonExistentExperiment = client.getExperimentWithExposureLoggingDisabled(
            "nonexistent_exp"
        )
        val nonExistentLayer = client.getLayerWithExposureLoggingDisabled("nonexistent_layer")

        client.manuallyLogGateExposure(nonExistentGate)
        client.manuallyLogConfigExposure(nonExistentConfig)
        client.manuallyLogExperimentExposure(nonExistentExperiment)
        client.manuallyLogLayerParameterExposure(nonExistentLayer, "param")

        client.shutdown()

        val parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)

        val manualExposureLogs = parsedLogs.events.filter {
            it.metadata?.get("isManualExposure") == "true"
        }
        assertEquals("Should have 4 manual exposure logs", 4, manualExposureLogs.size)

        manualExposureLogs.forEach { log ->
            assertEquals("true", log.metadata!!["isManualExposure"])
        }

        val gateLog = manualExposureLogs.find { it.eventName == "statsig::gate_exposure" }
        assertNotNull("Gate exposure log should exist", gateLog)
        assertEquals("nonexistent_gate", gateLog!!.metadata!!["gate"])

        val configLog = manualExposureLogs.find {
            it.eventName == "statsig::config_exposure" &&
                it.metadata!!["config"] == "nonexistent_config"
        }
        assertNotNull("Config exposure log should exist", configLog)
        assertEquals("nonexistent_config", configLog!!.metadata!!["config"])

        val expLog = manualExposureLogs.find {
            it.eventName == "statsig::config_exposure" &&
                it.metadata!!["config"] == "nonexistent_exp"
        }
        assertNotNull("Experiment exposure log should exist", expLog)
        assertEquals("nonexistent_exp", expLog!!.metadata!!["config"])

        val layerLog = manualExposureLogs.find { it.eventName == "statsig::layer_exposure" }
        assertNotNull("Layer exposure log should exist", layerLog)
        assertEquals("nonexistent_layer", layerLog!!.metadata!!["config"])
        assertEquals("param", layerLog.metadata!!["parameterName"])
    }

    @Suppress("UnusedFlow")
    @Test
    fun testAutoValueUpdateEnabled_callsPoll() {
        val user = StatsigUser("test_user")
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(enableAutoValueUpdate = true),
            network = network
        )
        client = Statsig.client

        val expectedInterval = AUTO_VALUE_UPDATE_INTERVAL_MINIMUM_VALUE.minutes.inWholeMilliseconds
        verify {
            network.pollForChanges(
                api = any(),
                user = user,
                metadata = any(),
                updateIntervalMs = expectedInterval,
                fallbackUrls = any()
            )
        }
        client.shutdown()
    }

    @Suppress("UnusedFlow")
    @Test
    fun testAutoValueUpdateDisabled_doesNotCallPoll() {
        val user = StatsigUser("test_user")
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(enableAutoValueUpdate = false),
            network = network
        )
        client = Statsig.client

        val expectedInterval = AUTO_VALUE_UPDATE_INTERVAL_MINIMUM_VALUE.minutes.inWholeMilliseconds
        verify(exactly = 0) {
            network.pollForChanges(
                api = any(),
                user = user,
                metadata = any(),
                updateIntervalMs = expectedInterval,
                fallbackUrls = any()
            )
        }
        client.shutdown()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testAutoValueUpdate_triggersCallback_onlyWithUpdates() = runBlocking {
        val user = StatsigUser("test_user")
        val testFlow = MutableSharedFlow<InitializeResponse.SuccessfulInitializeResponse?>()
        coEvery {
            network.pollForChanges(any(), any(), any(), any(), any())
        } coAnswers {
            testFlow
        }
        val mockPersistentCallback = mockk<IStatsigLifetimeCallback>()
        TestUtil.startStatsigAndWait(
            app,
            user,
            StatsigOptions(
                enableAutoValueUpdate = true,
                lifetimeCallback = mockPersistentCallback
            ),
            network = network
        )
        client = Statsig.client
        dispatcher.scheduler.advanceUntilIdle()

        // Expect one call from startup.
        coVerify(exactly = 1) {
            mockPersistentCallback.onValuesUpdated()
        }

        // Callback should not trigger if polling response has no updates
        runBlocking {
            testFlow.emit(TestUtil.makeInitializeResponse(hasUpdates = false))
        }

        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { mockPersistentCallback.onValuesUpdated() }

        // Expect callback to trigger when polling response has updates
        runBlocking {
            testFlow.emit(TestUtil.makeInitializeResponse(hasUpdates = true))
        }

        // TODO: See about moving this test to StandardTestDispatcher -
        //  delay() is a bad way to handle a flake-causing race condition
        delay(0.5.seconds.toJavaDuration())

        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 2) { mockPersistentCallback.onValuesUpdated() }
        client.shutdown()
    }
}
