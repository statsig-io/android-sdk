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
        every {
            app.getSharedPreferences(any(), any())
        } returns TestSharedPreferences()
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
            firstArg()
        }

        val statsigNetwork = mockkClass(StatsigNetwork::class)
        coEvery {
            statsigNetwork.initialize(any(), any(), any(), any(), any())
        } returns
                InitializeResponse(
                    featureGates = mapOf(
                        "always_on" to
                                APIFeatureGate(
                                    "always_on",
                                    true,
                                    "always_on_rule_id"
                                ),
                        "always_off" to
                                APIFeatureGate(
                                    "always_off",
                                    false,
                                    "always_on_rule_id"
                                ),
                    ),
                    configs = mapOf(
                        "test_config" to APIDynamicConfig(
                            "test_config",
                            mapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                            "default"
                        )
                    ),
                    hasUpdates = true,
                    time = 1621637839,
                )
        coEvery {
            statsigNetwork.apiRetryFailedLogs(any(), any(), any())
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

        assertEquals(
            "test",
            Statsig.getConfig("test_config").getString("string", "fallback"),
        )
        assertEquals(
            42,
            Statsig.getConfig("test_config").getInt("number", 0)
        )
        assertEquals(
            "default string instead",
            Statsig.getConfig("test_config")
                .getString("otherNumber", "default string instead"),
        )
        assertEquals("", Statsig.getConfig("not_a_valid_config").getRuleID())
        assertEquals("default", Statsig.getConfig("test_config").getRuleID())
    }
}
