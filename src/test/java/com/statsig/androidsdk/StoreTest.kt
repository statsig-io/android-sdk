package com.statsig.androidsdk

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class StoreTest {
    @Before
    internal fun setup() {
        mockkObject(StatsigUtil)
        every { StatsigUtil.getHashedString(any()) } answers { firstArg<String>() + "!" }
    }

    @After
    internal fun tearDown() {
      unmockkAll()
    }

    private fun getInitValue(
            value: String,
            inExperiment: Boolean = false,
            active: Boolean = false
    ): InitializeResponse {
        return InitializeResponse(
                featureGates =
                mutableMapOf("gate!" to APIFeatureGate("gate", inExperiment, "id", arrayOf())),
                configs =
                        mutableMapOf(
                                "config!" to
                                        APIDynamicConfig(
                                                "config!",
                                                mutableMapOf("key" to value),
                                                "id",
                                                arrayOf(),
                                        ),
                                "exp!" to
                                        APIDynamicConfig(
                                                "exp!",
                                                mutableMapOf("key" to value),
                                                "id",
                                                arrayOf(),
                                                isDeviceBased = false,
                                                isUserInExperiment = inExperiment,
                                                isExperimentActive = active,
                                        ),
                                "device_exp!" to
                                        APIDynamicConfig(
                                                "device_exp!",
                                                mutableMapOf("key" to value),
                                                "id",
                                                arrayOf(),
                                                isDeviceBased = true,
                                                isUserInExperiment = inExperiment,
                                                isExperimentActive = active,
                                        ),
                                "exp_non_stick!" to
                                        APIDynamicConfig(
                                                "non_stick_exp!",
                                                mutableMapOf("key" to value),
                                                "id",
                                                arrayOf(),
                                                isDeviceBased = false,
                                                isUserInExperiment = inExperiment,
                                                isExperimentActive = active,
                                        ),
                        ),
                layerConfigs = null,
                hasUpdates = false,
                time = 1621637839,
        )
    }

    @Test
    fun testCacheById() {
        val store = Store("jkw", null, TestSharedPreferences())
        store.save(getInitValue("v0", inExperiment = true, active = true))
        store.loadAndResetForUser("dloomb", null)
        store.save(getInitValue("v0", inExperiment = false, active = true))

        store.loadAndResetForUser("jkw", null)
        assertEquals(true, store.checkGate("gate").value)

        store.loadAndResetForUser("dloomb", null)
        assertEquals(false, store.checkGate("gate").value)
    }

    @Test
    fun testEvaluationReasons() {
        val sharedPrefs = TestSharedPreferences()
        var store = Store("jkw", null, sharedPrefs)

        // check before there is any value
        var exp = store.getExperiment("exp", false)
        var fakeConfig = store.getExperiment("config_fake", false)
        assertEquals(EvaluationReason.Uninitialized, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Uninitialized, fakeConfig.getEvaluationDetails().reason)

        // save some value from "network" and check again
        store.save(getInitValue("v0", inExperiment = true, active = true))

        exp = store.getExperiment("exp", false)
        fakeConfig = store.getExperiment("config_fake", false)
        var time = exp.getEvaluationDetails().time
        assertEquals(EvaluationReason.Network, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Unrecognized, fakeConfig.getEvaluationDetails().reason)

        // re-initialize store, and check before any "network" value is saved
        Thread.sleep(1000) // wait 1 sec before reinitializing so that we can check the evaluation time in details has not advanced
        store = Store("jkw", null, sharedPrefs)
        exp = store.getExperiment("exp", true) // set keepDeviceValue to true so we can check for sticky value next
        assertEquals(EvaluationReason.Cache, exp.getEvaluationDetails().reason)
        assertEquals(EvaluationReason.Unrecognized, fakeConfig.getEvaluationDetails().reason)
        assertEquals(time, exp.getEvaluationDetails().time)

        // re-initialize and check the previously saved sticky value
        store = Store("jkw", null, sharedPrefs)
        store.save(getInitValue("v1", inExperiment = true, active = true))
        exp = store.getExperiment("exp", true)
        assertEquals("v0", exp.getString("key", "default"))
        assertEquals(EvaluationReason.Sticky, exp.getEvaluationDetails().reason)
    }

    @Test
    fun testStickyUserExperimentMigration() {
        val sharedPrefs = TestSharedPreferences()
        StatsigUtil.saveStringToSharedPrefs(
                sharedPrefs,
                "Statsig.STICKY_USER_EXPERIMENTS",
                "{user_id: \"dloomb\", values: {\"bar!\": {\"value\": {\"foo\": \"aValue\"}}}}"
        )

        val store = Store("dloomb", null, sharedPrefs)
        store.save(TestUtil.makeInitializeResponse(dynamicConfigs = mapOf("bar!" to APIDynamicConfig(
          name = "bar!",
          value = mapOf("foo" to "aNewValue"),
          ruleID = "",
          isExperimentActive = true,
          isUserInExperiment = false
        ))))

        val exp = store.getExperiment("bar", true)
        assertEquals("aValue", exp.getValue()["foo"])
        assertEquals(EvaluationReason.Sticky, exp.getEvaluationDetails().reason)

        val savedVal =
                StatsigUtil.getFromSharedPrefs(sharedPrefs, "Statsig.STICKY_USER_EXPERIMENTS")
        assertNull(savedVal)
    }

    @Test
    fun testConfigNameNotHashed() {
        val store = Store("jkw", null, TestSharedPreferences())
        store.save(getInitValue("v0", inExperiment = true, active = true))

        var config = store.getExperiment("config", false)
        assertEquals("config", config.getName())
        var configFake = store.getExperiment("config_fake", false)
        assertEquals("config_fake", configFake.getName())
    }

    @Test
    fun testStickyBucketing() {
        val store = Store("jkw", null, TestSharedPreferences())
        store.save(getInitValue("v0", inExperiment = true, active = true))

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
        store.save(getInitValue("v1", inExperiment = true, active = true))

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // update values again. Now some values should be sticky except the non-sticky ones
        store.save(getInitValue("v2", inExperiment = true, active = true))

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
        store.save(getInitValue("v3", inExperiment = false, active = true))

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v3", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v3", nonStickExp.getString("key", ""))

        // update the experiments to no longer be active, values should update
        store.save(getInitValue("v4", inExperiment = false, active = false))

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
    fun testInactiveExperimentStickyBehavior() {
      val store = Store("jkw", null, TestSharedPreferences())
      store.save(getInitValue("v0", inExperiment = true, active = false))

      var exp = store.getExperiment("exp", true)
      assertEquals("v0", exp.getString("key", ""))

      store.save(getInitValue("v1", inExperiment = true, active = false))

      exp = store.getExperiment("exp", true)
      assertEquals("v1", exp.getString("key", ""))
    }

    @Test
    fun testStickyBehaviorWhenResettingUser() {
        val store = Store("jkw", null, TestSharedPreferences())
        store.save(getInitValue("v0", inExperiment = true, active = true))

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
        store.save(getInitValue("v1", inExperiment = true, active = true))

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v1", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // reset to a different user. Only device exp should stick
        store.loadAndResetForUser("tore", null)
        store.save(getInitValue("v2", inExperiment = true, active = true))

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
    fun testStickyBehaviorAcrossSessions() {
        val sharedPrefs = TestSharedPreferences()
        var store = Store("jkw", null, sharedPrefs)
        val v0Values = getInitValue("v0", inExperiment = true, active = true)
        store.save(v0Values)

        var config = store.getExperiment("config", true)
        var exp = store.getExperiment("exp", true)
        var deviceExp = store.getExperiment("device_exp", true)
        var nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v0", config.getString("key", ""))
        assertEquals("v0", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v0", nonStickExp.getString("key", ""))

        // Reinitialize, same user ID, should keep sticky values
        store = Store("jkw", null, sharedPrefs)
        val configs = v0Values.configs as MutableMap<String, APIDynamicConfig>

        configs["exp!"] = newConfigUpdatingValue(configs["exp!"]!!, mapOf("key" to "v0_alt"))
        configs["device_exp!"] = newConfigUpdatingValue(configs["device_exp!"]!!, mapOf("key" to "v0_alt"))
        store.save(v0Values)

        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        assertTrue("Should still get original v0 not v0_alt",
          exp.getString("key", "") == "v0"
              && deviceExp.getString("key", "") == "v0"
        )

        // Re-create store with a different user ID, update the values, user should still get sticky
        // value for device and only device
        store = Store("tore", null, sharedPrefs)
        store.save(getInitValue("v1", inExperiment = true, active = true))

        config = store.getExperiment("config", true)
        exp = store.getExperiment("exp", true)
        deviceExp = store.getExperiment("device_exp", true)
        nonStickExp = store.getExperiment("exp_non_stick", false)
        assertEquals("v1", config.getString("key", ""))
        assertEquals("v1", exp.getString("key", ""))
        assertEquals("v0", deviceExp.getString("key", ""))
        assertEquals("v1", nonStickExp.getString("key", ""))

        // Re-create store with the original user ID, check that sticky values are persisted
        store = Store("jkw", null, sharedPrefs)
        store.save(getInitValue("v2", inExperiment = true, active = true))

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

    private fun newConfigUpdatingValue(config: APIDynamicConfig, newValue: Map<String, Any>): APIDynamicConfig {
      return APIDynamicConfig(
        config.name,
        newValue,
        config.ruleID,
        config.secondaryExposures,
        isDeviceBased = config.isDeviceBased,
        isUserInExperiment = config.isUserInExperiment,
        isExperimentActive = config.isExperimentActive,
      )
    }
}
