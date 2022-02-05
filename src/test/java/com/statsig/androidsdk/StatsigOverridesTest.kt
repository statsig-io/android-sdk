package com.statsig.androidsdk

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class StatsigOverridesTest {
    private lateinit var app: Application

    private val aConfig =
        DynamicConfig("test_config",
            mapOf(
                "str" to "string-val",
                "bool" to true,
                "num" to 123))

    @Before
    internal fun setup() = runBlocking {
        app = mockk()

        Dispatchers.setMain(TestCoroutineDispatcher())

        TestUtil.mockApp(app)

        TestUtil.mockStatsigUtil()

        val statsigNetwork = TestUtil.mockNetwork()

        Statsig.client.statsigNetwork = statsigNetwork

        Statsig.initialize(app, "test-key")
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
        Statsig.overrideConfig(aConfig.getName(), aConfig)

        val config = Statsig.getConfig(aConfig.getName())
        assertEquals(aConfig.getName(), config.getName())
        assertEquals("string-val", config.getString("str", null))
        assertEquals(true, config.getBoolean("bool", false))
        assertEquals(123, config.getInt("num", 0))
    }

    @Test
    fun testOverrideExperiment()  {
        Statsig.overrideConfig(aConfig.getName(), aConfig)

        val experiment = Statsig.getExperiment("test_config")
        assertEquals(aConfig.getName(), experiment.getName())
        assertEquals("string-val", experiment.getString("str", null))
        assertEquals(true, experiment.getBoolean("bool", false))
        assertEquals(123, experiment.getInt("num", 0))
    }

    @Test
    fun testRemovingSingleOverride() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig(aConfig.getName(), aConfig)

        Statsig.removeOverride("always_off")
        assertFalse(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))

        val config = Statsig.getConfig(aConfig.getName())
        assertEquals(aConfig.getName(), config.getName())
        assertEquals("string-val", config.getString("str", null))
        assertEquals(true, config.getBoolean("bool", false))
        assertEquals(123, config.getInt("num", 0))
    }

    @Test
    fun testRemovingOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig(aConfig.getName(), aConfig)

        Statsig.removeAllOverrides()
        assertTrue(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))

        val config = Statsig.getConfig(aConfig.getName())
        assertEquals(aConfig.getName(), config.getName())
        assertNull(config.getString("str", null))
        assertFalse( config.getBoolean("bool", false))
        assertEquals(0, config.getInt("num", 0))
    }

    @Test
    fun testGetAllOverrides() {
        Statsig.overrideGate("always_off", true)
        Statsig.overrideGate("always_on", false)
        Statsig.overrideConfig(aConfig.getName(), aConfig)

        val overrides = Statsig.getAllOverrides()

        assertEquals(true, overrides.gates["always_off"])
        assertEquals(false, overrides.gates["always_on"])

        val config = overrides.configs[aConfig.getName()]
        assertEquals(aConfig.getName(), config?.getName())
        assertEquals(aConfig.getString("str", "a"), config?.getString("str", "b"))
    }
}