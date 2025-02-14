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

internal class Store(private val statsigScope: CoroutineScope, private val sharedPrefs: SharedPreferences, user: StatsigUser, private val sdkKey: String, private val options: StatsigOptions) {
    var reason: EvaluationReason

    private val gson = StatsigUtil.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()

    // Migrating to caching on SDK Key and user ids (Custom IDS + user id)
    private var currentUserCacheKeyDeprecated: String
    private var currentUserCacheKeyV2: String
    private var cacheById: ConcurrentHashMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: ConcurrentHashMap<String, APIDynamicConfig>
    private var localOverrides: StatsigOverrides

    init {
        currentUserCacheKeyDeprecated = user.getCacheKeyDEPRECATED()
        currentUserCacheKeyV2 = this.getScopedCacheKey(user)
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
        loadCacheForCurrentUser()
    }

    fun resetUser(user: StatsigUser) {
        reason = EvaluationReason.Uninitialized
        currentUserCacheKeyDeprecated = user.getCacheKeyDEPRECATED()
        currentUserCacheKeyV2 = this.getScopedCacheKey(user)
    }

    fun bootstrap(initializeValues: Map<String, Any>, user: StatsigUser) {
        val isValid = BootstrapValidator.isValid(initializeValues, user)
        reason = if (isValid) EvaluationReason.Bootstrap else EvaluationReason.InvalidBootstrap

        try {
            currentCache.values = gson.fromJson(
                gson.toJson(initializeValues),
                InitializeResponse.SuccessfulInitializeResponse::class.java,
            )
            cacheById[currentUserCacheKeyV2] = currentCache
        } catch (e: Exception) {
            // Do Nothing
        }
    }

    fun loadCacheForCurrentUser() {
        var cachedValues = cacheById[currentUserCacheKeyV2] ?: cacheById[currentUserCacheKeyDeprecated]
        if (cachedValues != null) {
            reason = EvaluationReason.Cache
            currentCache = cachedValues
        } else {
            currentCache = createEmptyCache()
        }
    }

    fun getLastUpdateTime(user: StatsigUser): Long? {
        var cachedValues = cacheById[this.getScopedCacheKey(user)] ?: cacheById[user.getCacheKeyDEPRECATED()]
        if (cachedValues?.userHash != user.toHashString()) {
            return null
        }
        return cachedValues?.values?.time
    }

    fun getPreviousDerivedFields(user: StatsigUser): Map<String, String> {
        var cachedValues = cacheById[this.getScopedCacheKey(user)] ?: cacheById[user.getCacheKeyDEPRECATED()]
        if (cachedValues?.userHash != user.toHashString()) {
            return mapOf()
        }
        return cachedValues?.values?.derivedFields ?: mapOf()
    }

    fun getFullChecksum(user: StatsigUser): String? {
        var cachedValues = cacheById[this.getScopedCacheKey(user)] ?: cacheById[user.getCacheKeyDEPRECATED()]
        if (cachedValues?.userHash != user.toHashString()) {
            return null
        }
        return cachedValues?.values?.fullChecksum ?: null
    }

    private fun getScopedCacheKey(user: StatsigUser): String {
        return this.options.customCacheKey(this.sdkKey, user)
    }

    suspend fun save(data: InitializeResponse.SuccessfulInitializeResponse, user: StatsigUser) {
        val cacheKey = this.getScopedCacheKey(user)
        val cache = cacheById[cacheKey] ?: createEmptyCache()
        cache.values = data
        cache.evaluationTime = System.currentTimeMillis()
        cache.userHash = user.toHashString()
        cacheById[cacheKey] = cache

        if (cacheKey == currentUserCacheKeyV2) {
            currentCache = cache
            reason = if (data.hasUpdates) EvaluationReason.Network else EvaluationReason.NetworkNotModified
        }

        // Drop out cache entry with deprecated cache key
        cacheById.remove(user.getCacheKeyDEPRECATED())

        var cacheString = gson.toJson(cacheById)

        // Drop out other users if the cache is getting too big
        if ((cacheString.length / 1024) > 1024/*1 MB*/ && cacheById.size > 1) {
            cacheById = ConcurrentHashMap()
            cacheById[currentUserCacheKeyV2] = currentCache
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
        var gate = currentCache.values.featureGates?.get(gateName)
            ?: currentCache.values.featureGates?.get(Hashing.getHashedString(gateName, currentCache.values.hashUsed))
        val gateOrDefault = gate ?: return FeatureGate(gateName, getEvaluationDetails(false), false)
        return FeatureGate(gateName, gateOrDefault, getEvaluationDetails(true))
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

        val data = getConfigData(configName)
        return hydrateDynamicConfig(configName, getEvaluationDetails(data != null), data)
    }

    private fun getConfigData(name: String): APIDynamicConfig? {
        return currentCache.values.let {
            it.configs?.get(name) ?: it.configs?.get(Hashing.getHashedString(name, currentCache.values.hashUsed))
        }
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

        val latestValue = currentCache.values.configs?.get(experimentName)
            ?: currentCache.values.configs?.get(Hashing.getHashedString(experimentName, currentCache.values.hashUsed))
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

        val latestValue = currentCache.values.layerConfigs?.get(layerName)
            ?: currentCache.values.layerConfigs?.get(Hashing.getHashedString(layerName, currentCache.values.hashUsed))
        val details = getEvaluationDetails(latestValue != null)
        val finalValue = getPossiblyStickyValue(layerName, latestValue, keepDeviceValue, details, true)
        return if (finalValue != null) {
            Layer(client, layerName, finalValue, details)
        } else {
            Layer(client, layerName, details)
        }
    }

    fun getParamStore(client: StatsigClient, paramStoreName: String, options: ParameterStoreEvaluationOptions?): ParameterStore {
        val values = currentCache.values
        if (values.paramStores == null) {
            return ParameterStore(client, HashMap(), paramStoreName, getEvaluationDetails(false), options)
        }
        var paramStore = values.paramStores[paramStoreName]
        if (paramStore != null) {
            return ParameterStore(client, paramStore, paramStoreName, getEvaluationDetails(true), options)
        }

        val hashedParamStoreName = Hashing.getHashedString(
            paramStoreName,
            currentCache.values.hashUsed,
        )
        paramStore = values.paramStores[hashedParamStoreName]
        return ParameterStore(
            client,
            paramStore ?: HashMap(),
            paramStoreName,
            getEvaluationDetails(paramStore != null),
            options,
        )
    }

    internal fun getGlobalEvaluationDetails(): EvaluationDetails {
        return EvaluationDetails(this.reason, currentCache.evaluationTime ?: System.currentTimeMillis(), lcut = currentCache.values.time)
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
            lcut = this.currentCache.values.time,
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

    fun getCurrentValuesAsString(): String {
        return gson.toJson(currentCache.values)
    }

    fun getCachedInitializationResponse(): InitializeResponse.SuccessfulInitializeResponse {
        return currentCache.values
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
            InitializeResponse.SuccessfulInitializeResponse(mapOf(), mapOf(), mapOf(), false, null, 0, mapOf(), null)
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
