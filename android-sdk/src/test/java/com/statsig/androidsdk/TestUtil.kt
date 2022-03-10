package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkClass
import io.mockk.mockkObject

class TestUtil {
    companion object {
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

        internal fun mockNetwork(captureUser: ((StatsigUser) -> Unit)? = null ): StatsigNetwork {
            val statsigNetwork = mockkClass(StatsigNetwork::class)

            coEvery {
                statsigNetwork.initialize(any(), any<String>(), any(), any(), any(), any())
            } coAnswers {
                InitializeResponse(mapOf(), mapOf(), false, 0)
            }
            coEvery {
                statsigNetwork.apiRetryFailedLogs(any(), any())
            } returns Unit
            coEvery {
                statsigNetwork.apiPostLogs(any(), any(), any())
            }

            coEvery {
                statsigNetwork.initialize(any(), any<String>(), any(), any(), any(), any())
            } coAnswers {
                captureUser?.invoke(thirdArg())
                InitializeResponse(
                    featureGates = mapOf(
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
                    ),
                    configs = mapOf(
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
                        )
                    ),
                    hasUpdates = true,
                    time = 1621637839,
                )
            }

            return statsigNetwork
        }
    }
}