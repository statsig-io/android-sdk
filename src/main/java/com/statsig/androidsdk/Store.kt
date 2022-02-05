package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

private const val CACHE_BY_USER_KEY: String = "Statsig.CACHE_BY_USER"
private const val DEPRECATED_STICKY_USER_EXPERIMENTS_KEY: String = "Statsig.STICKY_USER_EXPERIMENTS"
private const val STICKY_DEVICE_EXPERIMENTS_KEY: String = "Statsig.STICKY_DEVICE_EXPERIMENTS"
private const val LOCAL_OVERRIDES_KEY: String = "Statsig.LOCAL_OVERRIDES"
private const val STATSIG_NULL_USER: String = "Statsig.NULL_USER"

private data class StickyUserExperiments(
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

private data class DeprecatedStickyUserExperiments(
    @SerializedName("user_id") val userID: String?,
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

private data class Cache(
    @SerializedName("values") var values: InitializeResponse,
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments
)

private data class LocalOverrides(
    @SerializedName("gates") var gates: MutableMap<String, Boolean>,
    @SerializedName("configs") var configs: MutableMap<String, APIDynamicConfig>,
)

internal class Store (private var userID: String?, private var customIDs: Map<String, String>?, private val sharedPrefs: SharedPreferences) {
    private val gson = Gson()
    private var cacheById: MutableMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: MutableMap<String, APIDynamicConfig>
    private var localOverrides: LocalOverrides

    init {
        var cachedResponse = StatsigUtil.getFromSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY)
        val cachedDeviceValues = StatsigUtil.getFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)
        val cachedLocalOverrides = StatsigUtil.getFromSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY)

        cacheById = mutableMapOf()

        if (cachedResponse != null) {
            val type = object : TypeToken<MutableMap<String, Cache>>() {}.type
            cacheById = gson.fromJson(cachedResponse, type) ?: cacheById
        }

        stickyDeviceExperiments = mutableMapOf()
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            stickyDeviceExperiments = gson.fromJson(cachedDeviceValues, type) ?: stickyDeviceExperiments
        }

        localOverrides = LocalOverrides(mutableMapOf(), mutableMapOf())
        if (cachedLocalOverrides != null) {
            localOverrides = gson.fromJson(cachedLocalOverrides, LocalOverrides::class.java)
        }

        currentCache = cacheById[getUserStorageID()] ?: createEmptyCache()
        attemptToMigrateDeprecatedStickyUserExperiments()
    }

    fun loadAndResetForUser(newUserID: String?, newCustomIDs: Map<String, String>?) {
        userID = newUserID
        customIDs = newCustomIDs
        currentCache = cacheById[getUserStorageID()] ?: createEmptyCache()
    }

    fun save(data: InitializeResponse) {
        val storageID = getUserStorageID()
        currentCache.values = data;
        cacheById[storageID] = currentCache

        var cacheString = gson.toJson(cacheById)

        // Drop out other users if the cache is getting too big
        if ((cacheString.length / 1024) > 1024/*1 MB*/ && cacheById.size > 1) {
            cacheById = mutableMapOf()
            cacheById[storageID] = currentCache
            cacheString = gson.toJson(cacheById)
        }

        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY, cacheString)
    }

    fun checkGate(gateName: String): APIFeatureGate {
        val overriddenValue = localOverrides.gates[gateName]
        if (overriddenValue != null) {
            return APIFeatureGate(gateName, overriddenValue, "override")
        }

        val hashName = StatsigUtil.getHashedString(gateName)
        val values = currentCache.values
        if (
            values.featureGates == null ||
            !values.featureGates!!.containsKey(hashName)) {
            return APIFeatureGate(gateName, false, "")
        }
        return values.featureGates!![hashName] ?: APIFeatureGate(gateName, false, "")
    }

    fun getConfig(configName: String): DynamicConfig {
        if (localOverrides.configs[configName] != null) {
            return hydrateDynamicConfig(configName, localOverrides.configs[configName])
        }

        val hashName = StatsigUtil.getHashedString(configName)
        val values = currentCache.values
        if (
            values.configs == null ||
            !values.configs!!.containsKey(hashName)) {
            return DynamicConfig(configName)
        }
        var config = values.configs!![hashName]
        return hydrateDynamicConfig(configName, config)
    }

    fun getExperiment(experimentName: String, keepDeviceValue: Boolean): DynamicConfig {
        if (localOverrides.configs[experimentName] != null) {
            return hydrateDynamicConfig(experimentName, localOverrides.configs[experimentName])
        }

        val hashName = StatsigUtil.getHashedString(experimentName)
        val stickyValue = currentCache.stickyUserExperiments.experiments[hashName] ?: stickyDeviceExperiments[hashName]
        val latestValue = currentCache.values.configs?.get(hashName)

        // If flag is false, or experiment is NOT active, simply remove the sticky experiment value, and return the latest value
        if (!keepDeviceValue || latestValue?.isExperimentActive == false) {
            removeStickyValue(hashName)
            return getConfig(experimentName)
        }

        // If sticky value is already in cache, use it
        if (stickyValue != null) {
            return hydrateDynamicConfig(experimentName, stickyValue)
        }

        // If the user has NOT been exposed before, and is in this active experiment, then we save the value as sticky
        if (latestValue != null && latestValue.isExperimentActive && latestValue.isUserInExperiment) {
            if (latestValue?.isDeviceBased) {
                stickyDeviceExperiments[hashName] = latestValue
            } else {
                currentCache.stickyUserExperiments.experiments[hashName] = latestValue
            }
            cacheStickyValues()
        }

        return getConfig(experimentName)
    }

    fun overrideGate(gateName: String, value: Boolean) {
        localOverrides.gates[gateName] = value
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun overrideConfig(configName: String, value: DynamicConfig) {
        localOverrides.configs[configName] = APIDynamicConfig(
            configName,
            value.getValue(),
            value.getRuleID(),
            value.getSecondaryExposures())
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun removeOverride(name: String) {
        localOverrides.configs.remove(name)
        localOverrides.gates.remove(name)
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun removeAllOverrides() {
        localOverrides = LocalOverrides(mutableMapOf(), mutableMapOf())
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun getAllOverrides(): StatsigOverrides {
        return StatsigOverrides(
            localOverrides.gates,
            localOverrides.configs
                .mapValues { value -> hydrateDynamicConfig(value.key, value.value)  })
    }

    private fun hydrateDynamicConfig(name: String, config: APIDynamicConfig?): DynamicConfig {
        return DynamicConfig(
            name,
            config?.value ?: mapOf(),
            config?.ruleID ?: "",
            config?.secondaryExposures ?: arrayOf())
    }

    private fun createEmptyCache(): Cache {
        val emptyGatesAndConfigs = InitializeResponse(mapOf(), mapOf(), false, 0)
        val emptyStickyUserExperiments = StickyUserExperiments(mutableMapOf())
        return Cache(emptyGatesAndConfigs, emptyStickyUserExperiments)
    }

    private fun removeStickyValue(key: String) {
        currentCache.stickyUserExperiments.experiments.remove(key)
        stickyDeviceExperiments.remove(key)
        cacheStickyValues()
    }

    private fun cacheStickyValues() {
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY, gson.toJson(cacheById))
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY, gson.toJson(stickyDeviceExperiments))
    }

    private fun attemptToMigrateDeprecatedStickyUserExperiments() {
        val oldStickyUserExperimentValues =
            StatsigUtil.getFromSharedPrefs(sharedPrefs, DEPRECATED_STICKY_USER_EXPERIMENTS_KEY)
                ?: return
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, DEPRECATED_STICKY_USER_EXPERIMENTS_KEY)

        val stickyUserExperiments = gson.fromJson(oldStickyUserExperimentValues, DeprecatedStickyUserExperiments::class.java)
        if (stickyUserExperiments.userID != userID || currentCache.stickyUserExperiments.experiments.isNotEmpty()) {
            return
        }

        currentCache.stickyUserExperiments = StickyUserExperiments(stickyUserExperiments.experiments)
    }

    private fun getUserStorageID(): String {
        var id = userID ?: STATSIG_NULL_USER
        customIDs?.forEach { (k,v) -> id = "$id|$k:$v" }
        return id
    }
}
