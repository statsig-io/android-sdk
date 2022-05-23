package com.statsig.androidsdk

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.junit.WireMockRule
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import java.io.IOException

class ErrorBoundaryTest {
  private lateinit var boundary: ErrorBoundary

  @Before
  internal fun setup() {
    boundary = ErrorBoundary()
    boundary.setKey("client-key")
    boundary.urlString = wireMockRule.url("/v1/sdk_exception")

    stubFor(post(urlMatching("/v1/sdk_exception")).willReturn(aResponse().withStatus(202)))
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
        equalTo("client-key")
      ).withRequestBody(matchingJsonPath("\$[?(@.exception == 'java.io.IOException')]"))
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

    verify(1,
      postRequestedFor(urlEqualTo("/v1/sdk_exception")).withHeader(
        "STATSIG-API-KEY",
        equalTo("client-key")
      )
    )
  }
}
