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
import org.junit.Before
import org.junit.Test
class LogEventTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private lateinit var activity: Activity
    private lateinit var statsigLifecycleListener: Application.ActivityLifecycleCallbacks
    private var logEventRequests = mutableListOf<LogEventData>()
    private lateinit var network: StatsigNetwork

    @Before
    internal fun setup() {
        runBlocking {
            TestUtil.mockStatsigUtil()
            TestUtil.mockDispatchers()
            app = mockk()
            activity = mockk()
            testSharedPrefs = TestUtil.stubAppFunctions(app)
            Statsig.client = StatsigClient()
            network = TestUtil.mockNetwork(onLog = {
                logEventRequests.add(it)
            })
            TestUtil.startStatsigAndWait(app, StatsigUser(userID = "testUser"), StatsigOptions(disableDiagnosticsLogging = true), network)
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
            Statsig.client.statsigNetwork = StatsigNetwork("client-apikey")
            Statsig.logEvent("viewCartIcon")
            Statsig.logEvent("clickCartIcon")
            Statsig.logEvent("viewCart")
            mockAppOnPause()
            // Wait for flush happen
            Thread.sleep(500)
            assert(logEventRequests.size == 1)
            assert(StatsigUtil.getFromSharedPrefs(testSharedPrefs, "StatsigNetwork.OFFLINE_LOGS")!!.isNotEmpty())
            assert(logEventRequests[0].events[0].eventName == "viewCartIcon")
            assert(logEventRequests[0].events[1].eventName == "clickCartIcon")
            assert(logEventRequests[0].events[2].eventName == "viewCart")
            mockAppOnResume()
            Thread.sleep(500)
            coVerify { network.apiRetryFailedLogs(any()) }
        }
    }

    private fun mockAppOnPause() {
        val network = spyk(StatsigNetwork("client-apikey"))
        coEvery {
            network.apiPostLogs(any(), any())
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
