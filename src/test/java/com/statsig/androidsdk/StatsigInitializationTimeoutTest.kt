package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
class StatsigInitializationTimeoutTest {

    private var app: Application = RuntimeEnvironment.getApplication()
    private lateinit var client: StatsigClient
    private lateinit var network: StatsigNetwork
    private lateinit var errorBoundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private val hitErrorBoundary = CountDownLatch(1)
    private val allowErrorBoundaryResponse = CountDownLatch(1)
    private val errorBoundaryResponseReleased = AtomicBoolean(false)

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        TestUtil.setupHttp(app)
        mockWebServer = MockWebServer()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("sdk_exception")) {
                    hitErrorBoundary.countDown()
                    allowErrorBoundaryResponse.await(5, TimeUnit.SECONDS)
                    errorBoundaryResponseReleased.set(true)
                    MockResponse()
                        .setBody("{\"result\":\"error logged\"}")
                        .setResponseCode(200)
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        client = spyk(StatsigClient(), recordPrivateCalls = true)

        errorBoundary = client.errorBoundary
        errorBoundary.urlString = mockWebServer.url("/v1/sdk_exception").toString()

        network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            TestUtil.makeInitializeResponse()
        }

        // Lets get a successful network response, and then trigger error boundary
        // so we can test that eb does not block initialization
        every {
            println("causing exception")
            client["pollForUpdates"]()
        } throws (Exception("trigger the error boundary"))

        client.statsigNetwork = network
        client.errorBoundary = errorBoundary
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        TestUtil.reset()
    }

    @Test
    fun testInitializeAsyncWithSlowErrorBoundary() = runBlocking {
        val initializationDetails: InitializationDetails? = client.initialize(
            app,
            "client-key",
            StatsigUser("test_user"),
            StatsigOptions()
        )
        // Received response and are initialized, despite error boundary hit
        assert(initializationDetails != null)
        assert(client.isInitialized())

        // error boundary was hit, but response is still blocked at this point,
        // so it did not block initialize()
        assertThat(hitErrorBoundary.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(errorBoundaryResponseReleased.get()).isFalse()
        allowErrorBoundaryResponse.countDown()
    }
}
