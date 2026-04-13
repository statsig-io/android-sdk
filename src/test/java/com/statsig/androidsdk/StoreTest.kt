package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        runBlocking {
            TestUtil.getTestKeyValueStore(app).clearAll()
        }
    }

    @After
    internal fun tearDown() {
        runBlocking {
            TestUtil.getTestKeyValueStore(app).clearAll()
        }
        TestUtil.reset()
        unmockkAll()
    }

    private fun getInitValue(
        value: String,
        inExperiment: Boolean = false,
        active: Boolean = false,
        hasUpdates: Boolean = true,
        hasSdkFlags: Boolean = false,
        hasSdkConfigs: Boolean = false
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
            derivedFields = mapOf(),
            sdkFlags = if (hasSdkFlags) mutableMapOf("sdk_flag" to true) else null,
            sdkConfigs = if (hasSdkConfigs) {
                mutableMapOf(
                    "sdk_config_string" to "expectedConfig",
                    "sdk_config_number" to 100
                )
            } else {
                null
            }
        )

    @Test
    fun testCacheById() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestKeyValueStore(app),
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
    fun testLegacyCacheLoadsAndNextSaveUsesPerUserCompactFormat() = runBlocking {
        val storage = TestUtil.getTestKeyValueStore(app)
        val legacyCache = mapOf(
            "${userJkw.getCacheKey()}:client-apikey" to mapOf(
                "values" to getInitValue("legacy"),
                "stickyUserExperiments" to mapOf("values" to mapOf<String, APIDynamicConfig>()),
                "userHash" to userJkw.toHashString(StatsigUtil.getOrBuildGson()),
                "evaluationTime" to 123L
            )
        )

        storage.writeValue(
            "ondiskvaluecache",
            "Statsig.CACHE_BY_USER",
            StatsigUtil.getOrBuildGson().toJson(legacyCache)
        )

        val store =
            Store(
                coroutineScope,
                storage,
                userJkw,
                "client-apikey",
                StatsigOptions(),
                StatsigUtil.getOrBuildGson()
            )
        store.syncLoadFromLocalStorage()

        assertEquals("legacy", store.getConfig("config").getString("key", ""))

        store.save(getInitValue("network"), userJkw)

        val fullCacheKey = "${userJkw.toHashString(StatsigUtil.getOrBuildGson())}:client-apikey"
        val persisted = storage.readValueSync(
            TestUtil.getPerUserCacheStoreName(fullCacheKey),
            TestUtil.getPerUserCacheStorageKey(fullCacheKey)
        )
        assertThat(persisted).contains("\"response_format\":\"init-v2\"")
        assertThat(persisted).contains("\"dynamic_configs\"")
        assertThat(persisted).contains("\"stickyUserExperiments\"")
        assertThat(persisted).doesNotContain("\"secondary_exposures\"")
        assertThat(storage.readValueSync("ondiskvaluecache", "Statsig.CACHE_BY_USER")).isNull()
    }

    @Test
    fun testCacheKeyMappingWritesFromMultipleStoresAreMerged() = runBlocking {
        val storage = InMemoryKeyValueStorage()
        val gson = StatsigUtil.getOrBuildGson()
        val sdkKeyA = "client-apikey-a"
        val sdkKeyB = "client-apikey-b"
        val userA = StatsigUser("shared").apply { custom = mapOf("version" to "a") }
        val userB = StatsigUser("shared").apply { custom = mapOf("version" to "b") }

        val storeA = Store(coroutineScope, storage, userA, sdkKeyA, StatsigOptions(), gson)
        val storeB = Store(coroutineScope, storage, userB, sdkKeyB, StatsigOptions(), gson)
        storeA.syncLoadFromLocalStorage()
        storeB.syncLoadFromLocalStorage()

        storeA.save(getInitValue("a"), userA)
        storeB.save(getInitValue("b"), userB)

        val userAWithNewFullCacheKey =
            StatsigUser("shared").apply { custom = mapOf("version" to "a2") }
        val userBWithNewFullCacheKey =
            StatsigUser("shared").apply { custom = mapOf("version" to "b2") }

        val reloadedStoreA =
            Store(
                coroutineScope,
                storage,
                userAWithNewFullCacheKey,
                sdkKeyA,
                StatsigOptions(),
                gson
            )
        val reloadedStoreB =
            Store(
                coroutineScope,
                storage,
                userBWithNewFullCacheKey,
                sdkKeyB,
                StatsigOptions(),
                gson
            )

        reloadedStoreA.syncLoadFromLocalStorage()
        reloadedStoreB.syncLoadFromLocalStorage()

        assertThat(reloadedStoreA.getConfig("config").getString("key", "")).isEqualTo("a")
        assertThat(reloadedStoreB.getConfig("config").getString("key", "")).isEqualTo("b")
    }

    @Test
    fun testLegacyCacheKeyMappingLoadsFromSingleBlob() = runBlocking {
        val storage = InMemoryKeyValueStorage()
        val gson = StatsigUtil.getOrBuildGson()
        val legacyUser =
            StatsigUser("same-scoped-key").apply { custom = mapOf("version" to "one") }
        val currentUser =
            StatsigUser("same-scoped-key").apply { custom = mapOf("version" to "two") }
        val scopedCacheKey = "${legacyUser.getCacheKey()}:client-apikey"
        val legacyFullCacheKey = "${legacyUser.toHashString(gson)}:client-apikey"
        val currentFullCacheKey = "${currentUser.toHashString(gson)}:client-apikey"

        storage.writeValue(
            "ondiskvaluecache",
            "Statsig.CACHE_KEY_MAPPING",
            gson.toJson(mapOf(scopedCacheKey to legacyFullCacheKey))
        )

        val store =
            Store(coroutineScope, storage, currentUser, "client-apikey", StatsigOptions(), gson)
        store.syncLoadFromLocalStorage()

        assertThat(store.getFullUserCacheKeyForTesting(scopedCacheKey))
            .isEqualTo(legacyFullCacheKey)

        store.save(getInitValue("new"), currentUser)

        val persistedMapping = gson.fromJson(
            storage.readValue("ondiskvaluecache", "Statsig.CACHE_KEY_MAPPING"),
            Map::class.java
        )
        val persistedMappingEntry = persistedMapping[scopedCacheKey] as Map<*, *>
        assertThat(persistedMappingEntry["fullUserCacheKey"]).isEqualTo(currentFullCacheKey)
        assertThat(persistedMappingEntry["lastUsedAt"]).isNotNull()
    }

    @Test
    fun testReplacingCacheKeyMappingCleansUpOldUnreferencedUserCacheAsync() = runBlocking {
        val storage = InMemoryKeyValueStorage()
        val gson = StatsigUtil.getOrBuildGson()
        val userV1 = StatsigUser("same-scoped-key").apply { custom = mapOf("version" to "one") }
        val userV2 = StatsigUser("same-scoped-key").apply { custom = mapOf("version" to "two") }
        val oldFullCacheKey = "${userV1.toHashString(gson)}:client-apikey"
        val newFullCacheKey = "${userV2.toHashString(gson)}:client-apikey"
        val scopedCacheKey = "${userV1.getCacheKey()}:client-apikey"

        val store = Store(coroutineScope, storage, userV1, "client-apikey", StatsigOptions(), gson)
        store.syncLoadFromLocalStorage()
        store.save(getInitValue("old"), userV1)

        store.resetUser(userV2)
        store.loadCacheForCurrentUserAsync()
        store.save(getInitValue("new"), userV2)

        store.awaitCacheMaintenance()

        assertThat(store.getFullUserCacheKeyForTesting(scopedCacheKey)).isEqualTo(newFullCacheKey)
        assertThat(
            storage.readValue(
                TestUtil.getPerUserCacheStoreName(newFullCacheKey),
                TestUtil.getPerUserCacheStorageKey(newFullCacheKey)
            )
        ).isNotNull()
        assertThat(
            storage.readValue(
                TestUtil.getPerUserCacheStoreName(oldFullCacheKey),
                TestUtil.getPerUserCacheStorageKey(oldFullCacheKey)
            )
        ).isNull()
    }

    @Test
    fun testCacheKeyMappingEvictionUsesGlobalRegistry() = runBlocking {
        val storage = InMemoryKeyValueStorage()
        val gson = StatsigUtil.getOrBuildGson()
        val store =
            Store(
                coroutineScope,
                storage,
                StatsigUser("user_0"),
                "client-apikey",
                StatsigOptions(),
                gson
            )

        for (i in 0..10) {
            val user = StatsigUser("user_$i")
            store.resetUser(user)
            store.loadCacheForCurrentUserAsync()
            store.save(getInitValue("v$i"), user)
        }

        store.awaitCacheMaintenance()

        assertThat(store.getCacheKeyMappingSizeForTesting()).isEqualTo(10)
        assertThat(
            store.getFullUserCacheKeyForTesting(
                "${StatsigUser("user_10").getCacheKey()}:client-apikey"
            )
        ).isNotNull()
    }

    @Test
    fun testBootstrapMetadataPersistsAcrossNetworkSaveAndReload() = runBlocking {
        val storage = TestUtil.getTestKeyValueStore(app)
        val bootstrapValues = TestUtil.makeBootstrapInitializeValues(
            user = userJkw,
            response = TestUtil.makeInitializeResponse(time = 123L),
            sdkInfo = mapOf(
                "sdkType" to "ios-react-native",
                "sdkVersion" to "9.9.9"
            )
        )
        var store = Store(
            coroutineScope,
            storage,
            userJkw,
            "client-apikey",
            StatsigOptions(),
            StatsigUtil.getOrBuildGson()
        )

        store.bootstrap(bootstrapValues, userJkw)

        assertThat(store.getBootstrapMetadata()).isEqualTo(
            BootstrapMetadata(
                generatorSDKInfo = GeneratorSDKInfo(
                    sdkType = "ios-react-native",
                    sdkVersion = "9.9.9"
                ),
                lcut = 123L,
                user = StatsigUser(userID = "jkw")
            )
        )

        store.save(getInitValue("network"), userJkw)

        val fullCacheKey = "${userJkw.toHashString(StatsigUtil.getOrBuildGson())}:client-apikey"
        val persisted = storage.readValueSync(
            TestUtil.getPerUserCacheStoreName(fullCacheKey),
            TestUtil.getPerUserCacheStorageKey(fullCacheKey)
        )
        assertThat(persisted).contains("\"bootstrapMetadata\"")
        assertThat(persisted).contains("\"generatorSDKInfo\"")

        store = Store(
            coroutineScope,
            storage,
            userJkw,
            "client-apikey",
            StatsigOptions(),
            StatsigUtil.getOrBuildGson()
        )
        store.syncLoadFromLocalStorage()

        assertThat(store.getBootstrapMetadata()).isEqualTo(
            BootstrapMetadata(
                generatorSDKInfo = GeneratorSDKInfo(
                    sdkType = "ios-react-native",
                    sdkVersion = "9.9.9"
                ),
                lcut = 123L,
                user = StatsigUser(userID = "jkw")
            )
        )
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
        assertEvalDetails(
            Statsig.getConfig("long").getEvalDetails(),
            EvalSource.Cache,
            EvalReason.Recognized
        )
        assertEquals(Long.MAX_VALUE, Statsig.getConfig("long").getLong("key", 0L))
        assertEquals(Double.MIN_VALUE, Statsig.getConfig("double").getDouble("key", 0.0), 0.0)
    }

    @Test
    fun testEvaluationReasons() = runBlocking {
        val sharedPrefs = TestUtil.getTestKeyValueStore(app)
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
        assertEvalDetails(exp.getEvalDetails(), EvalSource.Uninitialized, EvalReason.Unrecognized)
        assertEvalDetails(
            fakeConfig.getEvalDetails(),
            EvalSource.Uninitialized,
            EvalReason.Unrecognized
        )

        store.syncLoadFromLocalStorage()

        // save value with no updates - unrecognized
        store.save(getInitValue("v0", hasUpdates = false), userJkw)
        store.persistStickyValues()
        exp = store.getExperiment("exp", false)
        assertEvalDetails(
            exp.getEvalDetails(),
            EvalSource.NetworkNotModified,
            EvalReason.Unrecognized
        )

        // save some value from "network" and check again
        store.save(getInitValue("v0", inExperiment = true, active = true), userJkw)
        store.persistStickyValues()

        exp = store.getExperiment("exp", false)
        fakeConfig = store.getExperiment("config_fake", false)
        val receivedAt = exp.getEvalDetails().receivedAt
        assertEvalDetails(exp.getEvalDetails(), EvalSource.Network, EvalReason.Recognized)
        assertEvalDetails(fakeConfig.getEvalDetails(), EvalSource.Network, EvalReason.Unrecognized)

        // we've saved values now, and got a successful network response with no updates
        // which is networknotmodified
        store.save(getInitValue("v0", hasUpdates = false), userJkw)
        exp = store.getExperiment("exp", false)
        assertEvalDetails(
            exp.getEvalDetails(),
            EvalSource.NetworkNotModified,
            EvalReason.Recognized
        )

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

        assertEvalDetails(exp.getEvalDetails(), EvalSource.Cache, EvalReason.Recognized)
        assertEvalDetails(fakeConfig.getEvalDetails(), EvalSource.Network, EvalReason.Unrecognized)
        assertEquals(receivedAt, exp.getEvalDetails().receivedAt)

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
        assertEvalDetails(exp.getEvalDetails(), EvalSource.Network, EvalReason.Sticky)
    }

    @Test
    fun testConfigNameNotHashed() = runBlocking {
        val store =
            Store(
                coroutineScope,
                TestUtil.getTestKeyValueStore(app),
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
                TestUtil.getTestKeyValueStore(app),
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
                TestUtil.getTestKeyValueStore(app),
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
                TestUtil.getTestKeyValueStore(app),
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
        val sharedPrefs = TestUtil.getTestKeyValueStore(app)
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

    @Test
    fun getSdkFlags_returnsFlags() {
        runBlocking {
            val store =
                Store(
                    coroutineScope,
                    TestUtil.getTestKeyValueStore(app),
                    userJkw,
                    "client-apikey",
                    StatsigOptions(),
                    StatsigUtil.getOrBuildGson()
                )
            val data = getInitValue("v0", inExperiment = true, active = true, hasSdkFlags = true)

            store.save(data, userJkw)

            val sdkFlags: Map<String, Any?> = store.getSDKFlags() ?: emptyMap()
            assertThat(sdkFlags).isNotEmpty()
            assertThat(sdkFlags).isEqualTo(data.sdkFlags)
        }
    }

    @Test
    fun getSdkConfigs_returnsConfigs() {
        runBlocking {
            val store =
                Store(
                    coroutineScope,
                    TestUtil.getTestKeyValueStore(app),
                    userJkw,
                    "client-apikey",
                    StatsigOptions(),
                    StatsigUtil.getOrBuildGson()
                )
            val data = getInitValue("v0", inExperiment = true, active = true, hasSdkConfigs = true)

            store.save(data, userJkw)

            val sdkConfigs: Map<String, Any?> = store.getSDKConfigs() ?: emptyMap()
            assertThat(sdkConfigs).isNotEmpty()
            assertThat(sdkConfigs).isEqualTo(data.sdkConfigs)
        }
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
