package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.Assert.*

class StatsigPendingInitTest : IStatsigCallback {

    private lateinit var app: Application
    private var flushedLogs: String = ""
    private var initUser: StatsigUser? = null

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
            statsigNetwork.initialize(any(), any<String>(), any(), any(), any())
        } coAnswers {
            delay(1000L)
            initUser = thirdArg()
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


        coEvery {
            statsigNetwork.apiRetryFailedLogs(any(), any())
        } returns Unit
        coEvery {
            statsigNetwork.apiPostLogs(any(), any(), any())
        } answers {
            flushedLogs = thirdArg<String>()
        }

        Statsig.statsigNetwork = statsigNetwork
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testInitializeBadInput() = runBlocking {
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
    fun testInitialize() {
        Statsig.initializeAsync(
            app,
            "client-111aaa",
            StatsigUser("123"),
            this@StatsigPendingInitTest,
            StatsigOptions(overrideStableID = "custom_stable_id")
        )
        assertFalse(Statsig.checkGate("always_on"))
        assertEquals("fallback", Statsig.getConfig("test_config").getString("string", "fallback"))
    }

    override fun onStatsigUpdateUser() {
        // noop
    }

    override fun onStatsigInitialize() {
        // noop
    }
}
