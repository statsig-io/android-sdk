package com.statsig.androidsdk

import android.app.Application
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CoroutineDispatcher
import io.mockk.*

class StatsigTest {

    private lateinit var app: Application

    @Before
    internal fun setup() {
        app = mockk<Application>()
        every {
            app.getSharedPreferences(any(), any())
        } returns null
        every {
            app.getApplicationInfo()
        } returns null
        every {
            app.getPackageManager()
        } returns null
        every {
            app.registerActivityLifecycleCallbacks(any())
        } returns Unit



        mockkObject(StatsigUtil)
        every {
            StatsigUtil.getHashedString(any())
        } answers {
            firstArg<String>()
        }

        mockkObject(StatsigNetwork)
        coEvery {
            StatsigNetwork.initialize(any(), any(), any(), any(), any())
        } returns
            InitializeResponse(
                featureGates = mapOf(
                    "always_on" to
                            APIFeatureGate(
                                "always_on",
                                true,
                                "always_on_rule_id",
                                arrayOf(
                                    mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"),
                                    mapOf("gate" to "dependent_gate_2", "gateValue" to "true", "ruleID" to "rule_id_2")
                                )
                            ),
                    "always_off" to
                            APIFeatureGate(
                                "always_off",
                                false,
                                "always_on_rule_id",
                                arrayOf()
                            ),
                ),
                configs = mapOf(
                    "test_config" to APIDynamicConfig(
                        "test_config",
                        mapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                        "default",
                        arrayOf(mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"))
                    )
                ),
                hasUpdates = true,
                time = 1621637839,
            )
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun initializeBadInput() = runBlocking {
        try {
            Statsig.initialize(
                app,
                "secret-111aaa",
                null,
            )

            fail("Statsig.initialize() did not fail for a non client/test key")
        } catch (expectedException: java.lang.IllegalArgumentException) {
        }
    }

    @Test
    fun testInitialize() = runBlocking {
        Statsig.initialize(
            app,
            "client-111aaa",
            null,
        )
        assertTrue(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))
        assertFalse(Statsig.checkGate("not_a_valid_gate_name"))

        assertEquals(
            "test",
            Statsig.getConfig("test_config")!!.getString("string", "fallback"),
        )
        assertEquals(
            42,
            Statsig.getConfig("test_config")!!.getInt("number", 0)
        )
        assertEquals(
            "default string instead",
            Statsig.getConfig("test_config")!!
                .getString("otherNumber", "default string instead"),
        )
        assertEquals("", Statsig.getConfig("not_a_valid_config").getRuleID())
        assertEquals("default", Statsig.getConfig("test_config")!!.getRuleID())

        assertEquals(Statsig.logger.events.count(), 8)

        // validate gate exposure
        assertEquals(Statsig.logger.events[0].metadata!!["gate"], "always_on")
        assertEquals(Statsig.logger.events[0].metadata!!["gateValue"], "true")
        assertEquals(Statsig.logger.events[0].metadata!!["ruleID"], "always_on_rule_id")
        assertEquals(Statsig.logger.events[0].secondaryExposures, arrayOf(
            mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1"),
            mapOf("gate" to "dependent_gate_2", "gateValue" to "true", "ruleID" to "rule_id_2")
        ))

        // validate config exposure
        assertEquals(Statsig.logger.events[3].metadata!!["config"], "test_config")
        assertEquals(Statsig.logger.events[3].metadata!!["ruleID"], "default")
        assertEquals(Statsig.logger.events[3].secondaryExposures, arrayOf(
            mapOf("gate" to "dependent_gate", "gateValue" to "true", "ruleID" to "rule_id_1")
        ))
    }
}
