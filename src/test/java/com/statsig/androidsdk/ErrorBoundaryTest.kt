package com.statsig.androidsdk

import android.app.Application
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit.WireMockRule
import io.mockk.coEvery
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
        Statsig.client = StatsigClient()
        TestUtil.mockBrokenServer()
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
                Statsig.client.statsigNetwork = mockk()
                coEvery {
                    Statsig.client.statsigNetwork.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } answers {
                    throw IOException("Example exception in StatsigNetwork initialize")
                }
                Statsig.client.initialize(
                    app,
                    "client-key",
                    null,
                    options = StatsigOptions(disableDiagnosticsLogging = true),
                )
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
                Statsig.client.statsigNetwork = mockk()
                coEvery {
                    Statsig.client.statsigNetwork.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any())
                } answers {
                    throw IOException("Example exception in StatsigNetwork initialize")
                }
                Statsig.client.initializeAsync(
                    app,
                    "client-key",
                    null,
                    options = StatsigOptions(disableDiagnosticsLogging = true),
                )
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
        // Expect exceptions thrown from coroutines without explicit capture statements
        // are still caught by the ErrorBoundary via the CoroutineExceptionHandler
        try {
            runBlocking {
                coEvery {
                    Statsig.client.setupAsync(any())
                } answers {
                    throw IOException("Example exception in StatsigClient setupAsync")
                }
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
        try {
            runBlocking {
                Statsig.client.initializeAsync(app, "client-key", null, callback)
                Statsig.client.updateUserAsync(null, callback)
                Statsig.shutdown()
            }
        } catch (e: Throwable) {
            assertTrue(false) // should not throw
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
