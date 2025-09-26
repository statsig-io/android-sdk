package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InitializationRetryFailedLogsTest {
    private lateinit var mockWebServer: MockWebServer
    private var logEventHits = 0
    private val gson = Gson()
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var url: String

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        TestUtil.mockDispatchers()

        url = mockWebServer.url("/v1").toString()
        val sharedPrefs = TestUtil.getTestSharedPrefs(app)

        val failedLogsJson = this::class.java.classLoader!!
            .getResource("sample_failed_logs.json")
            .readText()

        sharedPrefs.edit()
            .putString("StatsigNetwork.OFFLINE_LOGS:client-key", failedLogsJson)
            .commit()

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path!!.contains("initialize") -> {
                        MockResponse()
                            .setBody(gson.toJson(TestUtil.makeInitializeResponse()))
                            .setResponseCode(200)
                    }
                    request.path!!.contains("log_event") -> {
                        logEventHits++
                        MockResponse().setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
    }

    @Test
    fun testStoredFailedLogsDoNotBlockInitialize() = runBlocking {
        Statsig.initialize(
            app,
            "client-key",
            StatsigUser("test"),
            StatsigOptions(api = url, eventLoggingAPI = url),
        )
        assertEquals(0, logEventHits)

        val gateResult = Statsig.checkGate("test_gate")

        Statsig.shutdown()

        assert(!gateResult)
    }

    @Test
    fun testStoredFailedLogsDoNotBlockInitializeAsync() = runBlocking {
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize(initDetails: InitializationDetails) {
                assertEquals(0, logEventHits)
            }

            override fun onStatsigUpdateUser() {
                // Not needed for this test
            }
        }
        Statsig.initializeAsync(
            app,
            "client-key",
            StatsigUser("test"),
            callback,
            StatsigOptions(api = url, eventLoggingAPI = url),
        )

        val gateResult = Statsig.checkGate("test_gate")

        Statsig.shutdown()

        assert(!gateResult)
    }

    @Test
    fun testRetryingAndDroppingStoredFailedLogs() = runBlocking {
        var receivedRequestBodies = mutableListOf<String>()
        val maxAttemptsPerRequest = 3

        // Mock server with handler to track received requests
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("log_event")) {
                    receivedRequestBodies.add(request.body.readUtf8())
                    return MockResponse().setResponseCode(500) // Force failure to trigger retries
                }
                return MockResponse()
                    .setBody(gson.toJson(TestUtil.makeInitializeResponse()))
                    .setResponseCode(200)
            }
        }

        Statsig.initialize(
            app,
            "client-key",
            StatsigUser("test"),
            StatsigOptions(api = url, eventLoggingAPI = url),
        )
        Statsig.shutdown()

        // Group received requests by unique requestBody
        val requestCountMap = receivedRequestBodies.groupingBy { it }.eachCount()
        // Assert: no more than 10 distinct requests sent (others were dropped)
        assert(requestCountMap.size <= 10) {
            "Expected at most 10 unique log_event requests, got ${requestCountMap.size}"
        }

        // Assert: no request was retried more than 3 times
        val maxRetriesSeen = requestCountMap.values.maxOrNull() ?: 0
        assert(maxRetriesSeen <= maxAttemptsPerRequest) {
            "Expected max 3 retries per request, got $maxRetriesSeen"
        }
    }
}
