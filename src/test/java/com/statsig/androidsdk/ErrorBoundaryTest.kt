package com.statsig.androidsdk

import android.app.Application
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ErrorBoundaryTest {
    private lateinit var boundary: ErrorBoundary
    private lateinit var app: Application

    @Before
    internal fun setup() {
        boundary = ErrorBoundary()
        boundary.setKey("client-key")
        boundary.urlString = wireMockRule.url("/v1/sdk_exception")

        stubFor(post(urlMatching("/v1/sdk_exception")).willReturn(aResponse().withStatus(202)))

        app = mockk()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        val network = TestUtil.mockBrokenNetwork()
        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = network
        Statsig.errorBoundary = boundary
    }

    @After
    internal fun teardown() {
        unmockkAll()
    }

    @Rule
    @JvmField
    val wireMockRule = WireMockRule()

    @Test
    fun testLoggingToEndpoint() {
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
    fun testItDoesNotLogTheSameExceptionMultipleTimes() {
        boundary.capture({
            throw IOException("Test")
        })

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
    fun testInitializeIsCaptured() {
        try {
            runBlocking {
                Statsig.client.initialize(app, "client-key", null)
                Statsig.shutdown()
            }
        } catch (e: Throwable) {
            assertTrue(false) // should not throw
        }
        verify(
            1,
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ),
        )
    }

    @Test
    fun testInitializeAsyncIsCaptured() {
        try {
            runBlocking {
                Statsig.client.initializeAsync(app, "client-key", null)
                Statsig.shutdown()
            }
        } catch (e: Throwable) {
            assertTrue(false) // should not throw
        }
        verify(
            1,
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ),
        )
    }

    @Test
    fun testCoroutineExceptionHandler() {
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                // Generally, we don't expect exceptions from user defined callbacks to be caught
                // in our error boundary, but until we implement a way to filter them out
                // this test will use it as a way to test capturing via CoroutineExceptionHandler
                throw IOException("Thrown from onStatsigInitialize")
            }

            override fun onStatsigUpdateUser() {}
        }
        try {
            runBlocking {
                Statsig.client.initializeAsync(app, "client-key", null, callback)
                Statsig.shutdown()
            }
        } catch (e: Throwable) {
            assertTrue(false) // should not throw
        }
        verify(
            1,
            postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
                "STATSIG-API-KEY",
                equalTo("client-key"),
            ),
        )
    }
}
