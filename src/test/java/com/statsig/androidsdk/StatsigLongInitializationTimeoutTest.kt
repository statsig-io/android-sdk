package com.statsig.androidsdk

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runBlockingTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatsigLongInitializationTimeoutTest {

    private var app: Application = mockk()
    private lateinit var client: StatsigClient
    private lateinit var errorBoundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private var initializeHits = 0

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("initialize")) {
                    initializeHits++
                    runBlocking {
                        delay(500)
                    }
                    MockResponse()
                        .setBody("{\"result\":\"error logged\"}")
                        .setResponseCode(503)
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        client = spyk(StatsigClient(), recordPrivateCalls = true)
        client.errorBoundary = spyk(client.errorBoundary)
        errorBoundary = client.errorBoundary

        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)

        every {
            errorBoundary.getUrl()
        } returns mockWebServer.url("/v1/sdk_exception").toString()

        client.errorBoundary = errorBoundary
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testInitializeAsyncWithSlowErrorBoundary() = runBlockingTest {
        var initTimeout = 10000L
        val latch = CountDownLatch(1)

        client.initializeAsync(
            app,
            "client-key",
            StatsigUser("test_user"),
            object : IStatsigCallback {
                override fun onStatsigInitialize(details: InitializationDetails) {
                    latch.countDown()
                }

                override fun onStatsigUpdateUser() {
                    // no op
                }
            },
            StatsigOptions(initTimeoutMs = initTimeout, api = mockWebServer.url("/").toString()),
        )
        latch.await(initTimeout, TimeUnit.SECONDS)
        assert(client.isInitialized())
        assertTrue(initializeHits === 1)
    }
}
