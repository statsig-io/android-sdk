package com.statsig.androidsdk

import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParameterStoreTest {

    private var initUser: StatsigUser? = null
    private var app: Application = mockk()
    private val user = StatsigUser(userID = "a-user")
    private var client: StatsigClient = StatsigClient()

    private lateinit var paramStore: ParameterStore

    @Before
    internal fun setup() = runBlocking {
        app = mockk()
        TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()
        TestUtil.mockDispatchers()

        val statsigNetwork = TestUtil.mockNetwork()

        client = StatsigClient()
        client.statsigNetwork = statsigNetwork

        client.initialize(app, "test-key")

        TestUtil.startStatsigAndWait(app, user, StatsigOptions(overrideStableID = "custom_stable_id"), network = TestUtil.mockNetwork())
        paramStore = ParameterStore(
            statsigClient = client,
            paramStore = mapOf(
                "static_value" to mapOf(
                    "value" to "test",
                    "ref_type" to "static",
                    "param_type" to "string",
                ),
                "static_bool" to mapOf(
                    "value" to true,
                    "ref_type" to "static",
                    "param_type" to "boolean",
                ),
                "gate_value" to mapOf(
                    "ref_type" to "gate",
                    "param_type" to "string",
                    "gate_name" to "always_on",
                    "pass_value" to "pass",
                    "fail_value" to "fail",
                ),
                "gate_bool" to mapOf(
                    "ref_type" to "gate",
                    "param_type" to "string",
                    "gate_name" to "always_on",
                    "pass_value" to true,
                    "fail_value" to false,
                ),
                "gate_number" to mapOf(
                    "ref_type" to "gate",
                    "param_type" to "number",
                    "gate_name" to "always_on",
                    "pass_value" to 1,
                    "fail_value" to -1,
                ),
                "layer_value" to mapOf(
                    "ref_type" to "layer",
                    "param_type" to "string",
                    "layer_name" to "allocated_layer",
                    "param_name" to "string",
                ),
                "experiment_value" to mapOf(
                    "ref_type" to "experiment",
                    "param_type" to "string",
                    "experiment_name" to "exp",
                    "param_name" to "string",
                ),
                "dynamic_config_value" to mapOf(
                    "ref_type" to "dynamic_config",
                    "param_type" to "string",
                    "config_name" to "test_config",
                    "param_name" to "string",
                ),
            ),
            name = "test_parameter_store",
            evaluationDetails = EvaluationDetails(EvaluationReason.Network, lcut = 0),
            options = ParameterStoreEvaluationOptions(disableExposureLog = true),
        )

        return@runBlocking
    }

    @Test
    fun testNullFallback() {
        assertNull(paramStore.getString("nonexistent", null))
        assertEquals("test", paramStore.getString("static_value", null))
    }

    @Test
    fun testNullFallbackForGates() {
        assertEquals("pass", paramStore.getString("gate_value", null))
    }

    @Test
    fun testNullFallbackForLayers() {
        assertEquals("test", paramStore.getString("layer_value", null))
    }

    @Test
    fun testNullFallbackForExperiments() {
        assertEquals("test", paramStore.getString("experiment_value", null))
    }

    @Test
    fun testNullFallbackForDynamicConfigs() {
        assertEquals("test", paramStore.getString("dynamic_config_value", null))
    }

    @Test
    fun testWrongStaticType() {
        assertEquals("DEFAULT", paramStore.getString("gate_bool", "DEFAULT"))
    }

    @Test
    fun testGetBoolean() {
        assertEquals(true, paramStore.getBoolean("gate_bool", false))
    }

    @Test
    fun testGetBooleanFallback() {
        assertEquals(true, paramStore.getBoolean("nonexistent", true))
        assertEquals(false, paramStore.getBoolean("nonexistent", false))
    }

    @Test
    fun testGetNumber() {
        assertEquals(1.0, paramStore.getDouble("gate_number", 2.0), 0.01)
    }

    @Test
    fun testGetNumberFallback() {
        assertEquals(2.0, paramStore.getDouble("nonexistent", 2.0), 0.01)
        assertEquals(0.0, paramStore.getDouble("nonexistent", 0.0), 0.01)
    }

    @Test
    fun testGetNumberWrongType() {
        assertEquals("DEFAULT", paramStore.getString("gate_number", "DEFAULT"))
        assertEquals(true, paramStore.getBoolean("gate_number", true))
    }

    @Test
    fun testWrongGateType() {
        assertEquals("DEFAULT", paramStore.getString("static_bool", "DEFAULT"))
    }
}
