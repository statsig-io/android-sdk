package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LogEventTest {

    private val loggingEnabled = StatsigRuntimeMutableOptions(loggingEnabled = true)
    private val loggingDisabled = StatsigRuntimeMutableOptions(loggingEnabled = false)
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var testSharedPrefs: SharedPreferences
    private lateinit var activity: Activity
    private lateinit var statsigLifecycleListener: Application.ActivityLifecycleCallbacks
    private var logEventRequests = mutableListOf<LogEventData>()
    private lateinit var network: StatsigNetwork

    internal fun setup(options: StatsigOptions) {
        runBlocking {
            TestUtil.mockHashing()
            TestUtil.mockDispatchers()
            activity = mockk()
            testSharedPrefs = TestUtil.getTestSharedPrefs(app)
            Statsig.client = StatsigClient()
            network = TestUtil.mockNetwork(onLog = {
                logEventRequests.add(it)
            })
            TestUtil.startStatsigAndWait(app, StatsigUser(userID = "testUser"), options, network)

            val lifeCycleListenerField =
                StatsigClient::class.java.getDeclaredField("lifecycleListener")
            lifeCycleListenerField.isAccessible = true
            statsigLifecycleListener =
                lifeCycleListenerField.get(Statsig.client) as Application.ActivityLifecycleCallbacks
            statsigLifecycleListener.onActivityStarted(activity)
        }
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testRetryOnAppForegrounded() {
        runBlocking {
            setup(StatsigOptions(eventLoggingAPI = "https://fake.statsig.com/v1"))
            Statsig.logEvent("viewCartIcon")
            Statsig.logEvent("clickCartIcon")
            Statsig.logEvent("viewCart")
            mockAppOnPause()
            // Wait for flush happen
            Thread.sleep(500)
            assert(logEventRequests.size == 1)
            assert(
                StatsigUtil.getFromSharedPrefs(testSharedPrefs, "StatsigNetwork.OFFLINE_LOGS")!!
                    .isNotEmpty(),
            )
            assert(logEventRequests[0].events[0].eventName == "statsig::diagnostics")
            assert(logEventRequests[0].events[1].eventName == "viewCartIcon")
            assert(logEventRequests[0].events[2].eventName == "clickCartIcon")
            assert(logEventRequests[0].events[3].eventName == "viewCart")
            mockAppOnResume()
            Thread.sleep(500)
            coVerify { network.apiRetryFailedLogs("https://fake.statsig.com/v1") }
        }
    }

    @Test
    fun testRuntimeMutableSettingsLoggingEnabled() {
        runBlocking {
            // Init with logging disabled
            setup(
                StatsigOptions(
                    eventLoggingAPI = "https://fake.statsig.com/v1",
                    loggingEnabled = false,
                ),
            )
            Statsig.logEvent("viewCartIcon")
            Statsig.logEvent("clickCartIcon")
            Statsig.logEvent(eventName = "viewCart")
            Statsig.flush()
            // Logging disabled, nothing should have gotten past the flush
            assertThat(logEventRequests).isEmpty()
            // Enable logging, verify flush works as expected
            Statsig.updateRuntimeOptions(loggingEnabled)
            Statsig.flush()
            assertThat(logEventRequests).hasSize(1)
            // Disable, verify additional events will not be flushed
            Statsig.updateRuntimeOptions(loggingDisabled)
            Statsig.logEvent("unexpectedEvent")
            Statsig.flush()
            assertThat(logEventRequests).hasSize(1)
            // Enable logging, verify flush outputs events again
            Statsig.updateRuntimeOptions(loggingEnabled)
            Statsig.flush()
            assertThat(logEventRequests).hasSize(2)
        }
    }

    @Test
    fun testRuntimeMutableSettingsLoggingEnabledMaxBufferSizeWhenDisabled() {
        // Setup with logging disabled
        runBlocking {
            setup(
                StatsigOptions(
                    eventLoggingAPI = "https://fake.statsig.com/v1",
                    loggingEnabled = false,
                ),
            )
            // Add events to exceed the StatsigLogger max buffer size
            for (x in 1..MAX_EVENT_BUFFER_SIZE) {
                Statsig.logEvent("eventName$x")
                Statsig.flush()
            }
            assertThat(logEventRequests).isEmpty()
            Statsig.updateRuntimeOptions(loggingEnabled)
            Statsig.flush()

            // Verify that "statsig::diagnostics" initialization log has fallen off the front of the queue.
            assertThat(logEventRequests[0].events[0].eventName).isNotEqualTo("statsig::diagnostics")
            assertThat(logEventRequests[0].events[0].eventName).isEqualTo("eventName1")
        }
    }

    @Test
    fun testOverrideLoggingApi() = runBlocking {
        val apiPermutations = arrayOf(
            arrayOf(
                "https://initialize.fake.statsig.com/v1",
                "https://logevent.fake.statsig.com/v1",
            ),
            arrayOf("default", "default"),
            arrayOf("https://initialize.fake.statsig.com/v1", "default"),
            arrayOf("default", "https://lgevent.fake.statsig.com/v1"),
        )
        apiPermutations.forEach {
            verifyAPI(it[0], it[1])
        }
    }

    private fun verifyAPI(initializeApi: String, logEventAPI: String) = runBlocking {
        val options = StatsigOptions()
        var expectedInitializeAPI = "https://featureassets.org/v1/"
        var expectedLogEventApi = "https://prodregistryv2.org/v1/"
        if (initializeApi != "default") {
            options.api = initializeApi
            expectedInitializeAPI = initializeApi
        }
        if (logEventAPI != "default") {
            options.eventLoggingAPI = logEventAPI
            expectedLogEventApi = logEventAPI
        }
        setup(options)
        Statsig.logEvent("viewCartIcon")
        Statsig.shutdown()
        coVerify {
            network.initialize(
                expectedInitializeAPI,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        coVerify { network.apiPostLogs(expectedLogEventApi, any(), any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun mockAppOnPause() {
        val store = Store(
            TestUtil.coroutineScope,
            testSharedPrefs,
            StatsigUser(),
            "client-apikey",
            StatsigOptions(),
        )
        val network = spyk(
            StatsigNetwork(
                app,
                "client-apikey",
                testSharedPrefs,
                StatsigOptions(),
                mockk(),
                TestUtil.coroutineScope,
                store,
            ),
        )
        coEvery {
            network.apiPostLogs(any(), any(), any())
        } answers {
            logEventRequests.add(
                StatsigUtil.getGson().fromJson(secondArg<String>(), LogEventData::class.java),
            )
            callOriginal()
        }
        coEvery {
            network.addFailedLogRequest(any())
        } coAnswers {
            StatsigUtil.saveStringToSharedPrefs(
                testSharedPrefs,
                "StatsigNetwork.OFFLINE_LOGS",
                StatsigUtil.getGson().toJson(StatsigPendingRequests(listOf(firstArg()))),
            )
        }
        mockNetwork(network)
        statsigLifecycleListener.onActivityPaused(activity)
    }

    private fun mockAppOnResume() {
        mockNetwork(network)
        statsigLifecycleListener.onActivityResumed(activity)
    }

    private fun mockNetwork(network: StatsigNetwork) {
        Statsig.client.statsigNetwork = network
        val loggerField = StatsigClient::class.java.getDeclaredField("logger")
        loggerField.isAccessible = true
        val logger = loggerField.get(Statsig.client)
        val networkField = StatsigLogger::class.java.getDeclaredField("statsigNetwork")
        networkField.isAccessible = true
        networkField.set(logger, network)
    }
}
