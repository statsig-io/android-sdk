package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
class LogEventTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private lateinit var activity: Activity
    private lateinit var statsigLifecycleListener: Application.ActivityLifecycleCallbacks
    private var logEventRequests = mutableListOf<LogEventData>()
    private lateinit var network: StatsigNetwork

    internal fun setup(options: StatsigOptions) {
        runBlocking {
            TestUtil.mockStatsigUtil()
            TestUtil.mockDispatchers()
            app = mockk()
            TestUtil.mockNetworkConnectivityService(app)
            activity = mockk()
            testSharedPrefs = TestUtil.stubAppFunctions(app)
            Statsig.client = StatsigClient()
            network = TestUtil.mockNetwork(onLog = {
                logEventRequests.add(it)
            })
            TestUtil.startStatsigAndWait(app, StatsigUser(userID = "testUser"), options, network)
            val lifeCycleListenerField = StatsigClient::class.java.getDeclaredField("lifecycleListener")
            lifeCycleListenerField.isAccessible = true
            statsigLifecycleListener = lifeCycleListenerField.get(Statsig.client) as Application.ActivityLifecycleCallbacks
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
            assert(StatsigUtil.getFromSharedPrefs(testSharedPrefs, "StatsigNetwork.OFFLINE_LOGS")!!.isNotEmpty())
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
    fun testOverrideLoggingApi() = runBlocking {
        val apiPermutations = arrayOf(
            arrayOf("https://initialize.fake.statsig.com/v1", "https://logevent.fake.statsig.com/v1"),
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
        var expectedInitializeAPI = "https://api.statsig.com/v1"
        var expectedLogEventApi = "https://api.statsig.com/v1"
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
        coVerify { network.initialize(expectedInitializeAPI, any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify { network.apiPostLogs(expectedLogEventApi, any(), any()) }
    }

    private fun mockAppOnPause() {
        val network = spyk(StatsigNetwork(app, "client-apikey", Statsig.client.errorBoundary))
        coEvery {
            network.apiPostLogs(any(), any(), any())
        } answers {
            logEventRequests.add(StatsigUtil.getGson().fromJson(secondArg<String>(), LogEventData::class.java))
            callOriginal()
        }
        coEvery {
            network.addFailedLogRequest(any())
        } coAnswers {
            StatsigUtil.saveStringToSharedPrefs(testSharedPrefs, "StatsigNetwork.OFFLINE_LOGS", firstArg())
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
