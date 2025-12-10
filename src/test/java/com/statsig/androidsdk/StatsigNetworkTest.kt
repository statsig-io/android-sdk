package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_STABLE_ID_HEADER_KEY
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigNetworkTest {
    // Dynamic port so this test can run in parallel with other wiremock tests
    @Rule
    @JvmField
    val wireMockRule = WireMockRule(options().dynamicPort())

    private val app: Application = RuntimeEnvironment.getApplication()
    private val overrideID: String = "override_id"
    private val metadata = StatsigMetadata()

    private val user = StatsigUser()

    private val gson = StatsigUtil.getOrBuildGson()
    private lateinit var network: StatsigNetworkImpl

    private lateinit var options: StatsigOptions
    private lateinit var fallbackResolver: NetworkFallbackResolver
    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setup() {
        val dispatcher = TestUtil.mockDispatchers()
        val coroutineScope = TestScope(dispatcher)
        TestUtil.mockHashing()
        sharedPreferences = TestUtil.getTestSharedPrefs(app)
        TestUtil.setupHttp(app)

        stubFor(
            post(urlMatching("/initialize"))
                .willReturn(aResponse().withStatus(202))
        )

        stubFor(
            post(urlMatching("/log_event"))
                .willReturn(aResponse().withStatus(202))
        )

        options = StatsigOptions(api = wireMockRule.baseUrl())
        fallbackResolver =
            NetworkFallbackResolver(
                sharedPreferences,
                coroutineScope,
                gson = gson
            )
        val store = spyk<Store>(
            Store(
                coroutineScope,
                sharedPreferences,
                user,
                "client-apikey",
                options,
                gson = gson
            )
        )
        every {
            store.getSDKFlags()
        } answers {
            mapOf("enable_log_event_compression" to true)
        }
        network =
            StatsigNetworkImpl(
                app,
                "client-key",
                TestUtil.getTestSharedPrefs(app),
                options,
                networkResolver = fallbackResolver,
                coroutineScope,
                store,
                gson = gson
            )
    }

    @After
    fun teardown() {
        TestUtil.reset()
    }

    @Test
    fun initialize_includesStableIDinHeader() {
        metadata.overrideStableID(overrideID)

        runBlocking { makeInitializeRequest() }

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/initialize")
            ).withHeader(STATSIG_STABLE_ID_HEADER_KEY, equalTo(overrideID))
        )
    }

    @Test
    fun initialize_declaresAcceptEncoding() {
        runBlocking { makeInitializeRequest() }

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/initialize")
            ).withHeader("Accept-Encoding", equalTo(HttpUtils.ENCODING_GZIP))
        )
    }

    @Test
    fun logEvent_gzipsRequestBody() {
        val event = LogEvent("eventName")
        runBlocking { makeLogEventRequest(listOf(event)) }

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/log_event")
            ).withHeader(HttpUtils.CONTENT_ENCODING_HEADER_KEY, equalTo(HttpUtils.ENCODING_GZIP))
        )
    }

    private suspend fun makeInitializeRequest() {
        try {
            network.initializeImpl(
                wireMockRule.baseUrl(),
                user,
                null,
                metadata,
                ContextType.INITIALIZE,
                null,
                1,
                50,
                HashAlgorithm.NONE,
                mapOf(),
                null
            )
        } catch (e: Exception) {
            // noop
        }
    }

    private suspend fun makeLogEventRequest(events: List<LogEvent>) {
        val logEventBody = LogEventData(ArrayList(events), metadata)
        try {
            network.apiPostLogs(
                api = wireMockRule.baseUrl(),
                bodyString = gson.toJson(logEventBody),
                eventsCount = "1",
                fallbackUrls = null
            )
        } catch (e: Exception) {
            throw e
        }
    }
}
