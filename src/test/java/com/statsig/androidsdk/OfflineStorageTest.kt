package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OfflineStorageTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var testKeyValueStorage: KeyValueStorage<String>
    private val gson = StatsigUtil.getOrBuildGson()
    private lateinit var network: StatsigNetwork
    private val now = System.currentTimeMillis()
    private val threeDaysInMs = 3 * 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        testKeyValueStorage = TestUtil.getTestKeyValueStore(app)

        network = StatsigNetwork(
            app,
            "client-key",
            testKeyValueStorage,
            StatsigOptions(),
            mockk(),
            mockk(),
            mockk(),
            gson = gson
        )
    }

    @Test
    fun `getSavedLogs prunes logs older than 3 days`() = runBlocking {
        // Create 3 old and 3 recent logs
        val logs = listOf(
            StatsigOfflineRequest(now - threeDaysInMs - 100_000, "too old 1"),
            StatsigOfflineRequest(now - threeDaysInMs - 200_000, "too old 2"),
            StatsigOfflineRequest(now - threeDaysInMs - 300_000, "too old 3"),
            StatsigOfflineRequest(now - 3_000, "recent 1"),
            StatsigOfflineRequest(now - 2_000, "recent 2"),
            StatsigOfflineRequest(now - 1_000, "recent 3")
        )

        val json = gson.toJson(StatsigPendingRequests(logs))
        testKeyValueStorage.writeValue(
            "offlinelogs",
            "StatsigNetwork.OFFLINE_LOGS:client-key",
            json
        )

        val result = network.getSavedLogs()

        assertEquals(3, result.size)
        assertEquals("recent 1", result[0].requestBody)
        assertEquals("recent 2", result[1].requestBody)
        assertEquals("recent 3", result[2].requestBody)
    }

    @Test
    fun `filterValidLogs keeps only the most recent up to max cache`() {
        val now = System.currentTimeMillis()

        // Create 11 logs, timestamps increasing by 1 second (oldest first)
        val logs = (0..20).map { i ->
            StatsigOfflineRequest(
                timestamp = now - (11 - i) * 1_000L,
                requestBody = "log $i",
                retryCount = 0
            )
        }

        val filtered = network.filterValidLogs(logs, now)

        // It should keep only the last 10 logs (log 1 to log 10)
        assertEquals(10, filtered.size)
        val expectedLogs = (11..20).map { "log $it" }
        assertEquals(expectedLogs, filtered.map { it.requestBody })
    }

    @Test
    fun `getSavedLogs prunes logs exceeding max count of 10`() = runBlocking {
        // Create 11 logs within valid time window
        val logs = (0..10).map { i ->
            StatsigOfflineRequest(now - (11 - i) * 1_000L, "log $i")
        }

        val json = gson.toJson(StatsigPendingRequests(logs))
        testKeyValueStorage.writeValue(
            "offlinelogs",
            "StatsigNetwork.OFFLINE_LOGS:client-key",
            json
        )

        val result = network.getSavedLogs()

        // Should retain only the last 10 logs (log 1..10)
        assertThat(result).hasSize(10)
        (1..10).forEach { i ->
            assertEquals("log $i", result[i - 1].requestBody)
        }
        // Add one more log and check again
        network.addFailedLogRequest(StatsigOfflineRequest(now, "log 11"))
        val savedJson = testKeyValueStorage.readValue(
            "offlinelogs",
            "StatsigNetwork.OFFLINE_LOGS:client-key"
        )
        val saved = gson.fromJson(savedJson, StatsigPendingRequests::class.java)

        // See MAX_LOG_REQUESTS_TO_CACHE
        assertThat(saved.requests).hasSize(10)
        // Should drop "log 1", and now include "log 2" to "log 11"
        (2..11).forEachIndexed { index, i ->
            assertEquals("log $i", saved.requests[index].requestBody)
        }
    }
}
