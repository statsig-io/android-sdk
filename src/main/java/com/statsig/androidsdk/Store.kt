package com.statsig.androidsdk

import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

private const val CACHE_BY_USER_KEY: String = "Statsig.CACHE_BY_USER"
private const val DEPRECATED_STICKY_USER_EXPERIMENTS_KEY: String = "Statsig.STICKY_USER_EXPERIMENTS"
private const val STICKY_DEVICE_EXPERIMENTS_KEY: String = "Statsig.STICKY_DEVICE_EXPERIMENTS"
private const val LOCAL_OVERRIDES_KEY: String = "Statsig.LOCAL_OVERRIDES"
private const val CACHE_KEY_MAPPING_KEY: String = "Statsig.CACHE_KEY_MAPPING"
private const val USER_CACHE_KEY: String = "Statsig.USER_CACHE"
private const val USER_CACHE_STORE_PREFIX: String = "ondiskvaluecache_user"
private const val MAX_USER_CACHE_ENTRIES = 10

private const val STORE_NAME: String = "ondiskvaluecache"

private data class StickyUserExperiments(
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>
)

private data class DeprecatedStickyUserExperiments(
    @SerializedName("user_id") val userID: String?,
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>
)

@VisibleForTesting
private data class Cache(
    @SerializedName("values") var values: InitializeResponse.SuccessfulInitializeResponse,
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments,
    @SerializedName("userHash") var userHash: String,
    @SerializedName("evaluationTime") var evaluationTime: Long? = System.currentTimeMillis(),
    @SerializedName("bootstrapMetadata") var bootstrapMetadata: BootstrapMetadata? = null
)

private data class UserCacheKeys(
    val scopedCacheKey: String,
    val userHash: String,
    val fullUserCacheKey: String
)

private data class UserCacheStorageTarget(val storeName: String, val storageKey: String)

private data class PersistedCache(
    @SerializedName("schema_version") val schemaVersion: Int = PERSISTED_CACHE_SCHEMA_V2,
    @SerializedName("values") var values: PersistedInitializeResponse,
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments,
    @SerializedName("userHash") var userHash: String,
    @SerializedName("evaluationTime") var evaluationTime: Long? = System.currentTimeMillis(),
    @SerializedName("bootstrapMetadata") var bootstrapMetadata: BootstrapMetadata? = null
)
internal class Store(
    private val statsigScope: CoroutineScope,
    private val keyValueStorage: KeyValueStorage<String>,
    user: StatsigUser,
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val gson: Gson
) {
    var sourceV2: EvalSource
    var receivedValuesAt: Long? = null

    private val dispatcherProvider = CoroutineDispatcherProvider()
    private var currentUserCacheKeyV2: String
    private var currentFullUserCacheKey: String
    private var cacheById: ConcurrentHashMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: ConcurrentHashMap<String, APIDynamicConfig>
    private var localOverrides: StatsigOverrides
    private var cacheKeyMapping: ConcurrentHashMap<String, String>
    private var currentUser: StatsigUser

    init {
        val userCacheKeys = getUserCacheKeys(user)
        currentUserCacheKeyV2 = userCacheKeys.scopedCacheKey
        currentFullUserCacheKey = userCacheKeys.fullUserCacheKey
        cacheById = ConcurrentHashMap()
        currentCache = createEmptyCache()
        stickyDeviceExperiments = ConcurrentHashMap()
        localOverrides = StatsigOverrides.empty()
        sourceV2 = EvalSource.Uninitialized
        cacheKeyMapping = ConcurrentHashMap()
        currentUser = user
    }

    fun syncLoadFromLocalStorage() {
        runBlocking(dispatcherProvider.io) {
            loadFromLocalStorage()
        }
    }

    suspend fun loadFromLocalStorage() {
        sourceV2 = EvalSource.Loading
        cacheById = ConcurrentHashMap()

        stickyDeviceExperiments = ConcurrentHashMap()
        val cachedDeviceValues = keyValueStorage.readValue(
            STORE_NAME,
            STICKY_DEVICE_EXPERIMENTS_KEY
        )
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            try {
                val localSticky: Map<String, APIDynamicConfig> = gson.fromJson(
                    cachedDeviceValues,
                    type
                )
                stickyDeviceExperiments = ConcurrentHashMap(localSticky)
            } catch (_: Exception) {
                keyValueStorage.removeValue(STORE_NAME, STICKY_DEVICE_EXPERIMENTS_KEY)
            }
        }

        localOverrides = StatsigOverrides.empty()
        val cachedLocalOverrides = keyValueStorage.readValue(STORE_NAME, LOCAL_OVERRIDES_KEY)
        if (cachedLocalOverrides != null) {
            try {
                localOverrides = gson.fromJson(cachedLocalOverrides, StatsigOverrides::class.java)
            } catch (_: Exception) {
                keyValueStorage.removeValue(STORE_NAME, LOCAL_OVERRIDES_KEY)
            }
        }

        cacheKeyMapping = ConcurrentHashMap()
        val cachedCacheKeyMapping = keyValueStorage.readValue(STORE_NAME, CACHE_KEY_MAPPING_KEY)
        if (cachedCacheKeyMapping != null) {
            val type = object : TypeToken<ConcurrentHashMap<String, String>>() {}.type
            try {
                cacheKeyMapping = gson.fromJson(cachedCacheKeyMapping, type)
            } catch (_: Exception) {
                keyValueStorage.removeValue(STORE_NAME, CACHE_KEY_MAPPING_KEY)
            }
        }

        loadCacheForCurrentUserAsync()
    }

    fun resetUser(user: StatsigUser) {
        val userCacheKeys = getUserCacheKeys(user)
        currentUserCacheKeyV2 = userCacheKeys.scopedCacheKey
        currentFullUserCacheKey = userCacheKeys.fullUserCacheKey
        sourceV2 = EvalSource.Uninitialized
        currentUser = user
        receivedValuesAt = null
    }

    fun bootstrap(initializeValues: Map<String, Any>, user: StatsigUser) {
        val isValid = BootstrapValidator.isValid(initializeValues, user)
        sourceV2 = if (isValid) EvalSource.Bootstrap else EvalSource.InvalidBootstrap

        try {
            currentCache.bootstrapMetadata = BootstrapMetadata.fromInitializeValues(
                initializeValues,
                gson
            )
            currentCache.values =
                InitializeResponseFormatter.deserialize(gson.toJson(initializeValues), gson)
            cacheKeyMapping[currentUserCacheKeyV2] = currentFullUserCacheKey
            cacheById[currentFullUserCacheKey] = currentCache
        } catch (e: Exception) {
            // Do Nothing
        }
    }

    fun loadCacheForCurrentUser() {
        runBlocking(dispatcherProvider.io) {
            loadCacheForCurrentUserAsync()
        }
    }

    suspend fun loadCacheForCurrentUserAsync() {
        val userCacheKeys = getUserCacheKeys(currentUser)
        var cachedValues = this.getCachedValuesForUser(userCacheKeys)
        if (sourceV2 != EvalSource.Loading) {
            sourceV2 = EvalSource.Loading
        }
        if (cachedValues == null) {
            cachedValues = loadCacheForUserFromStorage(userCacheKeys)
        }
        if (cachedValues != null) {
            currentCache = cachedValues
            sourceV2 = EvalSource.Cache
            receivedValuesAt = cachedValues.evaluationTime
            return
        }
        currentCache = createEmptyCache()
    }

    private fun getCachedValuesForUser(userCacheKeys: UserCacheKeys): Cache? {
        val fullHashCachedValues = cacheById[userCacheKeys.fullUserCacheKey]
        if (fullHashCachedValues != null) {
            return fullHashCachedValues
        }
        var cachedValues = cacheById[userCacheKeys.scopedCacheKey]
        if (cachedValues != null) {
            return cachedValues
        }

        val cacheMapping = cacheKeyMapping[userCacheKeys.scopedCacheKey]
        if (cacheMapping != null) {
            cachedValues = cacheById[cacheMapping]
            if (cachedValues != null) {
                return cachedValues
            }
        }

        return null
    }

    fun getLastUpdateTime(user: StatsigUser): Long? {
        val userCacheKeys = getUserCacheKeys(user)
        var cachedValues = this.getCachedValuesForUser(userCacheKeys)
        if (cachedValues?.userHash != userCacheKeys.userHash) {
            return null
        }
        return cachedValues.values.time
    }

    fun getPreviousDerivedFields(user: StatsigUser): Map<String, String> {
        val userCacheKeys = getUserCacheKeys(user)
        val cachedValues = this.getCachedValuesForUser(userCacheKeys)
        if (cachedValues?.userHash != userCacheKeys.userHash) {
            return mapOf()
        }
        return cachedValues.values.derivedFields ?: mapOf()
    }

    fun getFullChecksum(user: StatsigUser): String? {
        val userCacheKeys = getUserCacheKeys(user)
        val cachedValues = this.getCachedValuesForUser(userCacheKeys)
        return cachedValues?.values?.fullChecksum
    }

    private fun getUserCacheKeys(user: StatsigUser): UserCacheKeys {
        val userHash = user.toHashString(gson)
        val scopedCacheKey = this.options.customCacheKey(this.sdkKey, user)
        return UserCacheKeys(
            scopedCacheKey = scopedCacheKey,
            userHash = userHash,
            fullUserCacheKey = "$userHash:${this.sdkKey}"
        )
    }

    suspend fun save(data: InitializeResponse.SuccessfulInitializeResponse, user: StatsigUser) {
        val userCacheKeys = getUserCacheKeys(user)
        val cacheKey = userCacheKeys.scopedCacheKey
        val fullCacheKey = userCacheKeys.fullUserCacheKey
        val isCurrentUser = cacheKey == currentUserCacheKeyV2
        if (isCurrentUser) {
            currentFullUserCacheKey = fullCacheKey
            receivedValuesAt = System.currentTimeMillis()
            if (data.hasUpdates) {
                val cache = cacheById[fullCacheKey] ?: createEmptyCache()
                cache.values = data
                cache.evaluationTime = receivedValuesAt
                cache.userHash = userCacheKeys.userHash
                cacheById[fullCacheKey] = cache

                currentCache = cache
                sourceV2 = EvalSource.Network
            } else {
                sourceV2 = EvalSource.NetworkNotModified
                return
            }
        }

        val priorMapping = cacheKeyMapping[cacheKey]
        cacheKeyMapping[cacheKey] = fullCacheKey

        val cacheToPersist = cacheById[fullCacheKey] ?: currentCache
        cacheById[fullCacheKey] = cacheToPersist
        writeUserCache(fullCacheKey, cacheToPersist)

        if (priorMapping != null &&
            priorMapping != fullCacheKey &&
            !cacheKeyMapping.containsValue(priorMapping)
        ) {
            cacheById.remove(priorMapping)
            deleteUserCache(priorMapping)
        }

        evictMappedCachesIfNeeded(cacheKey)
        keyValueStorage.writeValue(STORE_NAME, CACHE_KEY_MAPPING_KEY, gson.toJson(cacheKeyMapping))
        // New writes are per-user stores; keep legacy key read-only fallback.
        keyValueStorage.removeValue(STORE_NAME, CACHE_BY_USER_KEY)
    }

    fun checkGate(gateName: String): FeatureGate {
        val overriddenValue = localOverrides.gates[gateName]
        if (overriddenValue != null) {
            return FeatureGate(
                gateName,
                getEvalDetails(false, EvalReason.LocalOverride),
                overriddenValue,
                "override"
            )
        }
        val gate = currentCache.values.featureGates?.get(gateName)
            ?: currentCache.values.featureGates?.get(
                Hashing.getHashedString(gateName, currentCache.values.hashUsed)
            )
        val gateOrDefault = gate ?: return FeatureGate(gateName, getEvalDetails(false), false)
        return FeatureGate(gateName, gateOrDefault, getEvalDetails(true))
    }

    fun getConfig(configName: String): DynamicConfig {
        val overrideValue = localOverrides.configs[configName]
        if (overrideValue != null) {
            return DynamicConfig(
                configName,
                getEvalDetails(false, EvalReason.LocalOverride),
                overrideValue,
                "override"
            )
        }

        val data = getConfigData(configName)
        return hydrateDynamicConfig(configName, getEvalDetails(data != null), data)
    }

    private fun getConfigData(name: String): APIDynamicConfig? = currentCache.values.let {
        it.configs?.get(name)
            ?: it.configs?.get(Hashing.getHashedString(name, currentCache.values.hashUsed))
    }

    fun getExperiment(experimentName: String, keepDeviceValue: Boolean): DynamicConfig {
        val overrideValue = localOverrides.configs[experimentName]
        if (overrideValue != null) {
            return DynamicConfig(
                experimentName,
                getEvalDetails(false, EvalReason.LocalOverride),
                overrideValue,
                "override"
            )
        }

        val latestValue = currentCache.values.configs?.get(experimentName)
            ?: currentCache.values.configs?.get(
                Hashing.getHashedString(experimentName, currentCache.values.hashUsed)
            )
        val details = getEvalDetails(latestValue != null)
        val finalValue =
            getPossiblyStickyValue(experimentName, latestValue, keepDeviceValue, details, false)
        return hydrateDynamicConfig(
            experimentName,
            details,
            finalValue
        )
    }

    fun getLayer(
        client: StatsigClient?,
        layerName: String,
        keepDeviceValue: Boolean = false
    ): Layer {
        val overrideValue = localOverrides.layers[layerName]
        if (overrideValue != null) {
            return Layer(
                null,
                layerName,
                getEvalDetails(false, EvalReason.LocalOverride),
                overrideValue,
                "override"
            )
        }

        val latestValue = currentCache.values.layerConfigs?.get(layerName)
            ?: currentCache.values.layerConfigs?.get(
                Hashing.getHashedString(layerName, currentCache.values.hashUsed)
            )
        val details = getEvalDetails(latestValue != null)
        val finalValue =
            getPossiblyStickyValue(layerName, latestValue, keepDeviceValue, details, true)
        return if (finalValue != null) {
            Layer(client, layerName, finalValue, details)
        } else {
            Layer(client, layerName, details)
        }
    }

    fun getParamStore(
        client: StatsigClient,
        paramStoreName: String,
        options: ParameterStoreEvaluationOptions?
    ): ParameterStore {
        val values = currentCache.values
        if (values.paramStores == null) {
            return ParameterStore(
                client,
                HashMap(),
                paramStoreName,
                getEvalDetails(false),
                options
            )
        }
        var paramStore = values.paramStores[paramStoreName]
        if (paramStore != null) {
            return ParameterStore(
                client,
                paramStore,
                paramStoreName,
                getEvalDetails(true),
                options
            )
        }

        val hashedParamStoreName = Hashing.getHashedString(
            paramStoreName,
            currentCache.values.hashUsed
        )
        paramStore = values.paramStores[hashedParamStoreName]
        return ParameterStore(
            client,
            paramStore ?: HashMap(),
            paramStoreName,
            getEvalDetails(paramStore != null),
            options
        )
    }
    internal fun getGlobalEvalDetails(): EvalDetails = EvalDetails(
        source = sourceV2,
        reason = null,
        lcut = currentCache.values.time,
        receivedAt = receivedValuesAt
    )

    internal fun getBootstrapMetadata(): BootstrapMetadata? = currentCache.bootstrapMetadata

    internal fun getEvalDetails(
        valueExists: Boolean,
        reasonOverride: EvalReason? = null
    ): EvalDetails {
        if (valueExists) {
            return getGlobalEvalDetails().copy().apply { reason = EvalReason.Recognized }
        }

        return getGlobalEvalDetails().copy().apply {
            reason = reasonOverride ?: EvalReason.Unrecognized
        }
    }

    // Sticky Logic: https://gist.github.com/daniel-statsig/3d8dfc9bdee531cffc96901c1a06a402
    private fun getPossiblyStickyValue(
        name: String,
        latestValue: APIDynamicConfig?,
        keepDeviceValue: Boolean,
        details: EvalDetails,
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
            details.reason = EvalReason.Sticky
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
        keyValueStorage.writeValue(STORE_NAME, LOCAL_OVERRIDES_KEY, gson.toJson(localOverrides))
    }

    fun getAllOverrides(): StatsigOverrides = StatsigOverrides(
        localOverrides.gates,
        localOverrides.configs,
        localOverrides.layers
    )

    fun getSDKFlags(): Map<String, Any>? = currentCache.values.sdkFlags

    fun getSDKConfigs(): Map<String, Any>? = currentCache.values.sdkConfigs

    fun getCurrentCacheValuesAndEvalDetails(): ExternalInitializeResponse =
        ExternalInitializeResponse(gson.toJson(currentCache.values), getEvalDetails(true))

    fun getCurrentValuesAsString(): String = gson.toJson(currentCache.values)

    fun getCachedInitializationResponse(): InitializeResponse.SuccessfulInitializeResponse =
        currentCache.values

    private fun hydrateDynamicConfig(
        name: String,
        details: EvalDetails,
        config: APIDynamicConfig?
    ): DynamicConfig = if (config != null) {
        DynamicConfig(name, config, details)
    } else {
        DynamicConfig(name, details)
    }

    private fun createEmptyCache(): Cache {
        val emptyInitResponse =
            InitializeResponse.SuccessfulInitializeResponse(
                mapOf(),
                mapOf(),
                mapOf(),
                false,
                null,
                0,
                mapOf(),
                null
            )
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
        return currentCache.stickyUserExperiments.experiments[hashName]
            ?: stickyDeviceExperiments[hashName]
    }

    suspend fun persistStickyValues() {
        writeUserCache(currentFullUserCacheKey, currentCache)
        keyValueStorage.writeValue(
            STORE_NAME,
            STICKY_DEVICE_EXPERIMENTS_KEY,
            gson.toJson(stickyDeviceExperiments)
        )
    }

    private suspend fun loadCacheForUserFromStorage(userCacheKeys: UserCacheKeys): Cache? {
        val fullCacheKey = userCacheKeys.fullUserCacheKey
        val scopedCacheKey = userCacheKeys.scopedCacheKey

        readUserCache(fullCacheKey)?.let { loaded ->
            cacheById[fullCacheKey] = loaded
            return loaded
        }

        val mappedKey = cacheKeyMapping[scopedCacheKey]
        if (mappedKey != null) {
            readUserCache(mappedKey)?.let { loaded ->
                cacheById[mappedKey] = loaded
                return loaded
            }
        }

        val cachedResponse = keyValueStorage.readValue(STORE_NAME, CACHE_BY_USER_KEY) ?: return null
        val localCache = tryLoadCompactCacheMap(cachedResponse)
            ?: tryLoadLegacyCacheMap(cachedResponse)
            ?: run {
                keyValueStorage.removeValue(STORE_NAME, CACHE_BY_USER_KEY)
                return null
            }

        val resolvedKey =
            when {
                localCache.containsKey(fullCacheKey) -> fullCacheKey
                mappedKey != null && localCache.containsKey(mappedKey) -> mappedKey
                localCache.containsKey(scopedCacheKey) -> scopedCacheKey
                else -> return null
            }

        val canonicalKey = if (resolvedKey == scopedCacheKey) {
            fullCacheKey
        } else {
            resolvedKey
        }
        val loaded = localCache[resolvedKey] ?: return null
        cacheById[canonicalKey] = loaded
        cacheKeyMapping[scopedCacheKey] = canonicalKey
        return loaded
    }

    private suspend fun readUserCache(fullCacheKey: String): Cache? {
        val userCacheStorageTarget = getUserCacheStorageTarget(fullCacheKey)
        val serialized = keyValueStorage.readValue(
            userCacheStorageTarget.storeName,
            userCacheStorageTarget.storageKey
        ) ?: return null
        return try {
            tryLoadPersistedCache(serialized) ?: gson.fromJson(serialized, Cache::class.java)
        } catch (_: Exception) {
            keyValueStorage.removeValue(
                userCacheStorageTarget.storeName,
                userCacheStorageTarget.storageKey
            )
            null
        }
    }

    private suspend fun writeUserCache(fullCacheKey: String, cache: Cache) {
        val userCacheStorageTarget = getUserCacheStorageTarget(fullCacheKey)
        keyValueStorage.writeValue(
            userCacheStorageTarget.storeName,
            userCacheStorageTarget.storageKey,
            gson.toJson(toPersistedCache(cache))
        )
    }

    private suspend fun evictMappedCachesIfNeeded(currentCacheKey: String) {
        val overflow = cacheKeyMapping.size - MAX_USER_CACHE_ENTRIES
        if (overflow <= 0) {
            return
        }

        val candidates = cacheKeyMapping.entries
            .filter { it.key != currentCacheKey }
            .sortedBy { it.key }
            .take(overflow)

        candidates.forEach { (scopedKey, fullKey) ->
            cacheKeyMapping.remove(scopedKey, fullKey)
            cacheById.remove(fullKey)
            if (!cacheKeyMapping.containsValue(fullKey)) {
                deleteUserCache(fullKey)
            }
        }
    }

    private suspend fun deleteUserCache(fullCacheKey: String) {
        val userCacheStorageTarget = getUserCacheStorageTarget(fullCacheKey)
        try {
            keyValueStorage.clearStore(userCacheStorageTarget.storeName)
        } catch (_: NotImplementedError) {
            keyValueStorage.removeValue(
                userCacheStorageTarget.storeName,
                userCacheStorageTarget.storageKey
            )
        }
    }

    private fun getUserCacheStoreHash(fullCacheKey: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(fullCacheKey.toByteArray())
        return bytes.joinToString("") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }

    private fun getUserCacheStorageTarget(fullCacheKey: String): UserCacheStorageTarget {
        val storeHash = getUserCacheStoreHash(fullCacheKey)
        return UserCacheStorageTarget(
            storeName = "${USER_CACHE_STORE_PREFIX}_$storeHash",
            storageKey = "${USER_CACHE_KEY}_$storeHash"
        )
    }

    private fun tryLoadLegacyCacheMap(cachedResponse: String): Map<String, Cache>? {
        val type = object : TypeToken<MutableMap<String, Cache>>() {}.type
        return try {
            gson.fromJson(cachedResponse, type)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryLoadPersistedCache(serialized: String): Cache? {
        val persisted = try {
            gson.fromJson(serialized, PersistedCache::class.java)
        } catch (_: Exception) {
            null
        } ?: return null

        if (persisted.schemaVersion != PERSISTED_CACHE_SCHEMA_V2) {
            return null
        }

        return Cache(
            values = persisted.values.toRuntime(),
            stickyUserExperiments = persisted.stickyUserExperiments,
            userHash = persisted.userHash,
            evaluationTime = persisted.evaluationTime,
            bootstrapMetadata = persisted.bootstrapMetadata
        )
    }

    private fun tryLoadCompactCacheMap(cachedResponse: String): Map<String, Cache>? {
        val type = object : TypeToken<MutableMap<String, PersistedCache>>() {}.type
        return try {
            val localCache: Map<String, PersistedCache> = gson.fromJson(cachedResponse, type)
            if (localCache.values.any { it.schemaVersion != PERSISTED_CACHE_SCHEMA_V2 }) {
                return null
            }

            localCache.mapValues { (_, cache) ->
                Cache(
                    values = cache.values.toRuntime(),
                    stickyUserExperiments = cache.stickyUserExperiments,
                    userHash = cache.userHash,
                    evaluationTime = cache.evaluationTime,
                    bootstrapMetadata = cache.bootstrapMetadata
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun toPersistedCache(cache: Cache): PersistedCache = PersistedCache(
        values = InitializeResponseFormatter.toPersistedResponse(cache.values, gson),
        stickyUserExperiments = cache.stickyUserExperiments,
        userHash = cache.userHash,
        evaluationTime = cache.evaluationTime,
        bootstrapMetadata = cache.bootstrapMetadata
    )

    internal fun notifyNetworkFailure() {
        // Mark NoValues if cache attempt did not complete
        if (sourceV2 != EvalSource.Cache) {
            sourceV2 = EvalSource.NoValues
        }
    }

    internal fun notifyOfflineInit() {
        // Mark NoValues if we still have a generated empty cache
        if (sourceV2 == EvalSource.Cache && currentCache.values.time == 0L &&
            currentCache.userHash == ""
        ) {
            sourceV2 = EvalSource.NoValues
        }
    }
}
