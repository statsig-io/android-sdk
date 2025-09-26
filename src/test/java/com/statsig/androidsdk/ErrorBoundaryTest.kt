package com.statsig.androidsdk

import android.app.Application
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ErrorBoundaryTest {
    private lateinit var boundary: ErrorBoundary
    private var app: Application = RuntimeEnvironment.getApplication()

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()
        val coroutineScope = TestCoroutineScope()
        boundary = ErrorBoundary(coroutineScope)
        boundary.setKey("client-key")
        boundary.urlString = wireMockRule.url("/v1/sdk_exception")

        stubFor(post(urlMatching("/v1/sdk_exception")).willReturn(aResponse().withStatus(202)))

        TestUtil.mockHashing()
        val network = TestUtil.mockBrokenNetwork()
        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = network
        Statsig.client.errorBoundary = boundary
    }

    @After
    internal fun teardown() {
        unmockkAll()
    }

    @Rule
    @JvmField
    val wireMockRule = WireMockRule()

    @Test
    fun testLoggingToEndpoint() = runBlockingTest {
        boundary.capture({
            throw IOException("Test")
        })

        verify(
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ).withRequestBody(matchingJsonPath("\$[?(@.exception == 'java.io.IOException')]")),
        )
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
    fun testItDoesNotLogTheSameExceptionMultipleTimes() = runBlockingTest {
        boundary.capture({
            throw IOException("Test")
        })

        verify(
            1,
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ),
        )
    }

    @Test
    fun testExternalException() {
        // Expect exceptions thrown from user defined callbacks to be caught
        // by the ErrorBoundary but not logged
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                throw IOException("Thrown from onStatsigInitialize")
            }

            override fun onStatsigUpdateUser() {
                throw IOException("Thrown from onStatsigUpdateUser")
            }
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
        verify(
            0,
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ),
        )
    }
}
