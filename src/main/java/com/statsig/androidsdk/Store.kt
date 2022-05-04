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
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments,
    @SerializedName("evaluationTime") var evaluationTime: Long? = System.currentTimeMillis()
)

internal class Store (private var userID: String?, private var customIDs: Map<String, String>?, private val sharedPrefs: SharedPreferences) {
    private val gson = Gson()
    private var cacheById: MutableMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: MutableMap<String, APIDynamicConfig>
    private var localOverrides: StatsigOverrides
    private var reason: EvaluationReason

    init {
        val cachedResponse = StatsigUtil.getFromSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY)
        val cachedDeviceValues = StatsigUtil.getFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)
        val cachedLocalOverrides = StatsigUtil.getFromSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY)

        cacheById = mutableMapOf()

        if (cachedResponse != null) {
            val type = object : TypeToken<MutableMap<String, Cache>>() {}.type
            try {
                cacheById = gson.fromJson(cachedResponse, type) ?: cacheById
            } catch (_: Exception) {
                StatsigUtil.removeFromSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY)
            }
        }

        stickyDeviceExperiments = mutableMapOf()
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            try {
                stickyDeviceExperiments = gson.fromJson(cachedDeviceValues, type) ?: stickyDeviceExperiments
            } catch (_: Exception) {
                StatsigUtil.removeFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)
            }
        }

        localOverrides = StatsigOverrides(mutableMapOf(), mutableMapOf())
        if (cachedLocalOverrides != null) {
            try {
                localOverrides = gson.fromJson(cachedLocalOverrides, StatsigOverrides::class.java)
            } catch (_: Exception) {
                StatsigUtil.removeFromSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY)
            }
        }
        reason = EvaluationReason.Uninitialized
        currentCache = loadCacheForCurrentUser()
        attemptToMigrateDeprecatedStickyUserExperiments()
    }

    fun loadAndResetForUser(newUserID: String?, newCustomIDs: Map<String, String>?) {
        reason = EvaluationReason.Uninitialized
        userID = newUserID
        customIDs = newCustomIDs
        currentCache = loadCacheForCurrentUser()
    }

    private fun loadCacheForCurrentUser(): Cache {
        val cachedValues = cacheById[getUserStorageID()]
        return if (cachedValues != null) {
            reason = EvaluationReason.Cache
            cachedValues
        } else {
            createEmptyCache()
        }
    }

    fun save(data: InitializeResponse) {
        val storageID = getUserStorageID()
        currentCache.values = data
        currentCache.evaluationTime = System.currentTimeMillis()
        cacheById[storageID] = currentCache
        reason = EvaluationReason.Network

        var cacheString = gson.toJson(cacheById)

        // Drop out other users if the cache is getting too big
        if ((cacheString.length / 1024) > 1024/*1 MB*/ && cacheById.size > 1) {
            cacheById = mutableMapOf()
            cacheById[storageID] = currentCache
            cacheString = gson.toJson(cacheById)
        }

        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY, cacheString)
    }

    fun checkGate(gateName: String): FeatureGate {
        val overriddenValue = localOverrides.gates[gateName]
        if (overriddenValue != null) {
            return FeatureGate(gateName, getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overriddenValue, "override")
        }

        val hashName = StatsigUtil.getHashedString(gateName)
        val gate = currentCache.values.featureGates?.get(hashName) ?:
            return FeatureGate(gateName, getEvaluationDetails(false), false, "")
        return FeatureGate(gate.name, getEvaluationDetails(true), gate.value, gate.ruleID, gate.secondaryExposures)
    }

    fun getConfig(configName: String): DynamicConfig {
        val overrideValue = localOverrides.configs[configName]
        if (overrideValue != null) {
            return DynamicConfig(configName, getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overrideValue, "override")
        }

        val hashName = StatsigUtil.getHashedString(configName)
        val data = getConfigData(hashName)
        return hydrateDynamicConfig(configName, getEvaluationDetails(data != null), data)
    }

    private fun getConfigData(hashedConfigName: String): APIDynamicConfig? {
        val values = currentCache.values
        if (
            values.configs == null ||
            !values.configs.containsKey(hashedConfigName)) {
            return null
        }
        return values.configs[hashedConfigName]
    }

    fun getExperiment(experimentName: String, keepDeviceValue: Boolean): DynamicConfig {
        val overrideValue = localOverrides.configs[experimentName]
        if (overrideValue != null) {
            return DynamicConfig(
                experimentName,
                getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overrideValue,
                "override"
            )
        }

        val hashName = StatsigUtil.getHashedString(experimentName)
        val latestValue = currentCache.values.configs?.get(hashName)
        val details = getEvaluationDetails(latestValue != null)
        val finalValue = getPossiblyStickyValue(experimentName, latestValue, keepDeviceValue, details, false)
        return hydrateDynamicConfig(
            experimentName,
            details,
            finalValue
        )
    }

    fun getLayer(client: StatsigClient, layerName: String, keepDeviceValue: Boolean = false): Layer {
        val hashedLayerName = StatsigUtil.getHashedString(layerName)
        val latestValue = currentCache.values.layerConfigs?.get(hashedLayerName)
        val details = getEvaluationDetails(latestValue != null)
        val finalValue = getPossiblyStickyValue(layerName, latestValue, keepDeviceValue, details, true)
        return Layer(
            client,
            layerName,
            details,
            finalValue?.value ?: mapOf(),
            finalValue?.ruleID ?: "",
            finalValue?.secondaryExposures ?: arrayOf(),
            finalValue?.undelegatedSecondaryExposures ?: arrayOf(),
            finalValue?.isUserInExperiment ?: false,
            finalValue?.isExperimentActive ?: false,
            finalValue?.isDeviceBased ?: false,
            finalValue?.allocatedExperimentName ?: "",
            finalValue?.explicitParameters?.toSet(),
        )
    }

    private fun getEvaluationDetails(valueExists: Boolean, reasonOverride: EvaluationReason? = null): EvaluationDetails {
        return if (valueExists) {
            EvaluationDetails(this.reason, currentCache.evaluationTime ?: System.currentTimeMillis())
        } else {
            val actualReason = reasonOverride ?: if (this.reason == EvaluationReason.Uninitialized) {
                EvaluationReason.Uninitialized
            } else {
                EvaluationReason.Unrecognized
            }
            EvaluationDetails(actualReason, System.currentTimeMillis())
        }
    }

    // Sticky Logic: https://gist.github.com/daniel-statsig/3d8dfc9bdee531cffc96901c1a06a402
    private fun getPossiblyStickyValue(
      name: String,
      latestValue: APIDynamicConfig?,
      keepDeviceValue: Boolean,
      details: EvaluationDetails,
      isLayer: Boolean
    ): APIDynamicConfig? {
        // We don't want sticky behavior. Clear any sticky values and return latest.
        if (!keepDeviceValue) {
            removeStickyValue(name)
            return latestValue
        }

        // If there is no sticky value, save latest as sticky and return latest.
        val stickyValue = getStickyValue(name)
        if (stickyValue == null) {
            attemptToSaveStickyValue(name, latestValue)
            return latestValue
        }

        // Get the latest config value. Layers require a lookup by allocatedExperimentName.
        var latestExperimentValue: APIDynamicConfig? = null
        if (isLayer) {
          stickyValue.allocatedExperimentName?.let {
            latestExperimentValue = currentCache.values.configs?.get(it)
          }
        } else {
          latestExperimentValue = latestValue
        }

        if (latestExperimentValue?.isExperimentActive == true) {
            details.reason = EvaluationReason.Sticky
            return stickyValue
        }

        if (latestValue?.isExperimentActive == true) {
            attemptToSaveStickyValue(name, latestValue)
        } else {
            removeStickyValue(name)
        }

        return latestValue
    }

    fun overrideGate(gateName: String, value: Boolean) {
        localOverrides.gates[gateName] = value
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun overrideConfig(configName: String, value: Map<String, Any>) {
        localOverrides.configs[configName] = value
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun removeOverride(name: String) {
        localOverrides.configs.remove(name)
        localOverrides.gates.remove(name)
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun removeAllOverrides() {
        localOverrides = StatsigOverrides(mutableMapOf(), mutableMapOf())
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun getAllOverrides(): StatsigOverrides {
        return StatsigOverrides(
            localOverrides.gates,
            localOverrides.configs
        )
    }

    private fun hydrateDynamicConfig(name: String, details: EvaluationDetails, config: APIDynamicConfig?): DynamicConfig {
        return DynamicConfig(
            name,
            details,
            config?.value ?: mapOf(),
            config?.ruleID ?: "",
            config?.secondaryExposures ?: arrayOf(),
            config?.isUserInExperiment ?: false,
            config?.isExperimentActive ?: false,
            config?.isDeviceBased ?: false,
            config?.allocatedExperimentName ?: "")
    }

    private fun createEmptyCache(): Cache {
        val emptyInitResponse = InitializeResponse(mapOf(), mapOf(), mapOf(), false, 0)
        val emptyStickyUserExperiments = StickyUserExperiments(mutableMapOf())
        return Cache(emptyInitResponse, emptyStickyUserExperiments, System.currentTimeMillis())
    }

    private fun removeStickyValue(expName: String) {
        val expNameHash = StatsigUtil.getHashedString(expName)
        currentCache.stickyUserExperiments.experiments.remove(expNameHash)
        stickyDeviceExperiments.remove(expNameHash)
        cacheStickyValues()
    }

    private fun attemptToSaveStickyValue(expName: String, latestValue: APIDynamicConfig?) {
        if (latestValue == null) {
            return
        }

        val expNameHash = StatsigUtil.getHashedString(expName)
        if (latestValue.isExperimentActive && latestValue.isUserInExperiment) {
            if (latestValue.isDeviceBased) {
                stickyDeviceExperiments[expNameHash] = latestValue
            } else {
                currentCache.stickyUserExperiments.experiments[expNameHash] = latestValue
            }
            cacheStickyValues()
        }
    }

    private fun getStickyValue(expName: String): APIDynamicConfig? {
        val hashName = StatsigUtil.getHashedString(expName)
        return currentCache.stickyUserExperiments.experiments[hashName] ?: stickyDeviceExperiments[hashName]
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

        try {
            val stickyUserExperiments = gson.fromJson(oldStickyUserExperimentValues, DeprecatedStickyUserExperiments::class.java)
            if (stickyUserExperiments.userID != userID || currentCache.stickyUserExperiments.experiments.isNotEmpty()) {
                return
            }

            currentCache.stickyUserExperiments = StickyUserExperiments(stickyUserExperiments.experiments)
        }
        // no ops, since we've already removed the bad value
        catch (_: Exception) {}
    }

    private fun getUserStorageID(): String {
        var id = userID ?: STATSIG_NULL_USER
        val customIds = customIDs ?: return id

        for ((k, v) in customIds) {
            id = "$id$k:$v"
        }

        return id
    }
}
