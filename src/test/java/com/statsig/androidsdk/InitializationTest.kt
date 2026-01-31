package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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
class InitializationTest {
    private lateinit var app: Application
    private lateinit var mockWebServer: MockWebServer
    private var user: StatsigUser = StatsigUser("test-user")
    private var initializationHits = 0

    @Before
    internal fun setup() {
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockDispatchers()
        mockWebServer = MockWebServer()
        TestUtil.mockHashing()

        initializationHits = 0
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("initialize")) {
                    ++initializationHits
                    return MockResponse().setResponseCode(408)
                } else {
                    MockResponse().setResponseCode(200)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
        TestUtil.setupHttp(app)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        TestUtil.reset()
    }

    @Test
    fun testDefaultInitialization() = runTest {
        val options = StatsigOptions(api = mockWebServer.url("/v1").toString())
        val client = StatsigClient()
        client.initialize(app, "client-key", user, options)
        assertThat(initializationHits).isEqualTo(1)
        client.shutdown()
    }

    @Test
    fun testRetry() = runTest {
        val options =
            StatsigOptions(
                api = mockWebServer.url("/v1").toString(),
                initRetryLimit = 2,
                initTimeoutMs = 10000L
            )
        val client = StatsigClient()

        client.initialize(app, "client-key", user, options)
        assertThat(initializationHits).isEqualTo(3)
        client.shutdown()
    }
}
