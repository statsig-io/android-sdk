import com.google.common.truth.Truth.assertThat
import com.statsig.androidsdk.DEFAULT_INIT_API
import com.statsig.androidsdk.Endpoint
import com.statsig.androidsdk.InMemoryKeyValueStorage
import com.statsig.androidsdk.KeyValueStorage
import com.statsig.androidsdk.NetworkFallbackResolver
import com.statsig.androidsdk.StatsigUtil
import com.statsig.androidsdk.TestUtil
import com.statsig.androidsdk.UrlConfig
import com.statsig.androidsdk.isDomainFailure
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkFallbackResolverTest {
    private lateinit var testKeyValueStorage: KeyValueStorage<String>
    private lateinit var resolver: NetworkFallbackResolver
    private lateinit var dispatcher: TestDispatcher
    private lateinit var coroutineScope: TestScope

    companion object {
        const val SDK_KEY = "client-test-sdk-key"
        val STORAGE_KEY = "statsig.network_fallback"
        const val SIX_DAYS = 6 * 24 * 60 * 60 * 1000L
        const val ONE_DAY = 24 * 60 * 60 * 1000L
        const val NETWORK_ERROR = "NetworkError when attempting to fetch resource"

        const val FALLBACK_URL = "https://fallback.example.com/v1/initialize"
        const val CUSTOM_API = "https://custom.api.com/v1"

        val DEFAULT_INIT_URL_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API
        )
        val USER_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API,
            listOf(FALLBACK_URL)
        )
        val CUSTOM_API_CONFIG = UrlConfig(
            Endpoint.Initialize,
            CUSTOM_API
        )
        val CUSTOM_API_WITH_USER_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            CUSTOM_API,
            listOf(FALLBACK_URL)
        )
    }

    @Before
    internal fun setup() {
        TestUtil.mockHashing()
        dispatcher = TestUtil.mockDispatchers(UnconfinedTestDispatcher())
        coroutineScope = TestScope(dispatcher)
        testKeyValueStorage = InMemoryKeyValueStorage()
        resolver =
            NetworkFallbackResolver(
                testKeyValueStorage,
                coroutineScope,
                gson = StatsigUtil.getOrBuildGson()
            )
    }

    @After
    internal fun teardown() {
        TestUtil.reset()
    }

    private suspend fun cacheFallbackUrl(
        url: String,
        expiryTime: Long = System.currentTimeMillis() + ONE_DAY
    ) {
        val json = """
            {
                "initialize": {
                    "url": "$url",
                    "previous": [],
                    "expiryTime": $expiryTime
                }
            }
        """.trimIndent()
        testKeyValueStorage.writeValue("networkfallback", STORAGE_KEY, json)
        resolver.initializeFallbackInfo()
    }

    @Test
    fun getsFallbackInfo() = runTest {
        cacheFallbackUrl(FALLBACK_URL)

        val activeUrl = resolver.getActiveFallbackUrlFromMemory(DEFAULT_INIT_URL_CONFIG)
        assertTrue("gets the cached url", activeUrl == FALLBACK_URL)
    }

    @Test
    fun wipesFallbackInfoWhenExpired() = runBlocking {
        cacheFallbackUrl(
            FALLBACK_URL,
            expiryTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
        )
        assertThat(testKeyValueStorage.readAll("networkfallback")).isNotEmpty()

        // Get expired info, expect null and a write to disk
        val result = resolver.getActiveFallbackUrlFromMemory(DEFAULT_INIT_URL_CONFIG)

        assertThat(result).isNull()
        repeat(25) {
            if (resolver.readFallbackInfoFromCache() == null) {
                return@runBlocking
            }
            delay(10)
        }
        assertThat(resolver.readFallbackInfoFromCache()).isNull()
    }

    @Test
    fun bumpsExpiryTimeWhenUrlIsSuccessfullyUsed() = runTest {
        cacheFallbackUrl(FALLBACK_URL)

        resolver.tryBumpExpiryTime(DEFAULT_INIT_URL_CONFIG)

        val cache = resolver.readFallbackInfoFromCache()
        assertTrue("cache should not be empty", cache != null)
        val expiryTime = cache?.get(Endpoint.Initialize)?.expiryTime ?: 0L
        assertTrue(
            "Expiry time should be bumped",
            expiryTime > (System.currentTimeMillis() + SIX_DAYS)
        )
    }

    @Test
    fun servesUserFallbackAfterDomainFailure() = runTest {
        resolver.initializeFallbackInfo()
        assertNull(
            "no fallback is active before a failure",
            resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        )

        resolver.tryFetchUpdatedFallbackInfo(USER_FALLBACK_CONFIG, NETWORK_ERROR, false, true)

        assertThat(resolver.readFallbackInfoFromCache()?.get(Endpoint.Initialize)?.url)
            .isEqualTo(FALLBACK_URL)
        assertThat(resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG))
            .isEqualTo(FALLBACK_URL)
    }

    @Test
    fun servesUserFallbackAfterDomainFailureWithCustomApi() = runTest {
        resolver.initializeFallbackInfo()
        resolver.tryFetchUpdatedFallbackInfo(
            CUSTOM_API_WITH_USER_FALLBACK_CONFIG,
            NETWORK_ERROR,
            false,
            true
        )

        assertThat(
            resolver.getActiveFallbackUrlFromMemory(CUSTOM_API_WITH_USER_FALLBACK_CONFIG)
        ).isEqualTo(FALLBACK_URL)
    }

    @Test
    fun doesNotServeCachedFallbackForCustomApiWithoutUserFallbacks() = runTest {
        cacheFallbackUrl(FALLBACK_URL)

        assertNull(
            "a custom api opts out of fallbacks entirely",
            resolver.getActiveFallbackUrlFromMemory(CUSTOM_API_CONFIG)
        )
    }

    @Test
    fun doesNotServeCachedFallbackMissingFromUserFallbacks() = runTest {
        cacheFallbackUrl("https://stale.example.com/v1/initialize")

        assertNull(
            "a url the app no longer lists is not served",
            resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        )
    }

    @Test
    fun doNotFetchFallbackForCustomUrl() = runTest {
        resolver.initializeFallbackInfo()
        resolver.tryFetchUpdatedFallbackInfo(CUSTOM_API_CONFIG, NETWORK_ERROR, false, true)

        val cache = resolver.readFallbackInfoFromCache()
        assertTrue("cache should be empty", cache == null)
    }

    @Test
    fun handlesTimeouts() {
        assertTrue(isDomainFailure(null, true, true))
    }

    @Test
    fun handlesNetworkErrors() {
        assertTrue(isDomainFailure("NetworkError when attempting to fetch resource", false, true))
    }

    @Test
    fun handlesOtherErrors() {
        assertTrue(isDomainFailure("Unknown Error", false, true))
    }

    @Test
    fun rejectsWhenNoNetwork() {
        assertFalse(isDomainFailure("NetworkError when attempting to fetch resource", false, false))
    }
}
