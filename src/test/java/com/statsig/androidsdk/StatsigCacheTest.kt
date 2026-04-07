package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StatsigCacheTest {

    private lateinit var app: Application
    private lateinit var client: StatsigClient
    private lateinit var testKeyValueStorage: KeyValueStorage<String>
    private val gson = Gson()

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = RuntimeEnvironment.getApplication()
        testKeyValueStorage = TestUtil.getTestKeyValueStore(app)
        TestUtil.mockHashing()
    }

    @After
    internal fun tearDown() {
        TestUtil.reset()
    }

    @Test
    fun testInitializeUsesCacheBeforeNetworkResponse() {
        val user = StatsigUser("123")

        var cacheByUser: MutableMap<String, Any> = HashMap()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        var initialize = TestUtil.makeInitializeResponse()
        values.put("values", initialize)
        values.put("stickyUserExperiments", sticky)
        cacheByUser.put("${user.getCacheKey()}:client-test", values)

        runBlocking {
            testKeyValueStorage.writeValue(
                "ondiskvaluecache",
                "Statsig.CACHE_BY_USER",
                gson.toJson(cacheByUser)
            )
        }

        TestUtil.startStatsigAndDontWait(app, user, StatsigOptions())
        client = Statsig.client
        assertTrue(client.isInitialized())
        assertEvalDetails(
            client.getStore().checkGate("always_on").getEvalDetails(),
            EvalSource.Cache,
            EvalReason.Recognized
        )

        assertTrue(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs(
                "https://statsigapi.net/v1",
                null,
                StatsigMetadata()
            )
        }
        val config = client.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEvalDetails(config.getEvalDetails(), EvalSource.Cache, EvalReason.Recognized)
    }

    @Test
    fun testSetupDoesntLoadFromCacheWhenSetToAsync() {
        val user = StatsigUser("123")

        var cacheByUser: MutableMap<String, Any> = HashMap()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        var initialize = TestUtil.makeInitializeResponse()
        values.put("values", initialize)
        values.put("stickyUserExperiments", sticky)
        cacheByUser.put("${user.getCacheKey()}:client-test", values)

        runBlocking {
            testKeyValueStorage.writeValue(
                "ondiskvaluecache",
                "Statsig.CACHE_BY_USER",
                gson.toJson(cacheByUser)
            )
        }

        TestUtil.startStatsigAndDontWait(app, user, StatsigOptions(loadCacheAsync = true))
        client = Statsig.client
        client.statsigNetwork = TestUtil.mockNetwork()
        assertFalse(client.checkGate("always_on"))
        runBlocking {
            client.statsigNetwork.apiRetryFailedLogs(
                "https://statsigapi.net/v1",
                null,
                StatsigMetadata()
            )
            client.statsigNetwork.addFailedLogRequest(
                StatsigOfflineRequest(System.currentTimeMillis(), "{}", 0)
            )
        }
        val config = client.getConfig("test_config")
        assertEquals("fallback", config.getString("string", "fallback"))
        assertEvalDetails(
            config.getEvalDetails(),
            EvalSource.Uninitialized,
            EvalReason.Unrecognized
        )
        runBlocking {
            Statsig.client.shutdown()
            Statsig.client.initialize(
                app,
                "client-test",
                user,
                StatsigOptions(loadCacheAsync = true)
            )
        }
        assertTrue(client.checkGate("always_on"))

        val netConfig = client.getConfig("test_config")
        assertEquals("test", netConfig.getString("string", "fallback"))
        assertEvalDetails(netConfig.getEvalDetails(), EvalSource.Network, EvalReason.Recognized)

        assertTrue(Statsig.client.isInitialized())
    }
}
