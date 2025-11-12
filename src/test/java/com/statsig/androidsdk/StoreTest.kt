package com.statsig.androidsdk

import android.app.Application
import io.mockk.coVerify
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private lateinit var app: Application
    private val userJkw = StatsigUser(userID = "jkw")
    private val userDloomb = StatsigUser(userID = "dloomb")
    private val userTore = StatsigUser(userID = "tore")

    private lateinit var coroutineScope: TestScope

    @Before
    internal fun setup() {
        coroutineScope = TestScope(TestUtil.mockDispatchers())
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockHashing()
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    private fun getInitValue(
        value: String,
        inExperiment: Boolean = false,
        active: Boolean = false,
        hasUpdates: Boolean = true
    ): InitializeResponse.SuccessfulInitializeResponse =
        InitializeResponse.SuccessfulInitializeResponse(
            featureGates =
            mutableMapOf(
                "gate!" to
                    APIFeatureGate(
                        "gate",
                        inExperiment,
                        "id",
                        null,
                        arrayOf()
                    )
            ),
            configs =
            mutableMapOf(
                "config!" to
                    APIDynamicConfig(
                        "config!",
                        mutableMapOf("key" to value),
                        "id",
                        null,
                        arrayOf()
                    ),
                "exp!" to
                    APIDynamicConfig(
                        "exp!",
                        mutableMapOf("key" to value),
                        "id",
                        null,
                        arrayOf(),
                        isDeviceBased = false,
                        isUserInExperiment = inExperiment,
                        isExperimentActive = active
                    ),
                "device_exp!" to
                    APIDynamicConfig(
                        "device_exp!",
                        mutableMapOf("key" to value),
                        "id",
                        null,
                        arrayOf(),
                        isDeviceBased = true,
                        isUserInExperiment = inExperiment,
                        isExperimentActive = active
                    ),
                "exp_non_stick!" to
                    APIDynamicConfig(
                        "non_stick_exp!",
                        mutableMapOf("key" to value),
                        "id",
                        null,
                        arrayOf(),
                        isDeviceBased = false,
                        isUserInExperiment = inExperiment,
                        isExperimentActive = active
                    )
            ),
            layerConfigs = null,
            hasUpdates = hasUpdates,
            time = 1621637839,
            derivedFields = mapOf()
        )

    @Test
    fun testCacheById() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestSharedPrefs(app),
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)

        store.resetUser(userDloomb)
        store.loadCacheForCurrentUser()
        store.save(getInitValue("v0", inExperiment = false, active = true), userDloomb)

        store.resetUser(userJkw)
        store.loadCacheForCurrentUser()
        assertEquals(true, store.checkGate("gate").getValue())

        store.resetUser(userDloomb)
        store.loadCacheForCurrentUser()
        assertEquals(false, store.checkGate("gate").getValue())
    }

    @Test
    fun testParsingNumberPrecision() = runBlocking {
        var network: StatsigNetwork = TestUtil.mockNetwork(
            dynamicConfigs = mapOf(
                "long!" to
                    APIDynamicConfig(
                        "long!",
                        mutableMapOf("key" to Long.MAX_VALUE),
                        "id",
                        null,
                        arrayOf()
                    ),
                "double!" to
                    APIDynamicConfig(
                        "double!",
                        mutableMapOf("key" to Double.MIN_VALUE),
                        "id",
                        null,
                        arrayOf()
                    )
            ),
            time = 1621637839,
            hasUpdates = true
        )
        TestUtil.startStatsigAndWait(app, userJkw, StatsigOptions(), network)
        assertEquals(Long.MAX_VALUE, Statsig.getConfig("long").getLong("key", 0L))
        assertEquals(Double.MIN_VALUE, Statsig.getConfig("double").getDouble("key", 0.0), 0.0)
        network = TestUtil.mockBrokenNetwork()
        TestUtil.startStatsigAndWait(app, userJkw, StatsigOptions(loadCacheAsync = true), network)
        assertEquals(
            EvaluationReason.Cache,
            Statsig.getConfig("long").getEvaluationDetails().reason
        )
        assertEquals(Long.MAX_VALUE, Statsig.getConfig("long").getLong("key", 0L))
        assertEquals(Double.MIN_VALUE, Statsig.getConfig("double").getDouble("key", 0.0), 0.0)
    }

    @Test
    fun testEvaluationReasons() = runBlocking {
        val sharedPrefs = TestUtil.getTestSharedPrefs(app)
        var store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )

        // check before there is any value
        var exp = store.getExperiment("exp", false)
        var fakeConfig = store.getExperiment("config_fake", false)
        assertEquals(EvaluationReason.Uninitialized, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Uninitialized, fakeConfig.getEvaluationDetails().reason)

        store.syncLoadFromLocalStorage()

        // save value with no updates - unrecognized
        store.save(getInitValue("v0", hasUpdates = false), userJkw)
        store.persistStickyValues()
        exp = store.getExperiment("exp", false)
        assertEquals(EvaluationReason.Unrecognized, exp.getEvaluationDetails().reason)

        // save some value from "network" and check again
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)
        store.persistStickyValues()

        exp = store.getExperiment("exp", false)
        fakeConfig = store.getExperiment("config_fake", false)
        val time = exp.getEvaluationDetails().time
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Unrecognized, fakeConfig.getEvaluationDetails().reason)

        // we've saved values now, and got a successful network response with no updates
        // which is networknotmodified
        store.save(getInitValue("v0", hasUpdates = false), userJkw)
        exp = store.getExperiment("exp", false)
        assertEquals(EvaluationReason.NetworkNotModified, exp.getEvaluationDetails().reason)

        // re-initialize store, and check before any "network" value is saved
        // wait 1 sec before reinitializing so that we can check
        // the evaluation time in details has not advanced
        Thread.sleep(1000)
        store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        exp = store.getExperiment(
            "exp",
            true
        ) // set keepDeviceValue to true so we can check for sticky value next
        store.persistStickyValues()

        assertEquals(EvaluationReason.Cache, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Unrecognized, fakeConfig.getEvaluationDetails().reason)
        assertEquals(time, exp.getEvaluationDetails().time)

        // re-initialize and check the previously saved sticky value
        store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        store.save(getInitValue("v1", inExperiment = true, active = true), userJkw)
        store.persistStickyValues()

        exp = store.getExperiment("exp", true)
        assertEquals("v0", exp.getString("key", "default"))
        assertEquals(EvaluationReason.Sticky, exp.getEvaluationDetails().reason)
    }

    @Test
    fun testConfigNameNotHashed() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestSharedPrefs(app),
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)

        val config = store.getExperiment("config", false)
        assertEquals("config", config.getName())
        val configFake = store.getExperiment("config_fake", false)
        assertEquals("config_fake", configFake.getName())
    }

    @Test
    fun testStickyBucketing() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestSharedPrefs(app),
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)

        // getting values with keepDeviceValue = false first
        var config = store.getExperiment("config", false)
        var exp = store.getExperiment("exp", false)
        var deviceExp = store.getExperiment("device_exp", false)
        var nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v0", config.getString("key", ""))
        assertEquals("v0", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v0", nonStickExp.getString("key", ""))

        // update values, and then get with flag set to true. All values should update
        store.save(getInitValue("v1", inExperiment = true, active = true), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // update values again. Now some values should be sticky except the non-sticky ones
        store.save(getInitValue("v2", inExperiment = true, active = true), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v2", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v2", nonStickExp.getString("key", ""))

        // update the experiments so that the user is no longer in experiments, should still be
        // sticky for the right ones
        store.save(getInitValue("v3", inExperiment = false, active = true), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v3", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v3", nonStickExp.getString("key", ""))

        // update the experiments to no longer be active, values should update
        store.save(getInitValue("v4", inExperiment = false, active = false), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v4", config.getString("key", ""))
        assertEquals("v4", exp.getString("key", ""))
        assertEquals("v4", deviceExp.getString("key", ""))
        assertEquals("v4", nonStickExp.getString("key", ""))
    }

    @Test
    fun testInactiveExperimentStickyBehavior() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestSharedPrefs(app),
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.save(getInitValue("v0", inExperiment = true, active = false), userJkw)

        var exp = store.getExperiment("exp", true)
        assertEquals("v0", exp.getString("key", ""))

        store.save(getInitValue("v1", inExperiment = true, active = false), userJkw)

        exp = store.getExperiment("exp", true)
        assertEquals("v1", exp.getString("key", ""))
    }

    @Test
    fun testStickyBehaviorWhenResettingUser() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestSharedPrefs(app),
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)

        // getting values with keepDeviceValue = false first
        var config = store.getExperiment("config", false)
        var exp = store.getExperiment("exp", false)
        var deviceExp = store.getExperiment("device_exp", false)
        var nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v0", config.getString("key", ""))
        assertEquals("v0", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v0", nonStickExp.getString("key", ""))

        // update values, and then get with flag set to true. All values should update
        store.save(getInitValue("v1", inExperiment = true, active = true), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // reset to a different user. Only device exp should stick
        store.resetUser(userTore)
        store.loadCacheForCurrentUser()
        store.save(getInitValue("v2", inExperiment = true, active = true), userTore)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v2", config.getString("key", ""))
        assertEquals("v2", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v2", nonStickExp.getString("key", ""))
    }

    @Test
    fun testStickyBehaviorAcrossSessions() = runBlocking {
        val sharedPrefs = TestUtil.getTestSharedPrefs(app)
        var store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        val v0Values = getInitValue("v0", inExperiment = true, active = true)
        store.save(v0Values, userJkw)
        store.persistStickyValues()

        var config = store.getExperiment("config", true)
        var exp = store.getExperiment("exp", true)
        var deviceExp = store.getExperiment("device_exp", true)
        var nonStickExp = store.getExperiment("exp_non_stick", false)
        store.persistStickyValues()
        assertEquals("v0", config.getString("key", ""))
        assertEquals("v0", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v0", nonStickExp.getString("key", ""))

        // Reinitialize, same user ID, should keep sticky values
        store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        val configs = v0Values.configs as MutableMap<String, APIDynamicConfig>

        configs["exp!"] = newConfigUpdatingValue(configs["exp!"]!!, mapOf("key" to "v0_alt"))
        configs["device_exp!"] =
            newConfigUpdatingValue(configs["device_exp!"]!!, mapOf("key" to "v0_alt"))
        store.save(v0Values, userJkw)
        store.persistStickyValues()

        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        assertTrue(
            "Should still get original v0 not v0_alt",
            exp.getString("key", "") == "v0" &&
                deviceExp.getString("key", "") == "v0"
        )

        // Re-create store with a different user ID, update the values, user should still get sticky
        // value for device and only device
        store =
            Store(
                coroutineScope,
                sharedPrefs,
                userTore,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        store.save(getInitValue("v1", inExperiment = true, active = true), userTore)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // Re-create store with the original user ID, check that sticky values are persisted
        store =
            Store(
                coroutineScope,
                sharedPrefs,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()
        store.save(getInitValue("v2", inExperiment = true, active = true), userJkw)

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v2", config.getString("key", ""))
        assertEquals("v0", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v2", nonStickExp.getString("key", ""))

        // Reset sticky exp
        exp = store.getExperiment("exp", false)
        assertEquals("v2", exp.getString("key", ""))
    }

    @Test
    fun testStoreUpdatesOnlyWithUpdatedValues() = runBlocking {
        val networkTime = 123456789L
        var network: StatsigNetwork = TestUtil.mockNetwork(
            dynamicConfigs = mapOf(
                "test_config!" to APIDynamicConfig(
                    "test_config!",
                    mutableMapOf("key" to "first"),
                    "default"
                )
            ),
            time = networkTime,
            hasUpdates = true
        )
        val user = StatsigUser("123")
        TestUtil.startStatsigAndWait(app, user, StatsigOptions(), network)
        coVerify {
            network.initialize(any(), any(), null, any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(networkTime, Statsig.client.getStore().getLastUpdateTime(user))
        assertEquals("first", Statsig.getConfig("test_config").getString("key", ""))
        network = TestUtil.mockNetwork(
            dynamicConfigs = mapOf(
                "test_config!" to APIDynamicConfig(
                    "test_config!",
                    mutableMapOf("key" to "second"),
                    "default"
                )
            ),
            time = networkTime - 1,
            hasUpdates = false
        )
        Statsig.client.statsigNetwork = network
        runBlocking { Statsig.updateUser(user) }
        coVerify {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = networkTime,
                metadata = any(),
                coroutineScope = any(),
                contextType = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
                fullChecksum = any()
            )
        }
        assertEquals(networkTime, Statsig.client.getStore().getLastUpdateTime(user))
        assertEquals("first", Statsig.getConfig("test_config").getString("key", ""))
    }

    @Test
    fun testStoreUpdatesWithRefreshCache() = runBlocking {
        val networkTime = 123456789L
        var network: StatsigNetwork = TestUtil.mockNetwork(
            dynamicConfigs = mapOf(
                "test_config!" to APIDynamicConfig(
                    "test_config!",
                    mutableMapOf("key" to "first"),
                    "default"
                )
            ),
            time = networkTime,
            hasUpdates = true
        )
        val user = StatsigUser("123")
        TestUtil.startStatsigAndWait(app, user, StatsigOptions(), network)
        coVerify {
            network.initialize(any(), any(), null, any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(networkTime, Statsig.client.getStore().getLastUpdateTime(user))
        assertEquals("first", Statsig.getConfig("test_config").getString("key", ""))
        network = TestUtil.mockNetwork(
            dynamicConfigs = mapOf(
                "test_config!" to APIDynamicConfig(
                    "test_config!",
                    mutableMapOf("key" to "second"),
                    "default"
                )
            ),
            time = networkTime + 1,
            hasUpdates = true
        )
        Statsig.client.statsigNetwork = network
        runBlocking { Statsig.refreshCache() }
        coVerify {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = networkTime,
                metadata = any(),
                coroutineScope = any(),
                contextType = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
                fullChecksum = any()
            )
        }
        assertEquals(networkTime + 1, Statsig.client.getStore().getLastUpdateTime(user))
        assertEquals("second", Statsig.getConfig("test_config").getString("key", ""))
    }

    private fun newConfigUpdatingValue(
        config: APIDynamicConfig,
        newValue: Map<String, Any>
    ): APIDynamicConfig = APIDynamicConfig(
        config.name,
        newValue,
        config.ruleID,
        config.groupName,
        config.secondaryExposures,
        isDeviceBased = config.isDeviceBased,
        isUserInExperiment = config.isUserInExperiment,
        isExperimentActive = config.isExperimentActive
    )
}
