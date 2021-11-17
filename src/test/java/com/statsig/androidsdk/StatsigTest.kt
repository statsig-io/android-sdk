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

class StatsigTest : IStatsigCallback {

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
        val user = StatsigUser("123")
        user.customIDs = mapOf("random_id" to "abcde")
        Statsig.initializeAsync(
            app,
            "client-dontresolve",
            user,
            this@StatsigTest,
            StatsigOptions(overrideStableID = "custom_stable_id")
        )
    }

    override fun onStatsigUpdateUser() {
        // noop
    }

    override fun onStatsigInitialize() {
        assertEquals(
            Gson().toJson(initUser?.customIDs),
            Gson().toJson(mapOf("random_id" to "abcde"))
        )
        assertTrue(Statsig.checkGate("always_on"))
        assertFalse(Statsig.checkGate("always_off"))
        assertFalse(Statsig.checkGate("not_a_valid_gate_name"))

        val config = Statsig.getConfig("test_config")
        assertEquals("test", config.getString("string", "fallback"))
        assertEquals("test_config", config.getName())
        assertEquals(42, config.getInt("number", 0))
        assertEquals(
            "default string instead",
            config.getString("otherNumber", "default string instead")
        )
        assertEquals("default", Statsig.getConfig("test_config").getRuleID())

        val invalidConfig = Statsig.getConfig("not_a_valid_config")
        assertEquals("", invalidConfig.getRuleID())
        assertEquals("not_a_valid_config", invalidConfig.getName())

        val exp = Statsig.getExperiment("exp")
        assertEquals("exp", exp.getName())
        assertEquals(42, exp.getInt("number", 0))

        Statsig.logEvent("test_event1", 1.toDouble(), mapOf("key" to "value"));
        Statsig.logEvent("test_event2", mapOf("key" to "value2"));
        Statsig.logEvent("test_event3", "1");
        Statsig.shutdown()

        val parsedLogs = Gson().fromJson(flushedLogs, LogEventData::class.java)
        assertEquals(10, parsedLogs.events.count())
        // first 2 are exposures pre initialize() completion
        assertEquals("custom_stable_id", parsedLogs.statsigMetadata.stableID);
        assertEquals("custom_stable_id", Statsig.getStableID())

        // validate gate exposure
        assertEquals(parsedLogs.events[0].eventName, "statsig::gate_exposure")
        assertEquals(parsedLogs.events[0].user!!.userID, "123")
        assertEquals(parsedLogs.events[0].metadata!!["gate"], "always_on")
        assertEquals(parsedLogs.events[0].metadata!!["gateValue"], "true")
        assertEquals(parsedLogs.events[0].metadata!!["ruleID"], "always_on_rule_id")
        assertEquals(
            Gson().toJson(parsedLogs.events[0].secondaryExposures), Gson().toJson(
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    ),
                    mapOf(
                        "gate" to "dependent_gate_2",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_2"
                    )
                )
            )
        )

        // validate config exposure
        assertEquals(parsedLogs.events[3].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[3].user!!.userID, "123")
        assertEquals(parsedLogs.events[3].metadata!!["config"], "test_config")
        assertEquals(parsedLogs.events[3].metadata!!["ruleID"], "default")
        assertEquals(
            Gson().toJson(parsedLogs.events[3].secondaryExposures), Gson().toJson(
                arrayOf(
                    mapOf(
                        "gate" to "dependent_gate",
                        "gateValue" to "true",
                        "ruleID" to "rule_id_1"
                    )
                )
            )
        )

        // validate exp exposure
        assertEquals(parsedLogs.events[6].eventName, "statsig::config_exposure")
        assertEquals(parsedLogs.events[6].user!!.userID, "123")
        assertEquals(parsedLogs.events[6].metadata!!["config"], "exp")
        assertEquals(parsedLogs.events[6].metadata!!["ruleID"], "exp_rule")
        assertEquals(parsedLogs.events[6].secondaryExposures?.count() ?: 1, 0)

        // Validate custom logs
        assertEquals(parsedLogs.events[7].eventName, "test_event1")
        assertEquals(parsedLogs.events[7].user!!.userID, "123")
        assertEquals(parsedLogs.events[7].value, 1.0)
        assertEquals(
            Gson().toJson(parsedLogs.events[7].metadata),
            Gson().toJson(mapOf("key" to "value"))
        )
        assertNull(parsedLogs.events[7].secondaryExposures)

        assertEquals(parsedLogs.events[8].eventName, "test_event2")
        assertEquals(parsedLogs.events[8].user!!.userID, "123")
        assertEquals(parsedLogs.events[8].value, null)
        assertEquals(
            Gson().toJson(parsedLogs.events[8].metadata),
            Gson().toJson(mapOf("key" to "value2"))
        )
        assertNull(parsedLogs.events[8].secondaryExposures)

        assertEquals(parsedLogs.events[9].eventName, "test_event3")
        assertEquals(parsedLogs.events[9].user!!.userID, "123")
        assertEquals(parsedLogs.events[9].value, "1")
        assertNull(parsedLogs.events[9].metadata)
        assertNull(parsedLogs.events[9].secondaryExposures)
        return Unit
    }
}
