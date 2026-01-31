package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.spyk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

internal suspend fun getResponseForUser(user: StatsigUser, configName: String): InitializeResponse =
    withContext(Dispatchers.IO) {
        delay(500)
        TestUtil.makeInitializeResponse(
            mapOf(),
            mapOf(
                "$configName!" to APIDynamicConfig(
                    "$configName!",
                    mutableMapOf(
                        "key" to if (user.userID == "") "logged_out_value" else "value"
                    ),
                    "default"
                )
            ),
            mapOf()
        )
    }

@RunWith(RobolectricTestRunner::class)
class ExperimentCacheTest {
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork
    private var initializeCalls: Int = 0

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockHashing()

        // Setup network mock that returns different responses for first/second session
        network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val res = getResponseForUser(
                secondArg(),
                if (initializeCalls <
                    2
                ) {
                    "a_config"
                } else {
                    "b_config"
                }
            )
            initializeCalls++
            res
        }

        Statsig.client = spyk()
        Statsig.client.statsigNetwork = network
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
            custom = mapOf(
                "test" to "custom_value"
            )
        }

        val didInitializeLoggedOutUser = CountDownLatch(1)
        val didInitializeLoggedInUser = CountDownLatch(1)

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeLoggedOutUser.countDown()
            }

            override fun onStatsigUpdateUser() {
                didInitializeLoggedInUser.countDown()
            }
        }
        // First app session
        Statsig.initializeAsync(app, "client-key", loggedOutUser, callback, initializationOptions)

        // Wait for network response to complete
        didInitializeLoggedOutUser.await(3, TimeUnit.SECONDS)

        // Verify experiment works for logged out user
        var experiment = Statsig.getExperiment("a_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("logged_out_value", experiment.getString("key", "default"))

        Statsig.updateUserAsync(loggedInUser, callback)
        didInitializeLoggedInUser.await(3, TimeUnit.SECONDS)

        // Verify experiment works for logged in user
        experiment = Statsig.getExperiment("a_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))
        // should be written to cache now

        // Shutdown SDK
        Statsig.shutdown()

        // Setup second session
        val didInitializeLoggedInUserOnNextSession = CountDownLatch(1)

        val callbackAgain = object : IStatsigCallback {
            override fun onStatsigInitialize() {
                didInitializeLoggedInUserOnNextSession.countDown()
            }

            override fun onStatsigUpdateUser() {
            }
        }
        TestUtil.mockDispatchers()

        Statsig.client = spyk()
        Statsig.client.statsigNetwork = network

        val loggedInUserV2 = StatsigUser("test-user-id").apply {
            customIDs = mapOf(
                "deviceId" to "test-device-id",
                "companyId" to "test-company-id"
            )
            custom = mapOf(
                "test" to "custom_value",
                "prop" to "value"
            )
        }

        // Initialize SDK for second session
        Statsig.initializeAsync(
            app,
            "client-key",
            loggedInUserV2,
            callbackAgain,
            initializationOptions
        )

        // Check experiment immediately after init (before network response) for cached value
        experiment = Statsig.getExperiment("a_config")
        assertEquals(EvaluationReason.Cache, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Wait for network response to complete
        didInitializeLoggedInUserOnNextSession.await(3, TimeUnit.SECONDS)

        // Verify new experiment from network response
        experiment = Statsig.getExperiment("b_config")
        assertEquals(EvaluationReason.Network, experiment.getEvaluationDetails().reason)
        assertEquals("value", experiment.getString("key", "default"))

        // Verify old experiment is no longer available
        experiment = Statsig.getExperiment("a_config")
        assertEquals(EvaluationReason.Unrecognized, experiment.getEvaluationDetails().reason)
        assertEquals("default", experiment.getString("key", "default"))
    }
}
