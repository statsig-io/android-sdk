import android.app.Application
import android.content.SharedPreferences
import com.statsig.androidsdk.DEFAULT_INIT_API
import com.statsig.androidsdk.Endpoint
import com.statsig.androidsdk.ErrorBoundary
import com.statsig.androidsdk.NetworkFallbackResolver
import com.statsig.androidsdk.TestUtil
import com.statsig.androidsdk.UrlConfig
import com.statsig.androidsdk.isDomainFailure
import io.mockk.MockKAnnotations
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NetworkFallbackResolverTest {

    private lateinit var testSharedPrefs: SharedPreferences
    private lateinit var resolver: NetworkFallbackResolver
    private var app: Application = RuntimeEnvironment.getApplication()

    companion object {
        const val SDK_KEY = "client-test-sdk-key"
        val STORAGE_KEY = "statsig.network_fallback"
        const val SIX_DAYS = 6 * 24 * 60 * 60 * 1000L

        val DEFAULT_INIT_URL_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API,
        )
        val USER_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            DEFAULT_INIT_API,
            listOf("fallback.example.com"),
        )
        val CUSTOM_FALLBACK_CONFIG = UrlConfig(
            Endpoint.Initialize,
            "custom.api.com",
        )
    }

    @Before
    internal fun setup() = runBlocking {
        MockKAnnotations.init(this)
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)
        testSharedPrefs.edit().clear().commit()
        TestUtil.mockHashing()
        resolver = NetworkFallbackResolver(ErrorBoundary(), testSharedPrefs, TestUtil.coroutineScope)
    }

    @Test
    fun getsFallbackInfo() {
        val editor = testSharedPrefs.edit()
        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": ${System.currentTimeMillis() + 999999}
                }
            }
        """.trimIndent()
        editor.putString(STORAGE_KEY, json)
        editor.apply()

        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(DEFAULT_INIT_URL_CONFIG)
        assertTrue("gets the cached url", activeUrl == "fallback.example.com")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun wipesFallbackInfoWhenExpired() {
        val editor = testSharedPrefs.edit()
        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": ${System.currentTimeMillis() - 99999}
                }
            }
        """.trimIndent()
        editor.putString(STORAGE_KEY, json)
        editor.apply()

        val result = resolver.getActiveFallbackUrlFromMemory(DEFAULT_INIT_URL_CONFIG)
        TestUtil.coroutineScope.advanceUntilIdle()
        val cache = resolver.readFallbackInfoFromCache()

        assertNull(result)
        assertNull(cache)
    }

    @Test
    fun bumpsExpiryTimeWhenUrlIsSuccessfullyUsed() = runTest {
        val editor = testSharedPrefs.edit()
        val json = """
            {
                "initialize": {
                    "url": "fallback.example.com",
                    "previous": [],
                    "expiryTime": ${System.currentTimeMillis() + 99999}
                }
            }
        """.trimIndent()
        editor.putString(STORAGE_KEY, json)
        editor.commit()

        resolver.initializeFallbackInfo()
        resolver.tryBumpExpiryTime(SDK_KEY, DEFAULT_INIT_URL_CONFIG)

        val cache = resolver.readFallbackInfoFromCache()
        assertTrue("cache should not be empty", cache != null)
        val expiryTime = cache?.get(Endpoint.Initialize)?.expiryTime ?: 0L
        assertTrue("Expiry time should be bumped", expiryTime > (System.currentTimeMillis() + SIX_DAYS))
    }

    @Test
    fun useUserFallbackIfProvided() = runTest {
        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        assertNull("no active url should be returned for user fallback", activeUrl)
        resolver.tryFetchUpdatedFallbackInfo(SDK_KEY, USER_FALLBACK_CONFIG, "NetworkError when attempting to fetch resource", false, true)

        val cache = resolver.readFallbackInfoFromCache()
        assertTrue("cache should not be empty", cache != null)
        assertTrue("cache should contain user fallback", cache?.get(Endpoint.Initialize)?.url == "fallback.example.com")
    }

    @Test
    fun doNotFetchFallbackForCustomUrl() = runTest {
        resolver.initializeFallbackInfo()
        val activeUrl = resolver.getActiveFallbackUrlFromMemory(USER_FALLBACK_CONFIG)
        assertNull("no active url should be returned for custom url", activeUrl)
        resolver.tryFetchUpdatedFallbackInfo(SDK_KEY, CUSTOM_FALLBACK_CONFIG, "NetworkError when attempting to fetch resource", false, true)

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
