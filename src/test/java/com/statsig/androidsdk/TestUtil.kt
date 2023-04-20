package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TestUtil {
    companion object {
        private val dispatcher = TestCoroutineDispatcher()

        fun mockDispatchers() {
            Dispatchers.setMain(dispatcher)
            mockkConstructor(CoroutineDispatcherProvider::class)
            every {
                anyConstructed<CoroutineDispatcherProvider>().io
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().main
            } returns dispatcher
            every {
                anyConstructed<CoroutineDispatcherProvider>().default
            } returns dispatcher
        }

        fun getConfigValueMap(): Map<String, Any> {
            val string = """
            {
                "testString": "test",
                "testBoolean": true,
                "testInt": 12,
                "testDouble": 42.3,
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
            val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
            return gson.fromJson(string, Map::class.java) as Map<String, Any>
        }

        private val dummyFeatureGates = mapOf(
            "always_on!" to
                APIFeatureGate(
                    "always_on!",
                    true,
                    "always_on_rule_id",
                    arrayOf(
                        mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"),
                        mapOf("gate" to "dependent_gate_2", "gateValue" to "true", "ruleID" to "rule_id_2")
                    )
                ),
            "always_off!" to
                APIFeatureGate(
                    "always_off!",
                    false,
                    "always_on_rule_id",
                    arrayOf()
                ),
            "always_on_v2!" to
                    APIFeatureGate(
                        "always_off!",
                        true,
                        "always_on_v2_rule_id",
                        arrayOf()
                    ),
        )

        private val dummyDynamicConfigs = mapOf(
            "test_config!" to APIDynamicConfig(
                "test_config!",
                mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "default",
                arrayOf(mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"))
            ),
            "exp!" to APIDynamicConfig(
                "exp!",
                mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "exp_rule",
                arrayOf()
            ),
            "layer_exp!" to APIDynamicConfig(
                "layer_exp!",
                mutableMapOf("string" to "test", "number" to 42),
                "exp_rule",
                arrayOf(),
                isExperimentActive = true,
                isUserInExperiment = true,
            ),
            "other_layer_exp!" to APIDynamicConfig(
                "other_layer_exp!",
                mutableMapOf("string" to "other_test", "number" to 7742, "otherNumber" to 8817),
                "exp_rule",
                arrayOf(),
                isExperimentActive = true,
                isUserInExperiment = true,
                )
        )

        val dummyHoldoutExposure = mapOf(
          "gate" to "holdout!",
          "gateValue" to "true",
          "ruleID" to "assadfs"
        )

        val dummyTargetingGateExposure = mapOf(
          "gate" to "targeting!",
          "gateValue" to "true",
          "ruleID" to "asdf57"
        )

        val dummySecondaryExposures = arrayOf(
          dummyHoldoutExposure,
          dummyHoldoutExposure,
          dummyTargetingGateExposure
        )

        val dummyUndelegatedSecondaryExposures = arrayOf(
          dummyHoldoutExposure
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
                    "otherNumber" to 9917)
                ,
                "default",
                secondaryExposures = arrayOf(
                  dummyHoldoutExposure,
                ),
                undelegatedSecondaryExposures = arrayOf(
                  dummyHoldoutExposure
                ),
                allocatedExperimentName = ""
            )
        )

        internal fun makeInitializeResponse(
            featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
            dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
            layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs): InitializeResponse.SuccessfulInitializeResponse {
            return InitializeResponse.SuccessfulInitializeResponse(
                featureGates = featureGates,
                configs = dynamicConfigs,
                layerConfigs = layerConfigs,
                hasUpdates = true,
                time = 1621637839,
            )
        }

        @JvmName("startStatsigAndWait")
        internal fun startStatsigAndWait(app: Application, user: StatsigUser = StatsigUser("jkw"), options: StatsigOptions = StatsigOptions(), network: StatsigNetwork? = null) = runBlocking {
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
        internal fun startStatsigAndDontWait(app: Application, user: StatsigUser, options: StatsigOptions) {
            Statsig.client = StatsigClient()

            val setupMethod = Statsig.client.javaClass.getDeclaredMethod(
                "setup",
                Application::class.java,
                String::class.java,
                StatsigUser::class.java,
                StatsigOptions::class.java
            )
            setupMethod.isAccessible = true
            setupMethod.invoke(Statsig.client, app, "client-test", user, options)
        }

        fun getMockApp(): Application {
          return mockk()
        }

        @JvmName("captureLogs")
        internal fun captureLogs(network: StatsigNetwork, onLog: ((LogEventData) -> Unit)? = null) {
          coEvery {
            network.apiPostLogs(any(), any(), any())
          } answers {
              onLog?.invoke(Gson().fromJson(thirdArg<String>(), LogEventData::class.java))
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
            every {
                app.registerActivityLifecycleCallbacks(any())
            } returns Unit

            return sharedPrefs
        }

        fun mockStatsigUtil() {
            mockkObject(StatsigUtil)
            every {
                StatsigUtil.getHashedString(any())
            } answers {
                firstArg<String>() + "!"
            }

            coEvery {
                StatsigUtil.getFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().getString(secondArg<String>(), null)
            }

            coEvery {
                StatsigUtil.saveStringToSharedPrefs(any(), any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().putString(secondArg<String>(), thirdArg<String>()).apply()
            }

            coEvery {
                StatsigUtil.removeFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().remove(secondArg<String>())
            }
        }

        @JvmName("mockNetwork")
        internal fun mockNetwork(featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
                                 dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
                                 layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs,
                                 captureUser: ((StatsigUser) -> Unit)? = null): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()

            coEvery {
                statsigNetwork.apiRetryFailedLogs(any(), any())
            } returns Unit

            coEvery {
                statsigNetwork.initialize(any(), any(), any(), any(), any(), any())
            } coAnswers {
                captureUser?.invoke(thirdArg())
                makeInitializeResponse(featureGates, dynamicConfigs, layerConfigs)
            }

            coEvery {
                statsigNetwork.addFailedLogRequest(any())
            } answers {}

            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            } answers {}

            return statsigNetwork
        }
    }
}