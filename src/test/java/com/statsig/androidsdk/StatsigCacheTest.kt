package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StatsigCacheTest {

    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var testSharedPrefs: TestSharedPreferences
    private val gson = Gson()

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)

        mockkObject(Hashing)
        mockkObject(StatsigUtil)
        every {
            Hashing.getHashedString(any(), null)
        } answers {
            firstArg<String>() + "!"
        }
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
        assertEquals(EvaluationReason.Cache, client.getStore().checkGate("always_on").details.reason)

        assertTrue(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs("https://statsigapi.net/v1")
            client.statsigNetwork.addFailedLogRequest("{}")
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
        TestUtil.mockServer()
        client = Statsig.client
        assertFalse(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs("https://statsigapi.net/v1")
            client.statsigNetwork.addFailedLogRequest("{}")
        }
        val config = client.getConfig("test_config")
        assertEquals("fallback", config.getString("string", "fallback"))
        assertEquals(EvaluationReason.Uninitialized, config.getEvaluationDetails().reason)

        runBlocking {
            Statsig.client.initialize(app, "client-test", user, StatsigOptions(loadCacheAsync = true))
        }
        assertTrue(client.checkGate("always_on"))

        val netConfig = client.getConfig("test_config")
        assertEquals("test", netConfig.getString("string", "fallback"))
        assertEquals(EvaluationReason.Network, netConfig.getEvaluationDetails().reason)

        assertTrue(Statsig.client.isInitialized())
    }
}
