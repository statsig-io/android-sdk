package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ErrorBoundaryTest {
    private lateinit var boundary: ErrorBoundary
    private lateinit var mockWebServer: MockWebServer
    private var app: Application = RuntimeEnvironment.getApplication()

    @Before
    internal fun setup() {
        val dispatcher = TestUtil.mockDispatchers()
        val coroutineScope = TestScope(dispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        boundary = ErrorBoundary(coroutineScope)
        boundary.initialize("client-key")

        boundary.urlString = mockWebServer.url("/v1/sdk_exception").toString()

        TestUtil.setupHttp(app)
        TestUtil.mockHashing()
        val network = TestUtil.mockBrokenNetwork()
        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = network
        Statsig.client.errorBoundary = boundary
    }

    @After
    internal fun teardown() {
        TestUtil.reset()
        mockWebServer.shutdown()
    }

    @Test
    fun testLoggingToEndpoint() = runTest {
        boundary.capture({
            throw IOException("Test")
        })

        val request = mockWebServer.takeRequest()

        assertThat(request.headers).contains("STATSIG-API-KEY" to "client-key")
        assertThat(request.requestUrl.toString()).contains("/v1/sdk_exception")
        assertThat(request.body.toString())
            .contains(""""exception":"java.io.IOException"""")
    }

    @Test
    fun testRecovery() {
        var called = false
        boundary.capture({
            arrayOf(1)[2]
        }, recover = {
            called = true
        })

        assertTrue(called)
    }

    @Test
    fun testItDoesNotLogTheSameExceptionMultipleTimes() {
        boundary.capture({
            throw IOException("Test")
        })

        val request = mockWebServer.takeRequest()
        val request2 = mockWebServer.takeRequest(1, TimeUnit.SECONDS)

        assertThat(request.headers).contains("STATSIG-API-KEY" to "client-key")
        assertThat(request.requestUrl.toString()).contains("/v1/sdk_exception")
        assertThat(request.body.toString())
            .contains(""""exception":"java.io.IOException"""")

        assertThat(request2).isNull()
    }

    @Test
    fun testExternalException() = runTest {
        // Expect exceptions thrown from user defined callbacks to be caught
        // by the ErrorBoundary but not logged
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize(): Unit =
                throw IOException("Thrown from onStatsigInitialize")

            override fun onStatsigUpdateUser(): Unit =
                throw IOException("Thrown from onStatsigUpdateUser")
        }
        Statsig.client.statsigNetwork = TestUtil.mockNetwork()
        try {
            runBlocking {
                Statsig.client.initializeAsync(app, "client-key", null, callback)
                Statsig.client.updateUserAsync(null, callback)
                Statsig.shutdown()
            }
        } catch (e: Throwable) {
            // Test fails if an error escapes the boundary
            fail("Non-callback error was thrown within boundary: $e")
        }

        // Assert that no exceptions were logged by the error boundary.
        val request = mockWebServer.takeRequest(1, TimeUnit.SECONDS)
        assertThat(request).isNull()
    }
}
