package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LogEventRetryTest {
    private lateinit var mockWebServer: MockWebServer
    private var logEventHits = 0
    private val gson = Gson()
    private val app: Application = RuntimeEnvironment.getApplication()
    private var enforceLogEventException = false

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        logEventHits = 0
        TestUtil.setupHttp(app)
        val testClient = HttpUtils.okHttpClient!!.newBuilder().addInterceptor(
            testFailureInterceptor
        ).build()
        HttpUtils.okHttpClient = testClient
        TestUtil.mockDispatchers()
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
                    return response
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
    }

    @After
    fun teardown() {
        TestUtil.reset()
    }

    @Test
    fun testRetryOnRetryCode() = runBlocking {
        val url = mockWebServer.url("/v1").toString()
        Statsig.initialize(
            app,
            "client-key",
            StatsigUser("test"),
            StatsigOptions(api = url, eventLoggingAPI = url)
        )
        Statsig.logEvent("test-event1")
        Statsig.shutdown()
        assertThat(logEventHits).isEqualTo(2)
    }

    @Test
    fun testNoRetryOnException() = runBlocking {
        val url = mockWebServer.url("/v1").toString()
        Statsig.initialize(
            app,
            "client-key",
            StatsigUser("test"),
            StatsigOptions(api = url, eventLoggingAPI = url)
        )
        enforceLogEventException = true
        Statsig.logEvent("test-event1")
        Statsig.shutdown()
        assertThat(logEventHits).isEqualTo(1)
    }

    private val testFailureInterceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (enforceLogEventException && logEventHits > 0) {
                throw IOException("Artificially injected network failure")
            }
            return chain.proceed(chain.request())
        }
    }
}
