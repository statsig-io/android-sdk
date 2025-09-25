package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
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
    private lateinit var dispatcher: TestCoroutineDispatcher
    private lateinit var app: Application
    private lateinit var mockWebServer: MockWebServer
    private var user: StatsigUser = StatsigUser("test-user")
    private lateinit var testSharedPrefs: SharedPreferences
    private var initializationHits = 0

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    internal fun setup() {
        mockWebServer = MockWebServer()

        dispatcher = TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)
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

        client.initialize(app, "client-key", user, options)
        assert(initializationHits == 3)
        client.shutdown()
    }
}
