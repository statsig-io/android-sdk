package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal suspend fun getResponseForUser(user: StatsigUser): InitializeResponse {
    return withContext(Dispatchers.IO) {
        val isUserA = user.userID == "user-a"
        delay(if (isUserA) 500 else 2000)
        TestUtil.makeInitializeResponse(
            mapOf(),
            mapOf(
                "a_config!" to APIDynamicConfig(
                    "a_config!",
                    mutableMapOf(
                        "key" to if (isUserA) "user_a_value" else "user_b_value",
                    ),
                    "default",
                ),
            ),
            mapOf(),
            if (isUserA) 1748624742068 else 1748624742069,
            true,
        )
    }
}

class AsyncInitVsUpdateTest {
    lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private val gson = Gson()
    private lateinit var client: StatsigClient
    private lateinit var network: StatsigNetwork

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtil()

        network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val user = secondArg<StatsigUser>()
            val isUserA = user.userID == "user-a"
            val configs = mapOf(
                "a_config!" to APIDynamicConfig(
                    "a_config!",
                    mutableMapOf(
                        "key" to if (isUserA) "user_a_value" else "user_b_value",
                    ),
                    "default",
                ),
            )
            TestUtil.makeInitializeResponse(
                dynamicConfigs = configs,
                hasUpdates = true,
                hashUsed = HashAlgorithm.DJB2
            )
        }
        client = spyk()
        client.statsigNetwork = network
    }

    @After
    fun tearDown() {
        runBlocking {
            client.shutdownSuspend()
        }
        TestUtil.clearMockDispatchers()
    }

    @Test
    fun testNoCache() {
        val userA = StatsigUser("user-a")
        userA.customIDs = mapOf("workID" to "employee-a")

        val userB = StatsigUser("user-b")
        userB.customIDs = mapOf("workID" to "employee-b")

        val didInitializeUserA = CountDownLatch(1)
        val didInitializeUserB = CountDownLatch(1)

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeUserA.countDown()
            }

            override fun onStatsigUpdateUser() {
                didInitializeUserB.countDown()
            }
        }

        client.initializeAsync(app, "client-key", userA, callback)
        client.updateUserAsync(userB, callback)

        // Since updateUserAsync has been called, we void values for user_a
        var config = client.getConfig("a_config")
        var value = config.getString("key", "default")
        assertEquals("default", value)
        assertEquals(EvaluationReason.Uninitialized, config.getEvaluationDetails().reason)

        didInitializeUserA.await(3, TimeUnit.SECONDS)
        config = client.getConfig("a_config")
        value = config.getString("key", "default")
        assertEquals("default", value)
        assertEquals(EvaluationReason.Uninitialized, config.getEvaluationDetails().reason)

        didInitializeUserB.await(5, TimeUnit.SECONDS)

        value = client.getConfig("a_config").getString("key", "default")
        assertEquals("user_b_value", value)
    }

    @Test
    fun testLoadFromCache() {
        val userA = StatsigUser("user-a")
        val userB = StatsigUser("user-b")

        val didInitializeUserA = CountDownLatch(1)
        val didInitializeUserB = CountDownLatch(1)

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeUserA.countDown()
            }

            override fun onStatsigUpdateUser() {
                didInitializeUserB.countDown()
            }
        }

        val cacheById: MutableMap<String, Any> = HashMap()
        val values: MutableMap<String, Any> = HashMap()
        val sticky: MutableMap<String, Any> = HashMap()
        val userBCacheValues = TestUtil.makeInitializeResponse(
            mapOf(),
            mapOf(
                "a_config!" to APIDynamicConfig(
                    "a_config!",
                    mutableMapOf(
                        "key" to "user_b_value_cache",
                    ),
                    "default",
                ),
            ),
            mapOf(),
        )
        values["values"] = userBCacheValues
        values["stickyUserExperiments"] = sticky
        cacheById["user-b"] = values
        testSharedPrefs.edit().putString("Statsig.CACHE_BY_USER", gson.toJson(cacheById))

        client.initializeAsync(app, "client-key", userA, callback)
        client.updateUserAsync(userB, callback)

        // Since updateUserAsync has been called, we void values for user_a and serve cache
        // values for user_b
        var config = client.getConfig("a_config")
        var value = config.getString("key", "default")
        assertEquals("user_b_value_cache", value)
        assertEquals(EvaluationReason.Cache, config.getEvaluationDetails().reason)

        didInitializeUserA.await(2, TimeUnit.SECONDS)
        config = client.getConfig("a_config")
        value = config.getString("key", "default")
        assertEquals("user_b_value_cache", value)
        assertEquals(EvaluationReason.Cache, config.getEvaluationDetails().reason)

        didInitializeUserB.await(3, TimeUnit.SECONDS)

        config = client.getConfig("a_config")
        value = config.getString("key", "default")
        assertEquals("user_b_value", value)
        assertEquals(EvaluationReason.Network, config.getEvaluationDetails().reason)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun testNoAwait() {
        val userA = StatsigUser("user-a")
        val userB = StatsigUser("user-b")

        val didInitializeUserA = CountDownLatch(1)
        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeUserA.countDown()
            }

            override fun onStatsigUpdateUser() {}
        }
        client.initializeAsync(app, "client-key", userA, callback)
        didInitializeUserA.await(1, TimeUnit.SECONDS)
        GlobalScope.async {
            client.updateUser(userB)
        }

        // Calling updateUser without suspending will not guarantee synchronous load from cache
        val config = client.getConfig("a_config")
        val value = config.getString("key", "default")
        assertEquals("user_a_value", value)
        assertEquals(EvaluationReason.Network, config.getEvaluationDetails().reason)
    }

    private fun dumpCacheContents() {
        val cacheByUser = testSharedPrefs.getString("Statsig.CACHE_BY_USER", null)
        if (cacheByUser != null) {
            println("Cache contents:")
            val cacheMap = gson.fromJson(cacheByUser, Map::class.java)
            cacheMap.forEach { (userId, values) ->
                println("User: $userId")
                println("Values: $values")
            }
        } else {
            println("Cache is empty")
        }
    }

    private fun getCacheForUser(userId: String): Map<String, Any>? {
        val cacheByUser = testSharedPrefs.getString("Statsig.CACHE_BY_USER", null)
        if (cacheByUser != null) {
            val cacheMap = gson.fromJson(cacheByUser, Map::class.java) as Map<String, Map<String, Any>>
            return cacheMap[userId]
        }
        return null
    }
}
