package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TestUtil {
    companion object {
        private val dispatcher = TestCoroutineDispatcher()
        val coroutineScope = TestCoroutineScope(dispatcher)

        fun mockDispatchers(): TestCoroutineDispatcher {
            Dispatchers.setMain(dispatcher)
            mockkConstructor(CoroutineDispatcherProvider::class)
            mockkConstructor(MainCoroutineDispatcher::class)
            every {
                anyConstructed<CoroutineDispatcherProvider>().io
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().main
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().default
            } returns dispatcher
            return dispatcher
        }

        fun getConfigValueMap(): Map<String, Any> {
            val string = """
            {
                "testString": "test",
                "testBoolean": true,
                "testInt": 12,
                "testDouble": 42.3,
                "testAnotherDouble": 41.0,
                "testLong": 9223372036854775806,
                "testArray": [
                  "one",
                  "two"
                ],
                "testIntArray": [
                  3,
                  2
                ],
                "testDoubleArray": [
                  3.1,
                  2.1
                ],
                "testBooleanArray": [
                  true,
                  false
                ],
                "testNested": {
                  "nestedString": "nested",
                  "nestedBoolean": true,
                  "nestedDouble": 13.74,
                  "nestedLong": 13
                }
            }
            """.trimIndent()
            val gson = StatsigUtil.getGson()

            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(
                string,
                Map::class.java,
            ) as Map<String, Any>
        }

        private val dummyFeatureGates = mapOf(
            "always_on!" to APIFeatureGate(
                "always_on!",
                true,
                "always_on_rule_id",
                "always_on_group",
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1",
                    ),
                    mapOf(
                        "gate" to "dependent_gate_2",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_2",
                    ),
                ),
            ),
            "always_off!" to APIFeatureGate(
                "always_off!",
                false,
                "always_off_rule_id",
                "always_off_group",
                arrayOf(),
            ),
            "always_on_v2!" to APIFeatureGate(
                "always_on_v2!",
                true,
                "always_on_v2_rule_id",
                "always_on_v2_group",
                arrayOf(),
            ),
        )

        private val dummyDynamicConfigs = mapOf(
            "test_config!" to APIDynamicConfig(
                "test_config!",
                mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "default",
                null,
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1",
                    ),
                ),
            ),
            "exp!" to APIDynamicConfig(
                "exp!",
                mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "exp_rule",
                "exp_group",
                arrayOf(),
            ),
            "layer_exp!" to APIDynamicConfig(
                "layer_exp!",
                mutableMapOf("string" to "test", "number" to 42),
                "exp_rule",
                "exp_group",
                arrayOf(),
                isExperimentActive = true,
                isUserInExperiment = true,
            ),
            "other_layer_exp!" to APIDynamicConfig(
                "other_layer_exp!",
                mutableMapOf("string" to "other_test", "number" to 7742, "otherNumber" to 8817),
                "exp_rule",
                "exp_group",
                arrayOf(),
                isExperimentActive = true,
                isUserInExperiment = true,
            ),
        )

        val dummyHoldoutExposure = mapOf(
            "gate" to "holdout!",
            "gateValue" to "true",
            "ruleID" to "assadfs",
        )

        val dummyTargetingGateExposure = mapOf(
            "gate" to "targeting!",
            "gateValue" to "true",
            "ruleID" to "asdf57",
        )

        val dummySecondaryExposures = arrayOf(
            dummyHoldoutExposure,
            dummyHoldoutExposure,
            dummyTargetingGateExposure,
        )

        val dummyUndelegatedSecondaryExposures = arrayOf(
            dummyHoldoutExposure,
        )

        private val dummyLayerConfigs = mapOf(
            "allocated_layer!" to APIDynamicConfig(
                "allocated_layer!",
                mapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "default",
                secondaryExposures = dummySecondaryExposures,
                undelegatedSecondaryExposures = dummyUndelegatedSecondaryExposures,
                isExperimentActive = true,
                isUserInExperiment = true,
                allocatedExperimentName = "layer_exp!",
                explicitParameters = arrayOf("string", "number"),
            ),
            "unallocated_layer!" to APIDynamicConfig(
                "unallocated_layer!",
                mapOf(
                    "string" to "default_string",
                    "number" to 9942,
                    "otherNumber" to 9917,
                ),
                "default",
                secondaryExposures = arrayOf(
                    dummyHoldoutExposure,
                ),
                undelegatedSecondaryExposures = arrayOf(
                    dummyHoldoutExposure,
                ),
                allocatedExperimentName = "",
            ),
        )

        internal fun makeInitializeResponse(
            featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
            dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
            layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs,
            time: Long? = null,
            hasUpdates: Boolean = true,
        ): InitializeResponse.SuccessfulInitializeResponse {
            return InitializeResponse.SuccessfulInitializeResponse(
                featureGates = featureGates,
                configs = dynamicConfigs,
                layerConfigs = layerConfigs,
                hasUpdates = hasUpdates,
                time = time ?: 1621637839,
                derivedFields = mapOf(),
            )
        }

        @JvmName("startStatsigAndWait")
        internal fun startStatsigAndWait(
            app: Application,
            user: StatsigUser = StatsigUser("jkw"),
            options: StatsigOptions = StatsigOptions(),
            network: StatsigNetwork? = null,
        ) = runBlocking {
            val countdown = CountDownLatch(1)
            val callback = object : IStatsigCallback {
                override fun onStatsigInitialize() {
                    countdown.countDown()
                }

                override fun onStatsigUpdateUser() {
                    Assert.fail("Statsig.onStatsigUpdateUser should not have been called")
                }
            }

            Statsig.client = StatsigClient()
            if (network != null) {
                Statsig.client.statsigNetwork = network
            }
            Statsig.initializeAsync(app, "client-apikey", user, callback, options)
            countdown.await(1L, TimeUnit.SECONDS)
        }

        @JvmName("startStatsigAndDontWait")
        internal fun startStatsigAndDontWait(
            app: Application,
            user: StatsigUser,
            options: StatsigOptions,
        ) {
            Statsig.client = StatsigClient()

            val setupMethod = Statsig.client.javaClass.getDeclaredMethod(
                "setup",
                Application::class.java,
                String::class.java,
                StatsigUser::class.java,
                StatsigOptions::class.java,
            )
            setupMethod.isAccessible = true
            setupMethod.invoke(Statsig.client, app, "client-test", user, options)
        }

        internal fun startStatsigClientAndWait(
            app: Application,
            client: StatsigClient,
            sdkKey: String,
            user: StatsigUser,
            options: StatsigOptions = StatsigOptions(),
            network: StatsigNetwork? = null,
        ) = runBlocking {
            if (network != null) {
                client.statsigNetwork = network
            }
            client.initialize(app, sdkKey, user, options)
        }

        fun getMockApp(): Application {
            return mockk()
        }

        @JvmName("captureLogs")
        internal fun captureLogs(network: StatsigNetwork, onLog: ((LogEventData) -> Unit)? = null) {
            coEvery {
                network.apiPostLogs(any(), any(), any())
            } answers {
                onLog?.invoke(Gson().fromJson(secondArg<String>(), LogEventData::class.java))
            }
        }

        fun stubAppFunctions(app: Application): TestSharedPreferences {
            val sharedPrefs = TestSharedPreferences()

            every {
                app.getSharedPreferences(any(), any())
            } returns sharedPrefs

            every {
                app.applicationInfo
            } returns null

            every {
                app.packageManager
            } returns null
            mockAppLifecycleCallbacks(app)
            mockNetworkConnectivityService(app)

            return sharedPrefs
        }

        fun mockStatsigUtil() {
            mockkObject(Hashing)
            every {
                Hashing.getHashedString(any(), any())
            } answers {
                firstArg<String>() + "!"
            }

            mockkObject(StatsigUtil)
            coEvery {
                StatsigUtil.getFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().getString(secondArg<String>(), null)
            }

            coEvery {
                StatsigUtil.saveStringToSharedPrefs(any(), any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit()
                    .putString(secondArg<String>(), thirdArg<String>()).apply()
            }

            coEvery {
                StatsigUtil.removeFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().remove(secondArg<String>())
                firstArg<SharedPreferences>().edit().apply()
            }
        }

        @JvmName("mockBrokenNetwork")
        internal fun mockBrokenNetwork(
            onLog: ((LogEventData) -> Unit)? = null,
        ): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()
            coEvery {
                statsigNetwork.apiRetryFailedLogs(any())
            } answers {
                throw IOException("Example exception in StatsigNetwork apiRetryFailedLogs")
            }

            coEvery {
                statsigNetwork.initialize(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } answers {
                throw IOException("Example exception in StatsigNetwork initialize")
            }

            // This function is not making network request
            coEvery {
                statsigNetwork.addFailedLogRequest(any())
            } answers {}

            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            } answers {
                onLog?.invoke(
                    StatsigUtil.getGson().fromJson(secondArg<String>(), LogEventData::class.java),
                )
                throw IOException("Example exception in StatsigNetwork apiPostLogs")
            }
            return statsigNetwork
        }

        @JvmName("mockNetwork")
        internal fun mockNetwork(
            featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
            dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
            layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs,
            time: Long? = null,
            hasUpdates: Boolean = true,
            captureUser: ((StatsigUser) -> Unit)? = null,
            onLog: ((LogEventData) -> Unit)? = null,
        ): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()

            coEvery {
                statsigNetwork.apiRetryFailedLogs(any())
            } answers {}

            coEvery {
                statsigNetwork.initialize(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                captureUser?.invoke(secondArg())
                makeInitializeResponse(featureGates, dynamicConfigs, layerConfigs, time, hasUpdates)
            }

            coEvery {
                statsigNetwork.addFailedLogRequest(any())
            } coAnswers {}

            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            } answers {
                onLog?.invoke(
                    StatsigUtil.getGson().fromJson(secondArg<String>(), LogEventData::class.java),
                )
            }

            return statsigNetwork
        }

        private fun mockAppLifecycleCallbacks(application: Application) {
            every {
                application.registerActivityLifecycleCallbacks(any())
            } returns Unit

            every {
                application.unregisterActivityLifecycleCallbacks(any())
            } returns Unit
        }

        fun clearMockDispatchers() {
            Dispatchers.resetMain()
        }

        internal fun mockNetworkConnectivityService(
            application: Application,
        ) {
            val connectivityManager = mockk<ConnectivityManager>(relaxed = true)

            // Legacy API
            val info = mockk<NetworkInfo>()
            every { info.isConnectedOrConnecting } returns true
            every { connectivityManager.activeNetworkInfo } returns info

            // Modern API
            val network = mockk<Network>()
            every { connectivityManager.activeNetwork } returns network

            val capabilities = mockk<NetworkCapabilities>()
            every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
            every { connectivityManager.getNetworkCapabilities(network) } returns capabilities

            every {
                application.getSystemService(Context.CONNECTIVITY_SERVICE)
            } returns connectivityManager
        }
    }
}
