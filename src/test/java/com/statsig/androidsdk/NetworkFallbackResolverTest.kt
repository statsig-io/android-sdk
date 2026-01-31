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

        val DEFAULT_INIT_URL_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API
        )
        val USER_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API,
            listOf("fallback.example.com")
        )
        val CUSTOM_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            "custom.api.com"
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

    @Test
    fun getsFallbackInfo() = runTest {
        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": ${System.currentTimeMillis() + 999999}
                }
            }
        """.trimIndent()
        testKeyValueStorage.writeValue("networkfallback", STORAGE_KEY, json)

        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(DEFAULT_INIT_URL_CONFIG)
        assertTrue("gets the cached url", activeUrl == "fallback.example.com")
    }

    @Test
    fun wipesFallbackInfoWhenExpired() = runBlocking {
        val expiryTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds

        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": $expiryTime
                }
            }
        """.trimIndent()
        testKeyValueStorage.writeValue("networkfallback", STORAGE_KEY, json)
        assertThat(testKeyValueStorage.readAll("networkfallback")).isNotEmpty()

        // Make sure resolver has info in-memory
        resolver.initializeFallbackInfo()

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
        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": ${System.currentTimeMillis() + 99999}
                }
            }
        """.trimIndent()
        testKeyValueStorage.writeValue("networkfallback", STORAGE_KEY, json)

        resolver.initializeFallbackInfo()
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
    fun useUserFallbackIfProvided() = runTest {
        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        assertNull("no active url should be returned for user fallback", activeUrl)
        resolver.tryFetchUpdatedFallbackInfo(
            USER_FALLBACK_CONFIG,
            "NetworkError when attempting to fetch resource",
            false,
            true
        )

        val cache = resolver.readFallbackInfoFromCache()
        assertTrue("cache should not be empty", cache != null)
        assertTrue(
            "cache should contain user fallback",
            cache?.get(Endpoint.Initialize)?.url == "fallback.example.com"
        )
    }

    @Test
    fun doNotFetchFallbackForCustomUrl() = runTest {
        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        assertNull("no active url should be returned for custom url", activeUrl)
        resolver.tryFetchUpdatedFallbackInfo(
            CUSTOM_FALLBACK_CONFIG,
            "NetworkError when attempting to fetch resource",
            false,
            true
        )

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
