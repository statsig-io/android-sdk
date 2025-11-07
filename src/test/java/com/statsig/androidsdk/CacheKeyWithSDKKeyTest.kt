package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CacheKeyWithSDKKeyTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: SharedPreferences

    private var user = StatsigUser("testUser")

    @Before
    internal fun setup() = runBlocking {
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        user.customIDs = mapOf("companyId" to "123")
        TestUtil.mockHashing()
        var values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        values["values"] = TestUtil.makeInitializeResponse()
        values["stickyUserExperiments"] = sticky
        var cacheById: MutableMap<String, Any> = HashMap()
        // Write a cached by original key
        testSharedPrefs = TestUtil.getTestSharedPrefs(app)
        testSharedPrefs.edit().putString(
            "Statsig.CACHE_BY_USER",
            StatsigUtil.buildGson().toJson(cacheById)
        ).apply()
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockBrokenNetwork())

        return@runBlocking
    }

    @Test
    fun testWriteToCacheWithNewKey() = runBlocking {
        Statsig.client.shutdown()
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockNetwork())
        val cacheById = StatsigUtil.buildGson().fromJson(
            StatsigUtil.getFromSharedPrefs(testSharedPrefs, "Statsig.CACHE_BY_USER"),
            Map::class.java
        )
        assertThat(
            cacheById.keys
        ).contains("${user.toHashString(gson = StatsigUtil.buildGson())}:client-apikey")
    }
}
