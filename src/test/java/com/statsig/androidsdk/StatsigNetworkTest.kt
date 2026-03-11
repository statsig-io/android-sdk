package com.statsig.androidsdk

import android.app.Application
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.github.tomakehurst.wiremock.junit.WireMockRule
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_STABLE_ID_HEADER_KEY
import io.mockk.every
import io.mockk.spyk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
    private lateinit var keyValueStorage: KeyValueStorage<String>

    @Before
    fun setup() {
        val dispatcher = TestUtil.mockDispatchers()
        val coroutineScope = TestScope(dispatcher)
        TestUtil.mockHashing()
        keyValueStorage = TestUtil.getTestKeyValueStore(app)
        TestUtil.setupHttp(app)
        HttpUtils.exceptionUrlString = "${wireMockRule.baseUrl()}/rgstr_e"

        stubFor(
            post(urlMatching("/initialize"))
                .willReturn(aResponse().withStatus(202))
        )

        stubFor(
            post(urlMatching("/log_event"))
                .willReturn(aResponse().withStatus(202))
        )

        stubFor(
            post(urlMatching("/rgstr_e"))
                .willReturn(aResponse().withStatus(202))
        )

        options = StatsigOptions(api = wireMockRule.baseUrl())
        fallbackResolver =
            NetworkFallbackResolver(
                keyValueStorage,
                coroutineScope,
                gson = gson
            )
        val store = spyk<Store>(
            Store(
                coroutineScope,
                keyValueStorage,
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
                StatsigNetworkConnectivityListener(app),
                "client-key",
                TestUtil.getTestKeyValueStore(app),
                options,
                networkResolver = fallbackResolver,
                coroutineScope,
                store,
                gson = gson
            )
    }

    @After
    fun teardown() {
        HttpUtils.exceptionUrlString = "https://prodregistryv2.org/v1/rgstr_e"
        TestUtil.reset()
    }

    @Test
    fun initialize_includesStableIDinHeader() = runTest {
        metadata.overrideStableID(overrideID)

        makeInitializeRequest()

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/initialize")
            ).withHeader(STATSIG_STABLE_ID_HEADER_KEY, equalTo(overrideID))
        )
    }

    @Test
    fun initialize_declaresAcceptEncoding() = runTest {
        makeInitializeRequest()

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/initialize")
            ).withHeader("Accept-Encoding", equalTo(HttpUtils.ENCODING_GZIP))
        )
    }

    @Test
    fun logEvent_gzipsRequestBody() = runTest {
        val event = LogEvent("eventName")
        makeLogEventRequest(listOf(event))

        wireMockRule.verify(
            postRequestedFor(
                urlMatching("/log_event")
            ).withHeader(HttpUtils.CONTENT_ENCODING_HEADER_KEY, equalTo(HttpUtils.ENCODING_GZIP))
        )
    }

    @Test
    fun retryFailedLogs_reportsDiagnosticEvent_whenRetryBudgetExceeded() = runTest {
        val savedLogs = StatsigPendingRequests(
            listOf(
                StatsigOfflineRequest(
                    timestamp = System.currentTimeMillis(),
                    requestBody = "{\"events\":[]}",
                    retryCount = 2,
                    eventCount = "7"
                )
            )
        )
        keyValueStorage.writeValue(
            "offlinelogs",
            "StatsigNetwork.OFFLINE_LOGS:client-key",
            gson.toJson(savedLogs)
        )

        val httpDispatcher = HttpUtils.getHttpClient().dispatcher
        val idleLatch = CountDownLatch(1)
        httpDispatcher.idleCallback = Runnable { idleLatch.countDown() }
        try {
            network.apiRetryFailedLogs(
                api = "://invalid-url",
                fallbackUrls = null,
                statsigMetadata = metadata
            )
            if (!idleLatch.await(2, TimeUnit.SECONDS)) {
                throw AssertionError("Timed out waiting for exception logging request to complete")
            }
        } finally {
            httpDispatcher.idleCallback = null
        }

        wireMockRule.verify(
            postRequestedFor(urlMatching("/rgstr_e"))
                .withHeader(HttpUtils.STATSIG_EVENT_COUNT, equalTo("7"))
                .withRequestBody(matchingJsonPath("$.tag", equalTo(LOG_EVENT_FAILED)))
                .withRequestBody(matchingJsonPath("$.eventCount", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.offlineRetries", equalTo("2")))
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
                500,
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
                fallbackUrls = null,
                statsigMetadata = metadata
            )
        } catch (e: Exception) {
            throw e
        }
    }
}
