package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class CacheKeyWithSDKKeyTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences

    private var user = StatsigUser("testUser")

    @Before
    internal fun setup() = runBlocking {
        TestUtil.mockDispatchers()
        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)
        user.customIDs = mapOf("companyId" to "123")
        TestUtil.mockStatsigUtil()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        values["values"] = TestUtil.makeInitializeResponse()
        values["stickyUserExperiments"] = sticky
        var cacheById: MutableMap<String, Any> = HashMap()
        cacheById[user.getCacheKeyDEPRECATED()] = values
        // Mock cached by original key
        coEvery {
            StatsigUtil.syncGetFromSharedPrefs(any(), cmpEq("Statsig.CACHE_BY_USER"))
        } coAnswers {
            StatsigUtil.getGson().toJson(cacheById)
        }
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockBrokenNetwork())
        return@runBlocking
    }

    @Test
    fun testLoadFromPreviousCacheKey() {
        assert(Statsig.client.checkGate("always_on"))
        val config = Statsig.client.getConfig("test_config")
        assert(config.getEvaluationDetails().reason == EvaluationReason.Cache)
        assert(config.getString("string", "DEFAULT") == "test")
    }

    @Test
    fun testWriteToCacheWithNewKey() = runBlocking {
        Statsig.client.shutdown()
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockNetwork())
        val cacheById = StatsigUtil.getGson().fromJson(StatsigUtil.getFromSharedPrefs(testSharedPrefs, "Statsig.CACHE_BY_USER"), Map::class.java)
        assert(cacheById.keys.contains(StatsigOptions().customCacheKey("client-apikey", user)))
    }
}
