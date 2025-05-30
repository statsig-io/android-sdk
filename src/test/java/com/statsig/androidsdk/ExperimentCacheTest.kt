package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal suspend fun getResponseForUser(user: StatsigUser, configName: String): InitializeResponse {
    return withContext(Dispatchers.IO) {
        delay(500)
        TestUtil.makeInitializeResponse(
            mapOf(),
            mapOf(
                "${configName}!" to APIDynamicConfig(
                    "{$configName}!",
                    mutableMapOf(
                        "key" to "value",
                    ),
                    "default",
                ),
            ),
            mapOf(),
        )
    }
}

class ExperimentCacheTest {
    private lateinit var app: Application
    private lateinit var testSharedPrefs: TestSharedPreferences
    private lateinit var network: StatsigNetwork
    private var initializeCalls: Int = 0

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
            println("\n=== Network initialize called ===")
            getResponseForUser(secondArg(), if (initializeCalls === 0) "a_config" else "b_config")
        }

        Statsig.client = spyk()
        Statsig.client.statsigNetwork = network
    }

    @Test
    fun testExperimentCacheAfterRestart() {
        // Test user setup
        val user = StatsigUser("test-user-id").apply {
            customIDs = mapOf(
                "deviceId" to "test-device-id",
                "companyId" to "test-company-id"
            )
        }

        val didInitializeUserA = CountDownLatch(1)

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeUserA.countDown()
            }

            override fun onStatsigUpdateUser() {
            }
        }
        // First app session
        println("\n=== Starting first session ===")
        Statsig.initializeAsync(app, "client-key", user, callback)

        // Wait for network response to complete
        println("Waiting for network response...")
        didInitializeUserA.await(3, TimeUnit.SECONDS)

        // Verify experiment works in first session
        var experiment = Statsig.getExperiment("a_config")
        println("\n=== First Session Verification ===")
        println("Evaluation reason: ${experiment.getEvaluationDetails().reason}")
        println("Value: ${experiment.getString("key", "default")}")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Shutdown SDK
        println("\n=== Shutting down SDK ===")
        Statsig.shutdown()

        // Setup second session
        println("\n=== Starting second session ===")
        val didInitializeUserB = CountDownLatch(1)

        val callbackAgain = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeUserB.countDown()
            }

            override fun onStatsigUpdateUser() {
            }
        }
        // First app session
        println("\n=== Starting first session ===")
        Statsig.initializeAsync(app, "client-key", user, callback)
        TestUtil.mockDispatchers() // Re-mock dispatchers after shutdown
        TestUtil.stubAppFunctions(app) // Re-stub app functions after shutdown

        Statsig.client = spyk()
        Statsig.client.statsigNetwork = network

        // Initialize SDK for second session
        println("Starting second initialization")
        Statsig.initializeAsync(app, "client-key", user, callbackAgain)

        // Check experiment immediately after init (before network response)
        experiment = Statsig.getExperiment("a_config")
        println("\n=== Second Session Initial Check ===")
        println("Evaluation reason: ${experiment.getEvaluationDetails().reason}")
        println("Value: ${experiment.getString("key", "default")}")
        assertEquals(EvaluationReason.Cache, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Wait for network response to complete
        println("Waiting for second network response...")
        didInitializeUserB.await(3, TimeUnit.SECONDS)
        println("Second network response received")

        // Verify new experiment from network response
        experiment = Statsig.getExperiment("b_config")
        println("\n=== Second Session Network Check ===")
        println("Evaluation reason: ${experiment.getEvaluationDetails().reason}")
        println("Value: ${experiment.getString("key", "default")}")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Verify old experiment is no longer available
        experiment = Statsig.getExperiment("a_config")
        println("\n=== Second Session Old Config Check ===")
        println("Evaluation reason: ${experiment.getEvaluationDetails().reason}")
        println("Value: ${experiment.getString("key", "default")}")
        assertEquals(EvaluationReason.Unrecognized, experiment.getEvaluationDetails().reason)
        assertEquals("default", experiment.getString("key", "default"))
    }
} 