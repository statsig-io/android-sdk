package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

class LogEventRetryTest {
    private lateinit var mockWebServer: MockWebServer
    private var logEventHits = 0
    private val gson = Gson()
    private val app: Application = mockk()
    private var enforceLogEventException = false

    @Before
    fun setup() {
        logEventHits = 0
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("initialize")) {
                    MockResponse()
                        .setBody(gson.toJson(TestUtil.makeInitializeResponse()))
                        .setResponseCode(200)
                } else if (request.path!!.contains("log_event")) {
                    logEventHits++
                    val logEventStatusCode = if (logEventHits >= 2) 404 else 599
                    val response = MockResponse().setResponseCode(logEventStatusCode)
                    if (!enforceLogEventException) {
                        response.setBody("err")
                    }
                    return response
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
    }

    @Test
    fun testRetryOnRetryCode() = runBlocking {
        val url = mockWebServer.url("/v1").toString()
        Statsig.initialize(app, "client-key", StatsigUser("test"), StatsigOptions(api = url, eventLoggingAPI = url))
        Statsig.logEvent("test-event1")
        Statsig.shutdown()
        assert(logEventHits == 2)
    }

    @Test
    fun testNoRetryOnException() = runBlocking {
        val url = mockWebServer.url("/v1").toString()
        Statsig.initialize(app, "client-key", StatsigUser("test"), StatsigOptions(api = url, eventLoggingAPI = url))
        enforceLogEventException = true
        Statsig.logEvent("test")
        Statsig.shutdown()
        assert(logEventHits == 1)
    }
}
