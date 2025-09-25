package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class StatsigInitializationTimeoutTest {

    private var app: Application = RuntimeEnvironment.getApplication()
    private lateinit var client: StatsigClient
    private lateinit var network: StatsigNetwork
    private lateinit var errorBoundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private val hitErrorBoundary = CountDownLatch(1)

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("sdk_exception")) {
                    hitErrorBoundary.countDown()
                    runBlocking {
                        delay(1000)
                    }
                    MockResponse()
                        .setBody("{\"result\":\"error logged\"}")
                        .setResponseCode(200)
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
        network = TestUtil.mockNetwork()

        TestUtil.mockDispatchers()

        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            TestUtil.makeInitializeResponse()
        }

        // Lets get a successful network response, and then trigger error boundary
        // so we can test that eb does not block the initialization beyond the init timeout
        every {
            println("causing exception")
            client["pollForUpdates"]()
        } throws(Exception("trigger the error boundary"))

        every {
            errorBoundary.getUrl()
        } returns mockWebServer.url("/v1/sdk_exception").toString()

        client.statsigNetwork = network
        client.errorBoundary = errorBoundary
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testInitializeAsyncWithSlowErrorBoundary() = runBlocking {
        var initializationDetails: InitializationDetails? = null
        var initTimeout = 500L
        runBlocking {
            initializationDetails = client.initialize(app, "client-key", StatsigUser("test_user"), StatsigOptions(initTimeoutMs = initTimeout))
        }
        // initialize timeout was hit, we got a value back and we are considered initialized
        assert(initializationDetails != null)
        assert(client.isInitialized())

        // error boundary was hit, but has not completed at this point, so the initialization timeout worked
        assertTrue(hitErrorBoundary.await(1, TimeUnit.SECONDS))
        assertTrue(
            "initialization time ${initializationDetails!!.duration} not less than initTimeout $initTimeout",
            initializationDetails!!.duration < initTimeout + 100L,
        )

        // error boundary was hit, but has not completed at this point, so the initialization timeout worked
        assert(hitErrorBoundary.await(1, TimeUnit.SECONDS))
    }
}
