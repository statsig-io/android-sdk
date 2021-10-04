package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class StatsigTest {

    private lateinit var app: Application

    @Before
    internal fun setup() {
        app = mockk()
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

        Dispatchers.setMain(TestCoroutineDispatcher())

        mockkObject(StatsigUtil)
        every {
            StatsigUtil.getHashedString(any())
        } answers {
            firstArg<String>() + "!"
        }

        val statsigNetwork = mockkClass(StatsigNetwork::class)
        coEvery {
            statsigNetwork.initialize(any(), any(), any(), any(), any())
        } returns
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
        coEvery {
            statsigNetwork.apiRetryFailedLogs(any(), any())
        } returns Unit

        Statsig.statsigNetwork = statsigNetwork
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

        val config = Statsig.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals("test_config", config.getName())
        assertEquals(42, config.getInt("number", 0))
        assertEquals("default string instead", config.getString("otherNumber", "default string instead"))
        assertEquals("default", Statsig.getConfig("test_config").getRuleID())

        val invalidConfig = Statsig.getConfig("not_a_valid_config")
        assertEquals("", invalidConfig.getRuleID())
        assertEquals("not_a_valid_config", invalidConfig.getName())

        val exp = Statsig.getExperiment("exp")
        assertEquals("exp", exp.getName())
        assertEquals(42, exp.getInt("number", 0))

        assertEquals(Statsig.logger.events.count(), 7)

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

        // validate exp exposure
        assertEquals(Statsig.logger.events[6].metadata!!["config"], "exp")
        assertEquals(Statsig.logger.events[6].metadata!!["ruleID"], "exp_rule")
        assertEquals(Statsig.logger.events[6].secondaryExposures, arrayOf())
    }
}
