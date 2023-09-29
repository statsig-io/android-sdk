package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert
import java.lang.reflect.Type
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
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
            return gson.fromJson(string, Map::class.java) as Map<String, Any>
        }

        private val dummyFeatureGates = mapOf(
            "always_on!" to
                APIFeatureGate(
                    "always_on!",
                    true,
                    "always_on_rule_id",
                    "always_on_group",
                    arrayOf(
                        mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"),
                        mapOf("gate" to "dependent_gate_2", "gateValue" to "true", "ruleID" to "rule_id_2"),
                    ),
                ),
            "always_off!" to
                APIFeatureGate(
                    "always_off!",
                    false,
                    "always_off_rule_id",
                    "always_off_group",
                    arrayOf(),
                ),
            "always_on_v2!" to
                APIFeatureGate(
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
                arrayOf(mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1")),
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
        internal fun startStatsigAndWait(app: Application, user: StatsigUser = StatsigUser("jkw"), options: StatsigOptions = StatsigOptions(), server: MockWebServer? = null) = runBlocking {
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
            if (server != null) {
                useServer(Statsig.client, server)
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
                StatsigOptions::class.java,
            )
            setupMethod.isAccessible = true
            setupMethod.invoke(Statsig.client, app, "client-test", user, options)
        }

        fun getMockApp(): Application {
            return mockk()
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
            mockkObject(Hashing)
            every {
                Hashing.getHashedString(any(), null)
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
                firstArg<SharedPreferences>().edit().putString(secondArg<String>(), thirdArg<String>()).apply()
            }

            coEvery {
                StatsigUtil.removeFromSharedPrefs(any(), any())
            } coAnswers {
                firstArg<SharedPreferences>().edit().remove(secondArg<String>())
            }
        }

        @JvmName("mockBrokenServer")
        internal fun mockBrokenServer(): MockWebServer {
            var server = MockWebServer()
            server.apply {
                dispatcher = object : Dispatcher() {
                    @Throws(InterruptedException::class)
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return MockResponse().setResponseCode(404)
                    }
                }
            }
            useServer(server = server)
            return server
        }

        @JvmName("mockServer")
        internal fun mockServer(
            featureGates: Map<String, APIFeatureGate> = dummyFeatureGates,
            dynamicConfigs: Map<String, APIDynamicConfig> = dummyDynamicConfigs,
            layerConfigs: Map<String, APIDynamicConfig> = dummyLayerConfigs,
            time: Long? = null,
            hasUpdates: Boolean = true,
            responseCode: Int = 200,
            onLog: ((LogEventData) -> Unit)? = null,
            getInitializeResponse: ((InitializeRequestBody) -> InitializeResponse)? = null,
        ): MockWebServer {
            var server = MockWebServer()
            server.apply {
                dispatcher = object : Dispatcher() {
                    @Throws(InterruptedException::class)
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        when (request.path) {
                            "/v1/initialize" -> {
                                val requestBody = request.body.readUtf8()
                                var response: InitializeResponse = makeInitializeResponse(featureGates, dynamicConfigs, layerConfigs, time, hasUpdates)
                                if (getInitializeResponse != null) {
                                    response = getInitializeResponse.invoke(Gson().fromJson(requestBody, InitializeRequestBody::class.java))
                                }
                                val type = object : TypeToken<MutableMap<String, Any>>() {}.type
                                val gson = GsonBuilder().registerTypeAdapter(
                                    type,
                                    PolymorphicSerializer(),
                                ).create()
                                var stringified = gson.toJson(response)
                                return MockResponse().setResponseCode(responseCode).setBody(stringified)
                            }
                            "/v1/log_event" -> {
                                val requestBody = request.body.readUtf8()
                                onLog?.invoke(Gson().fromJson(requestBody, LogEventData::class.java))
                                return MockResponse().setResponseCode(responseCode)
                            }
                        }
                        return MockResponse().setResponseCode(responseCode)
                    }
                }
            }
            useServer(server = server)
            return server
        }

        internal fun mockClientWithServer(client: StatsigClient? = null, server: MockWebServer): StatsigClient {
            var mockClient: StatsigClient = if (client == null) spyk() else spyk(client)
            every {
                mockClient.options
            } answers {
                callOriginal().apply { api = server.url("/v1").toString() }
            }
            return mockClient
        }

        internal fun useServer(client: StatsigClient? = null, server: MockWebServer) {
            Statsig.client = mockClientWithServer(client ?: Statsig.client, server)
        }

        // Because Gson can't handle serializing polymorphic objects in Java
        // we need a custom serializer to handle nested object values in APIDynamicConfig
        internal class PolymorphicSerializer : JsonSerializer<Any> {
            override fun serialize(
                src: Any?,
                typeOfSrc: Type?,
                context: JsonSerializationContext?,
            ): JsonElement {
                return src.let {
                    if (it is Map<*, *>) {
                        var obj = JsonObject()
                        for (item in it) {
                            val key = item.key
                            val value = item.value
                            if (key is String) {
                                obj.add(key, serialize(value, null, null))
                            }
                        }
                        return@let obj
                    } else if (it is Map<*, *>) {
                        var obj = JsonObject()
                        for (item in it) {
                            val key = item.key
                            val value = item.value
                            if (key is String) {
                                obj.add(key, serialize(value, null, null))
                            }
                        }
                        return@let obj
                    } else if (it is String) {
                        return@let JsonPrimitive(it)
                    } else if (it is Number) {
                        return@let JsonPrimitive(it)
                    } else if (it is Boolean) {
                        return@let JsonPrimitive(it)
                    } else if (it is Char) {
                        return@let JsonPrimitive(it)
                    } else if (it is Array<*>) {
                        var arr = JsonArray()
                        for (item in it) {
                            arr.add(serialize(item, null, null))
                        }
                        return@let arr
                    } else {
                        return@let JsonPrimitive(it.toString())
                    }
                }
            }
        }
    }
}
