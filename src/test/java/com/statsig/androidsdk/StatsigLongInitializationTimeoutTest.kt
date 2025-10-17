package com.statsig.androidsdk

import android.app.Application
import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigLongInitializationTimeoutTest {

    private var app: Application = RuntimeEnvironment.getApplication()
    private lateinit var client: StatsigClient
    private lateinit var errorBoundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private var initializeHits = 0

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("initialize")) {
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
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        client = spyk(StatsigClient(), recordPrivateCalls = true)
        client.errorBoundary = spyk(client.errorBoundary)
        errorBoundary = client.errorBoundary

        TestUtil.mockDispatchers()

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
    fun testInitializeAsyncWithSlowErrorBoundary() = runBlocking {
        val initTimeout = 10_000L // ms
        val latch = CountDownLatch(1)

        client.initializeAsync(
            app,
            "client-key",
            StatsigUser("test_user"),
            object : IStatsigCallback {
                override fun onStatsigInitialize(details: InitializationDetails) {
                    latch.countDown()
                }
                override fun onStatsigUpdateUser() {}
            },
            StatsigOptions(initTimeoutMs = initTimeout, api = mockWebServer.url("/").toString())
        )
        latch.await(10, TimeUnit.SECONDS)

        assert(client.isInitialized())
        assertTrue(initializeHits == 1)
    }
}
