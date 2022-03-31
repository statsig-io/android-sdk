package com.statsig.androidsdk

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class StatsigOverridesTest {
    private lateinit var app: Application

    private val aConfig = mapOf(
        "str" to "string-val",
        "bool" to true,
        "num" to 123)

    @Before
    internal fun setup() = runBlocking {
        TestUtil.overrideMainDispatcher()

        app = mockk()

        TestUtil.stubAppFunctions(app)

        TestUtil.mockStatsigUtil()

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
    }

    @Test
    fun testOverrideExperiment()  {
        Statsig.overrideConfig("a_config", aConfig)

        val experiment = Statsig.getExperiment("a_config")
        assertEquals("a_config", experiment.getName())
        assertEquals("string-val", experiment.getString("str", null))
        assertEquals(true, experiment.getBoolean("bool", false))
        assertEquals(123, experiment.getInt("num", 0))
    }

    @Test
    fun testRemovingSingleOverride() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig("a_config", aConfig)

        Statsig.removeOverride("always_off")
        assertFalse(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))

        val config = Statsig.getConfig("a_config")
        assertEquals("a_config", config.getName())
        assertEquals("string-val", config.getString("str", null))
        assertEquals(true, config.getBoolean("bool", false))
        assertEquals(123, config.getInt("num", 0))
    }

    @Test
    fun testRemovingOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig("a_config", aConfig)

        Statsig.removeAllOverrides()
        assertTrue(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))

        val config = Statsig.getConfig("a_config")
        assertEquals("a_config", config.getName())
        assertNull(config.getString("str", null))
        assertFalse( config.getBoolean("bool", false))
        assertEquals(0, config.getInt("num", 0))
    }

    @Test
    fun testGetAllOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig("a_config", aConfig)

        val overrides = Statsig.getAllOverrides()

        assertEquals(true, overrides.gates["always_off"])
        assertEquals(false, overrides.gates["always_on"])

        val config = overrides.configs["a_config"] ?: mapOf()
        assertEquals(aConfig["str"] , config["str"])
    }
}