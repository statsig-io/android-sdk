package com.statsig.androidsdk

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class StatsigOverridesTest {
    private lateinit var app: Application

    private val aValueMap = mapOf(
        "str" to "string-val",
        "bool" to true,
        "num" to 123
    )

    private val aConfig = mapOf(
        "str" to "string-val",
        "bool" to true,
        "num" to 123)

    @Before
    internal fun setup() = runBlocking {
        TestUtil.mockDispatchers()
        TestUtil.mockStatsigUtil()
        app = mockk()
        TestUtil.stubAppFunctions(app)

        val statsigNetwork = TestUtil.mockNetwork()

        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = statsigNetwork

        Statsig.initialize(app, "test-key")
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testOverrideGate()  {
        Statsig.overrideGate("always_off", true)
        assertTrue(Statsig.checkGate("always_off"))

        Statsig.overrideGate("always_on", false)
        assertFalse(Statsig.checkGate("always_on"))
    }

    @Test
    fun testOverrideConfig()  {
        Statsig.overrideConfig("a_config", aConfig)

        val config = Statsig.getConfig("a_config")
        assertEquals("a_config", config.getName())
        assertEquals("string-val", config.getString("str", null))
        assertEquals(true, config.getBoolean("bool", false))
        assertEquals(123, config.getInt("num", 0))
        assertEquals(EvaluationReason.LocalOverride, config.getEvaluationDetails().reason)
    }

    @Test
    fun testOverrideExperiment()  {
        Statsig.overrideConfig("a_config", aConfig)

        val experiment = Statsig.getExperiment("a_config")
        assertEquals("a_config", experiment.getName())
        assertEquals("string-val", experiment.getString("str", null))
        assertEquals(true, experiment.getBoolean("bool", false))
        assertEquals(123, experiment.getInt("num", 0))
        assertEquals(EvaluationReason.LocalOverride, experiment.getEvaluationDetails().reason)
    }

    @Test
    fun testOverrideLayer() {
        Statsig.overrideLayer("a_layer", aValueMap)

        val layer = Statsig.getLayer("a_layer")
        assertEquals("a_layer", layer.getName())
        assertEquals("string-val", layer.getString("str", null))
        assertEquals(true, layer.getBoolean("bool", false))
        assertEquals(123, layer.getInt("num", 0))
        assertEquals(EvaluationReason.LocalOverride, layer.getEvaluationDetails().reason)
    }

    @Test
    fun testRemovingSingleLayerOverride() {
        Statsig.overrideGate("a_gate", true)
        Statsig.overrideConfig("a_config", aValueMap)
        Statsig.overrideLayer("a_layer", aValueMap)
        Statsig.overrideLayer("b_layer", aValueMap)

        Statsig.removeOverride("a_layer")

        assertTrue(Statsig.checkGate("a_gate"))

        val config = Statsig.getConfig("a_config")
        assertEquals(EvaluationReason.LocalOverride, config.getEvaluationDetails().reason)

        val layer = Statsig.getLayer("a_layer")
        assertEquals(EvaluationReason.Unrecognized, layer.getEvaluationDetails().reason)

        val anotherLayer = Statsig.getLayer("b_layer")
        assertEquals(EvaluationReason.LocalOverride, anotherLayer.getEvaluationDetails().reason)
    }

    @Test
    fun testRemovingSingleOverride() {
        Statsig.overrideGate("a_gate", true)
        Statsig.overrideConfig("a_config", aValueMap)
        Statsig.overrideConfig("b_config", aValueMap)
        Statsig.overrideLayer("a_layer", aValueMap)

        Statsig.removeOverride("a_config")

        assertTrue(Statsig.checkGate("a_gate"))

        val config = Statsig.getConfig("a_config")
        assertEquals(EvaluationReason.Unrecognized, config.getEvaluationDetails().reason)

        val anotherConfig = Statsig.getConfig("b_config")
        assertEquals(EvaluationReason.LocalOverride, anotherConfig.getEvaluationDetails().reason)

        val layer = Statsig.getLayer("a_layer")
        assertEquals(EvaluationReason.LocalOverride, layer.getEvaluationDetails().reason)
    }

    @Test
    fun testRemovingOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig("a_config", aValueMap)
        Statsig.overrideLayer("a_layer", aValueMap)

        Statsig.removeAllOverrides()
        assertTrue(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))

        val config = Statsig.getConfig("a_config")
        assertEquals("a_config", config.getName())
        assertNull(config.getString("str", null))
        assertFalse(config.getBoolean("bool", false))
        assertEquals(0, config.getInt("num", 0))
        // reason should be unrecognized because a_config does not exist
        assertEquals(EvaluationReason.Unrecognized, config.getEvaluationDetails().reason)

        val layer = Statsig.getConfig("a_layer")
        assertEquals("a_layer", layer.getName())
        assertNull(config.getString("str", null))
        assertEquals(EvaluationReason.Unrecognized, layer.getEvaluationDetails().reason)
    }

    @Test
    fun testGetAllOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig("a_config", aValueMap)
        Statsig.overrideLayer("a_layer", aValueMap)

        val overrides = Statsig.getAllOverrides()

        assertEquals(true, overrides.gates["always_off"])
        assertEquals(false, overrides.gates["always_on"])

        val config = overrides.configs["a_config"] ?: mapOf()
        assertEquals(aValueMap["str"], config["str"])

        val layer = overrides.layers["a_layer"] ?: mapOf()
        assertEquals(aValueMap["str"], layer["str"])
    }
}