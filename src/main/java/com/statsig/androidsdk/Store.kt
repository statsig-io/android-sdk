package com.statsig.androidsdk

import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_BY_USER_KEY: String = "Statsig.CACHE_BY_USER"
private const val DEPRECATED_STICKY_USER_EXPERIMENTS_KEY: String = "Statsig.STICKY_USER_EXPERIMENTS"
private const val STICKY_DEVICE_EXPERIMENTS_KEY: String = "Statsig.STICKY_DEVICE_EXPERIMENTS"
private const val LOCAL_OVERRIDES_KEY: String = "Statsig.LOCAL_OVERRIDES"

private data class StickyUserExperiments(
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

private data class DeprecatedStickyUserExperiments(
    @SerializedName("user_id") val userID: String?,
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

@VisibleForTesting
private data class Cache(
    @SerializedName("values") var values: InitializeResponse.SuccessfulInitializeResponse,
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments,
    @SerializedName("userHash") var userHash: String,
    @SerializedName("evaluationTime") var evaluationTime: Long? = System.currentTimeMillis(),
)

internal class Store(
    private val statsigScope: CoroutineScope,
    private val sharedPrefs: SharedPreferences,
    user: StatsigUser,
) {
    private val gson = StatsigUtil.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private var currentUserCacheKey: String
    private var cacheById: ConcurrentHashMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: ConcurrentHashMap<String, APIDynamicConfig>
    private var localOverrides: StatsigOverrides
    private var reason: EvaluationReason

    init {
        currentUserCacheKey = user.getCacheKey()
        cacheById = ConcurrentHashMap()
        currentCache = createEmptyCache()
        stickyDeviceExperiments = ConcurrentHashMap()
        localOverrides = StatsigOverrides.empty()
        reason = EvaluationReason.Uninitialized
    }

    fun syncLoadFromLocalStorage() {
        val cachedResponse = StatsigUtil.syncGetFromSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY)
        val cachedDeviceValues = StatsigUtil.syncGetFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)
        val cachedLocalOverrides = StatsigUtil.syncGetFromSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY)

        if (cachedResponse != null) {
            val type = object : TypeToken<MutableMap<String, Cache>>() {}.type
            try {
                val localCache: Map<String, Cache> = gson.fromJson(cachedResponse, type)
                cacheById = ConcurrentHashMap(localCache)
            } catch (_: Exception) {
                statsigScope.launch(dispatcherProvider.io) {
                    StatsigUtil.removeFromSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY)
                }
            }
        }

        stickyDeviceExperiments = ConcurrentHashMap()
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            try {
                val localSticky: Map<String, APIDynamicConfig> = gson.fromJson(cachedDeviceValues, type)
                stickyDeviceExperiments = ConcurrentHashMap(localSticky)
            } catch (_: Exception) {
                statsigScope.launch(dispatcherProvider.io) {
                    StatsigUtil.removeFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)
                }
            }
        }

        localOverrides = StatsigOverrides.empty()
        if (cachedLocalOverrides != null) {
            try {
                localOverrides = gson.fromJson(cachedLocalOverrides, StatsigOverrides::class.java)
            } catch (_: Exception) {
                statsigScope.launch(dispatcherProvider.io) {
                    StatsigUtil.removeFromSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY)
                }
            }
        }
        currentCache = loadCacheForCurrentUser()
    }

    fun loadAndResetForUser(user: StatsigUser) {
        reason = EvaluationReason.Uninitialized
        currentUserCacheKey = user.getCacheKey()
        currentCache = loadCacheForCurrentUser()
    }

    fun bootstrap(initializeValues: Map<String, Any>, user: StatsigUser) {
        val isValid = BootstrapValidator.isValid(initializeValues, user)
        reason = if (isValid) EvaluationReason.Bootstrap else EvaluationReason.InvalidBootstrap

        try {
            currentCache.values = gson.fromJson(
                gson.toJson(initializeValues),
                InitializeResponse.SuccessfulInitializeResponse::class.java,
            )
            cacheById[currentUserCacheKey] = currentCache
        } catch (e: Exception) {
            // Do Nothing
        }
    }

    private fun loadCacheForCurrentUser(): Cache {
        val cachedValues = cacheById[currentUserCacheKey]
        return if (cachedValues != null) {
            reason = EvaluationReason.Cache
            cachedValues
        } else {
            createEmptyCache()
        }
    }

    fun getLastUpdateTime(user: StatsigUser): Long? {
        val cachedValues = cacheById[user.getCacheKey()]
        if (cachedValues?.userHash != user.toHashString()) {
            return null
        }
        return cachedValues?.values?.time
    }

    fun getPreviousDerivedFields(user: StatsigUser): Map<String, String> {
        val cachedValues = cacheById[user.getCacheKey()]
        if (cachedValues?.userHash != user.toHashString()) {
            return mapOf()
        }
        return cachedValues?.values?.derivedFields ?: mapOf()
    }

    suspend fun save(data: InitializeResponse.SuccessfulInitializeResponse, user: StatsigUser) {
        val cacheKey = user.getCacheKey()
        val cache = cacheById[cacheKey] ?: createEmptyCache()
        cache.values = data
        cache.evaluationTime = System.currentTimeMillis()
        cache.userHash = user.toHashString()
        cacheById[cacheKey] = cache

        if (cacheKey == currentUserCacheKey) {
            currentCache = cache
            reason = if (data.hasUpdates) EvaluationReason.Network else EvaluationReason.NetworkNotModified
        }

        var cacheString = gson.toJson(cacheById)

        // Drop out other users if the cache is getting too big
        if ((cacheString.length / 1024) > 1024/*1 MB*/ && cacheById.size > 1) {
            cacheById = ConcurrentHashMap()
            cacheById[currentUserCacheKey] = currentCache
            cacheString = gson.toJson(cacheById)
        }
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY, cacheString)
    }

    fun checkGate(gateName: String): FeatureGate {
        val overriddenValue = localOverrides.gates[gateName]
        if (overriddenValue != null) {
            return FeatureGate(
                gateName,
                getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overriddenValue,
                "override",
            )
        }

        val hashName = Hashing.getHashedString(gateName, currentCache.values.hashUsed)
        val gate = currentCache.values.featureGates?.get(hashName)
            ?: return FeatureGate(gateName, getEvaluationDetails(false))
        return FeatureGate(gate, getEvaluationDetails(true))
    }

    fun getConfig(configName: String): DynamicConfig {
        val overrideValue = localOverrides.configs[configName]
        if (overrideValue != null) {
            return DynamicConfig(
                configName,
                getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overrideValue,
                "override",
            )
        }

        val hashName = Hashing.getHashedString(configName, currentCache.values.hashUsed)
        val data = getConfigData(hashName)
        return hydrateDynamicConfig(configName, getEvaluationDetails(data != null), data)
    }

    private fun getConfigData(hashedConfigName: String): APIDynamicConfig? {
        val values = currentCache.values
        if (
            values.configs == null ||
            !values.configs.containsKey(hashedConfigName)
        ) {
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
                "override",
            )
        }

        val hashName = Hashing.getHashedString(experimentName, currentCache.values.hashUsed)
        val latestValue = currentCache.values.configs?.get(hashName)
        val details = getEvaluationDetails(latestValue != null)
        val finalValue = getPossiblyStickyValue(experimentName, latestValue, keepDeviceValue, details, false)
        return hydrateDynamicConfig(
            experimentName,
            details,
            finalValue,
        )
    }

    fun getLayer(client: StatsigClient?, layerName: String, keepDeviceValue: Boolean = false): Layer {
        val overrideValue = localOverrides.layers[layerName]
        if (overrideValue != null) {
            return Layer(
                null,
                layerName,
                getEvaluationDetails(false, EvaluationReason.LocalOverride),
                overrideValue,
                "override",
            )
        }

        val hashedLayerName = Hashing.getHashedString(layerName, currentCache.values.hashUsed)
        val latestValue = currentCache.values.layerConfigs?.get(hashedLayerName)
        val details = getEvaluationDetails(latestValue != null)
        val finalValue = getPossiblyStickyValue(layerName, latestValue, keepDeviceValue, details, true)
        return if (finalValue != null) {
            Layer(client, layerName, finalValue, details)
        } else {
            Layer(client, layerName, details)
        }
    }

    internal fun getGlobalEvaluationDetails(): EvaluationDetails {
        return EvaluationDetails(this.reason, currentCache.evaluationTime ?: System.currentTimeMillis())
    }

    internal fun getEvaluationDetails(
        valueExists: Boolean,
        reasonOverride: EvaluationReason? = null,
    ): EvaluationDetails {
        if (valueExists) {
            return getGlobalEvaluationDetails()
        }

        var reason = EvaluationReason.Unrecognized
        if (this.reason == EvaluationReason.Uninitialized) {
            reason = EvaluationReason.Uninitialized
        }

        return EvaluationDetails(
            reason = reasonOverride ?: reason,
            time = System.currentTimeMillis(),
        )
    }

    // Sticky Logic: https://gist.github.com/daniel-statsig/3d8dfc9bdee531cffc96901c1a06a402
    private fun getPossiblyStickyValue(
        name: String,
        latestValue: APIDynamicConfig?,
        keepDeviceValue: Boolean,
        details: EvaluationDetails,
        isLayer: Boolean,
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
    }

    fun overrideConfig(configName: String, value: Map<String, Any>) {
        localOverrides.configs[configName] = value
    }

    fun overrideLayer(layerName: String, value: Map<String, Any>) {
        localOverrides.layers[layerName] = value
    }

    fun removeOverride(name: String) {
        localOverrides.configs.remove(name)
        localOverrides.gates.remove(name)
        localOverrides.layers.remove(name)
    }

    fun removeAllOverrides() {
        localOverrides = StatsigOverrides.empty()
    }

    suspend fun saveOverridesToLocalStorage() {
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun getAllOverrides(): StatsigOverrides {
        return StatsigOverrides(
            localOverrides.gates,
            localOverrides.configs,
            localOverrides.layers,
        )
    }

    fun getCurrentCacheValuesAndEvaluationReason(): ExternalInitializeResponse {
        return ExternalInitializeResponse(gson.toJson(currentCache.values), getEvaluationDetails(true))
    }

    private fun hydrateDynamicConfig(
        name: String,
        details: EvaluationDetails,
        config: APIDynamicConfig?,
    ): DynamicConfig {
        return if (config != null) {
            DynamicConfig(name, config, details)
        } else {
            DynamicConfig(name, details)
        }
    }

    private fun createEmptyCache(): Cache {
        val emptyInitResponse =
            InitializeResponse.SuccessfulInitializeResponse(mapOf(), mapOf(), mapOf(), false, null, 0, mapOf())
        val emptyStickyUserExperiments = StickyUserExperiments(mutableMapOf())
        return Cache(emptyInitResponse, emptyStickyUserExperiments, "", System.currentTimeMillis())
    }

    // save and remove sticky values in memory only
    // a separate coroutine will persist them to storage
    private fun removeStickyValue(expName: String) {
        val expNameHash = Hashing.getHashedString(expName, currentCache.values.hashUsed)
        currentCache.stickyUserExperiments.experiments.remove(expNameHash)
        stickyDeviceExperiments.remove(expNameHash)
    }

    // save and remove sticky values in memory only
    // a separate coroutine will persist them to storage
    private fun attemptToSaveStickyValue(expName: String, latestValue: APIDynamicConfig?) {
        if (latestValue == null) {
            return
        }

        val expNameHash = Hashing.getHashedString(expName, currentCache.values.hashUsed)
        if (latestValue.isExperimentActive && latestValue.isUserInExperiment) {
            if (latestValue.isDeviceBased) {
                stickyDeviceExperiments[expNameHash] = latestValue
            } else {
                currentCache.stickyUserExperiments.experiments[expNameHash] = latestValue
            }
        }
    }

    private fun getStickyValue(expName: String): APIDynamicConfig? {
        val hashName = Hashing.getHashedString(expName, currentCache.values.hashUsed)
        return currentCache.stickyUserExperiments.experiments[hashName] ?: stickyDeviceExperiments[hashName]
    }

    suspend fun persistStickyValues() {
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, CACHE_BY_USER_KEY, gson.toJson(cacheById))
        StatsigUtil.saveStringToSharedPrefs(
            sharedPrefs,
            STICKY_DEVICE_EXPERIMENTS_KEY,
            gson.toJson(stickyDeviceExperiments),
        )
    }
}
