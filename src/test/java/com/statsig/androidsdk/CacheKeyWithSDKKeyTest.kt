package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CacheKeyWithSDKKeyTest {
    private lateinit var app: Application
    private lateinit var testKeyValueStorage: KeyValueStorage<String>

    private var user = StatsigUser("testUser")

    @Before
    internal fun setup() = runBlocking {
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        user.customIDs = mapOf("companyId" to "123")
        TestUtil.mockHashing()
        // Write a cached by original key
        testKeyValueStorage = TestUtil.getTestKeyValueStore(app)
        testKeyValueStorage.writeValue(
            "ondiskvaluecache",
            "Statsig.CACHE_BY_USER",
            StatsigUtil.getOrBuildGson().toJson(emptyMap<String, Any>())
        )
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockBrokenNetwork())

        return@runBlocking
    }

    @After
    internal fun teardown() {
        TestUtil.reset()
    }

    @Test
    fun testWriteToCacheWithNewKey() = runBlocking {
        Statsig.client.shutdown()
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockNetwork())
        Statsig.client.getStore().awaitPendingSave()
        val gson = StatsigUtil.getOrBuildGson()
        val scopedCacheKey = "${user.getCacheKey()}:client-apikey"
        val fullCacheKey = "${user.toHashString(gson = gson)}:client-apikey"

        val cacheMapping =
            testKeyValueStorage.readValue("ondiskvaluecache", "Statsig.CACHE_KEY_MAPPING")
        val mapping = gson.fromJson(cacheMapping, Map::class.java)
        val mappingEntry = mapping[scopedCacheKey] as Map<*, *>
        assertThat(mappingEntry["fullUserCacheKey"]).isEqualTo(fullCacheKey)
        assertThat(mappingEntry["lastUsedAt"]).isNotNull()

        val perUserCache = testKeyValueStorage.readValue(
            TestUtil.getPerUserCacheStoreName(fullCacheKey),
            TestUtil.getPerUserCacheStorageKey(fullCacheKey)
        )
        assertThat(perUserCache).isNotNull()
    }
}
