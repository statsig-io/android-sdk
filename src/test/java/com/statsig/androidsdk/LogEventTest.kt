package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
class LogEventTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private lateinit var activity: Activity
    private lateinit var statsigLifecycleListener: Application.ActivityLifecycleCallbacks
    private var logEventRequests = mutableListOf<LogEventData>()
    private lateinit var webServer: MockWebServer
    private lateinit var brokenWebServer: MockWebServer

    @Before
    internal fun setup() {
        runBlocking {
            TestUtil.mockStatsigUtil()
            app = mockk()
            activity = mockk()
            testSharedPrefs = TestUtil.stubAppFunctions(app)
            Statsig.client = StatsigClient()
            brokenWebServer = TestUtil.mockServer(responseCode = 400, onLog = {
                logEventRequests.add(it)
            })
            webServer = TestUtil.mockServer(onLog = {
                logEventRequests.add(it)
            })
            TestUtil.startStatsigAndWait(app, StatsigUser(userID = "testUser"), StatsigOptions(disableDiagnosticsLogging = true), webServer)
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
            Statsig.logEvent("viewCartIcon")
            Statsig.logEvent("clickCartIcon")
            Statsig.logEvent("viewCart")
            mockAppOnPause()
            // Wait for flush happen
            Thread.sleep(200)
            assert(logEventRequests.size == 1)
            assert(StatsigUtil.getFromSharedPrefs(testSharedPrefs, "StatsigNetwork.OFFLINE_LOGS")!!.isNotEmpty())
            assert(logEventRequests[0].events[0].eventName == "viewCartIcon")
            assert(logEventRequests[0].events[1].eventName == "clickCartIcon")
            assert(logEventRequests[0].events[2].eventName == "viewCart")
            mockAppOnResume()
            Thread.sleep(200)
            assert(logEventRequests[1].events[0].eventName == "viewCartIcon")
            assert(logEventRequests[1].events[1].eventName == "clickCartIcon")
            assert(logEventRequests[1].events[2].eventName == "viewCart")
        }
    }

    private fun mockAppOnPause() {
        overrideAPI(brokenWebServer.url("/v1").toString())
        statsigLifecycleListener.onActivityPaused(activity)
    }

    private fun mockAppOnResume() {
        overrideAPI(webServer.url("/v1").toString())
        statsigLifecycleListener.onActivityResumed(activity)
    }

    private fun overrideAPI(overrideAPI: String) {
        val loggerField = StatsigClient::class.java.getDeclaredField("logger")
        loggerField.isAccessible = true
        val logger = loggerField.get(Statsig.client)
        val apiField = StatsigLogger::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        apiField.set(logger, overrideAPI)
    }
}
