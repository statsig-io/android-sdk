package com.statsig.androidsdk

import android.app.Application
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
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
        every {
            StatsigNetwork.apiPost(any(), any(), any(), any(), any())
        } answers {
            val callback = lastArg<(InitializeResponse?, CoroutineDispatcher?) -> Unit>()
            callback(
                InitializeResponse(
                    mapOf(
                        "always_on" to true,
                        "always_off" to false
                    ),
                    mapOf(
                        "test_config" to Config(
                            "test_config",
                            mapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                            "default"
                        )
                    )
                ),
                null
            )
            null
        }
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun beforeInitialize() {
        assertFalse(Statsig.checkGate("any_gate_name"))

        assertNull(Statsig.getConfig("any_config"))
    }

    @Test
    fun initializeBadInput() {
        val cb = object : StatsigCallback {
            override fun onStatsigReady() {
                // no-op for now
            }
        }
        try {
            Statsig.initialize(
                app,
                "secret-111aaa",
                null,
                cb
            )
            fail("Statsig.initialize() did not fail for a non client/test key")
        } catch (expectedException: java.lang.IllegalArgumentException) {
        }
    }

    @Test
    fun testInitialize() {
        var callbackComplete: Boolean = false
        val initializeCallback = object : StatsigCallback {
            override fun onStatsigReady() {
                assertTrue(Statsig.isReady())

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
                assertNull(Statsig.getConfig("not_a_valid_config"))
                callbackComplete = true
            }
        }

        Statsig.initialize(
            app,
            "client-111aaa",
            null,
            initializeCallback
        )
        assertTrue(callbackComplete)
    }
}
