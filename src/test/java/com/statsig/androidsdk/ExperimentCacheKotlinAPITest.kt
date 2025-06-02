package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExperimentCacheKotlinAPITest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private lateinit var network: StatsigNetwork
    private var initializeCalls: Int = 0
    private lateinit var client: StatsigClient

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        app = mockk()
        testSharedPrefs = TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtil()

        // Setup network mock that returns different responses for first/second session
        network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val res = getResponseForUser(secondArg(), if (initializeCalls < 2) "a_config" else "b_config")
            initializeCalls++
            res
        }

        client = spyk()
        client.statsigNetwork = network
    }

    private fun StatsigUser.getTestCustomCacheKey(sdkKey: String): String {
        var id = userID ?: STATSIG_NULL_USER
        val ids = (customIDs ?: mapOf()).filter { it.key != "companyId" }

        for ((k, v) in ids) {
            id = "$id$k:$v"
        }
        return "$id:$sdkKey"
    }

    @Test
    fun testExperimentCacheAfterRestart() {
        val initializationOptions = StatsigOptions(
            disableHashing = true,
            customCacheKey = { sdkKey, user -> user.getTestCustomCacheKey(sdkKey) }
        )
        val loggedOutUser = StatsigUser("")
        // Test user setup
        val loggedInUser = StatsigUser("test-user-id").apply {
            customIDs = mapOf(
                "deviceId" to "test-device-id",
                "companyId" to "test-company-id"
            )
        }

        // First app session - start initialize without waiting
        val initializeDeferred = GlobalScope.async {
            client.initialize(app, "client-key", loggedOutUser, initializationOptions)
        }

        // Wait for network response to complete
        runBlocking {
            initializeDeferred.await()
        }

        // Verify experiment works for logged out user
        var experiment = client.getExperiment("a_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("logged_out_value", experiment.getString("key", "default"))

        // Start update without waiting
        val updateDeferred = GlobalScope.async {
            client.updateUser(loggedInUser)
        }

        // Wait for update to complete
        runBlocking {
            updateDeferred.await()
        }

        // Verify experiment works for logged in user
        experiment = client.getExperiment("a_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))
        // should be written to cache now

        // Shutdown SDK and create new client for second session
        runBlocking {
            client.shutdown()
        }
        client = spyk()
        client.statsigNetwork = network
        TestUtil.mockDispatchers()

        // Initialize SDK for second session - start without waiting
        val secondInitializeDeferred = GlobalScope.async {
            client.initialize(app, "client-key", loggedInUser, initializationOptions)
        }

        // Check experiment immediately after init (before network response) for cached value
        experiment = client.getExperiment("a_config")
        assertEquals(EvaluationReason.Cache, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Now wait for initialize to complete
        runBlocking {
            secondInitializeDeferred.await()
        }

        // Verify new experiment from network response
        experiment = client.getExperiment("b_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Verify old experiment is no longer available
        experiment = client.getExperiment("a_config")
        assertEquals(EvaluationReason.Unrecognized, experiment.getEvaluationDetails().reason)
        assertEquals("default", experiment.getString("key", "default"))
    }
} 