package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigCacheTest {

    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var testSharedPrefs: SharedPreferences
    private val gson = Gson()

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)
        TestUtil.mockHashing()
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testInitializeUsesCacheBeforeNetworkResponse() {
        val user = StatsigUser("123")

        var cacheById: MutableMap<String, Any> = HashMap()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        var initialize = TestUtil.makeInitializeResponse()
        values.put("values", initialize)
        values.put("stickyUserExperiments", sticky)
        cacheById.put("123", values)

        testSharedPrefs.edit().putString("Statsig.CACHE_BY_USER", gson.toJson(cacheById)).apply()

        TestUtil.startStatsigAndDontWait(app, user, StatsigOptions())
        client = Statsig.client
        assertTrue(client.isInitialized())
        assertEquals(EvaluationReason.Cache, client.getStore().checkGate("always_on").getEvaluationDetails().reason)

        assertTrue(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs("https://statsigapi.net/v1")
        }
        val config = client.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals(EvaluationReason.Cache, config.getEvaluationDetails().reason)
    }

    @Test
    fun testSetupDoesntLoadFromCacheWhenSetToAsync() {
        val user = StatsigUser("123")

        var cacheById: MutableMap<String, Any> = HashMap()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        var initialize = TestUtil.makeInitializeResponse()
        values.put("values", initialize)
        values.put("stickyUserExperiments", sticky)
        cacheById.put("123", values)

        testSharedPrefs.edit().putString("Statsig.CACHE_BY_USER", gson.toJson(cacheById)).apply()

        TestUtil.startStatsigAndDontWait(app, user, StatsigOptions(loadCacheAsync = true))
        client = Statsig.client
        client.statsigNetwork = TestUtil.mockNetwork()
        assertFalse(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs("https://statsigapi.net/v1")
            client.statsigNetwork.addFailedLogRequest(
                StatsigOfflineRequest(System.currentTimeMillis(), "{}", 0),
            )
        }
        val config = client.getConfig("test_config")
        assertEquals("fallback", config.getString("string", "fallback"))
        assertEquals(EvaluationReason.Uninitialized, config.getEvaluationDetails().reason)
        runBlocking {
            Statsig.client.shutdown()
            Statsig.client.initialize(app, "client-test", user, StatsigOptions(loadCacheAsync = true))
        }
        assertTrue(client.checkGate("always_on"))

        val netConfig = client.getConfig("test_config")
        assertEquals("test", netConfig.getString("string", "fallback"))
        assertEquals(EvaluationReason.Network, netConfig.getEvaluationDetails().reason)

        assertTrue(Statsig.client.isInitialized())
    }
}
