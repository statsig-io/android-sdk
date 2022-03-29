package com.statsig.androidsdk

import android.app.Application
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.mockk.*

class TestUtil {
    companion object {
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
                mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
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

        private val dummyLayerConfigs = mapOf(
            "allocated_layer!" to APIDynamicConfig(
                "allocated_layer!",
                mapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                "default",
                arrayOf(),
                isExperimentActive = true,
                isUserInExperiment = true,
                allocatedExperimentName = "layer_exp!"
            ),
            "unallocated_layer!" to APIDynamicConfig(
                "unallocated_layer!",
                mapOf(
                    "string" to "default_string",
                    "number" to 9942,
                    "otherNumber" to 9917)
                ,
                "default"
            )
        )

        internal fun makeInitializeResponse(
            featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
            dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
            layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs): InitializeResponse {
            return InitializeResponse(
                featureGates = featureGates,
                configs = dynamicConfigs,
                layerConfigs = layerConfigs,
                hasUpdates = true,
                time = 1621637839,
            )
        }

        fun mockApp(app: Application): TestSharedPreferences {
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
        }

        internal fun mockNetwork(featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
                                 dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
                                 layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs,
                                 captureUser: ((StatsigUser) -> Unit)? = null ): StatsigNetwork {
            val statsigNetwork = mockk<StatsigNetwork>()

            coEvery {
                statsigNetwork.apiRetryFailedLogs(any(), any())
            } returns Unit
            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            }

            coEvery {
                statsigNetwork.initialize(any(), any(), any(), any(), any(), any())
            } coAnswers {
                captureUser?.invoke(thirdArg())
                makeInitializeResponse(featureGates, dynamicConfigs, layerConfigs)
            }

            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            } answers {

            }

            return statsigNetwork
        }
    }
}