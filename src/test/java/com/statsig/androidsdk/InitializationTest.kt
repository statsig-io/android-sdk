package com.statsig.androidsdk

import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class InitializationTest {
    private val dispatcher = TestUtil.mockDispatchers()

    private lateinit var app: Application
    private lateinit var mockWebServer: MockWebServer
    private var user: StatsigUser = StatsigUser("test-user")
    private lateinit var testSharedPrefs: TestSharedPreferences
    private var initializationHits = 0

    @Before
    internal fun setup() {
        mockWebServer = MockWebServer()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()
        TestUtil.mockNetworkConnectivityService(app)

        initializationHits = 0
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.path!!.contains("initialize")) {
                    ++initializationHits
                    if (initializationHits == 1) {
                        throw Exception("Fake exception")
                    } else {
                        return MockResponse().setResponseCode(400)
                    }
                } else {
                    MockResponse().setResponseCode(200)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testDefaultInitialization() = dispatcher.runBlockingTest {
        val options = StatsigOptions(api = mockWebServer.url("/v1").toString())
        val client = StatsigClient()
        client.statsigScope = TestCoroutineScope()
        client.initialize(app, "client-key", user, options)
        assert(initializationHits == 1)
        client.shutdown()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testRetry() = dispatcher.runBlockingTest {
        val options = StatsigOptions(api = mockWebServer.url("/v1").toString(), initRetryLimit = 2, initTimeoutMs = 10000L)
        val client = StatsigClient()
        client.statsigScope = TestCoroutineScope()
        client.initialize(app, "client-key", user, options)
        dispatcher.advanceTimeBy(5)

        assert(initializationHits == 3)
        client.shutdown()
    }
}
