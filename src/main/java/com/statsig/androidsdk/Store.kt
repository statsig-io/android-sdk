package com.statsig.androidsdk

import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

private data class CacheKeyMappingValue(
    @SerializedName("fullUserCacheKey") val fullUserCacheKey: String,
    @SerializedName("lastUsedAt") val lastUsedAt: Long
)

private data class PersistedCache(
    @SerializedName("schema_version") val schemaVersion: Int = PERSISTED_CACHE_SCHEMA_V2,
    @SerializedName("values") var values: PersistedInitializeResponse,
    @SerializedName("stickyUserExperiments") var stickyUserExperiments: StickyUserExperiments,
    @SerializedName("userHash") var userHash: String,
    @SerializedName("evaluationTime") var evaluationTime: Long? = System.currentTimeMillis(),
    @SerializedName("bootstrapMetadata") var bootstrapMetadata: BootstrapMetadata? = null
)

private fun getStorageHash(value: String): String {
    // Storage hashes are embedded into DataStore file names and preference keys. Keep them
    // lowercase hex instead of our usual base64 SHA-256 representation so they are path-safe.
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return bytes.joinToString("") { byte ->
        ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
    }
}

/**
 * Stores one Initialize response payload per full user cache key.
 *
 * The full user cache key is still a logical Statsig cache identifier; this class hashes it into
 * DataStore-safe store/key names and owns the per-user mutex used while mutating that payload.
 */
private class UserCacheStorage(
    private val keyValueStorage: KeyValueStorage<String>,
    private val fromSerializedCache: suspend (String, UserCacheStorageTarget) -> Cache?,
    private val toSerializedCache: (Cache) -> String
) {
    private companion object {
        private val userCacheMutexes: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

        private fun getUserCacheMutex(fullUserCacheKey: String): Mutex =
            userCacheMutexes.getOrPut(fullUserCacheKey) { Mutex() }
    }

    suspend fun read(fullUserCacheKey: String): Cache? {
        val target = getStorageTarget(fullUserCacheKey)
        val serialized = keyValueStorage.readValue(target.storeName, target.storageKey)
            ?: return null
        return fromSerializedCache(serialized, target)
    }

    suspend fun write(fullUserCacheKey: String, cache: Cache) {
        val target = getStorageTarget(fullUserCacheKey)
        keyValueStorage.writeValue(target.storeName, target.storageKey, toSerializedCache(cache))
    }

    suspend fun delete(fullUserCacheKey: String) {
        val target = getStorageTarget(fullUserCacheKey)
        try {
            keyValueStorage.clearStore(target.storeName)
        } catch (_: NotImplementedError) {
            keyValueStorage.removeValue(target.storeName, target.storageKey)
        }
    }

    suspend fun <T> withFullUserCacheKeyLock(fullUserCacheKey: String, action: suspend () -> T): T =
        getUserCacheMutex(fullUserCacheKey).withLock { action() }

    private fun getStorageTarget(fullUserCacheKey: String): UserCacheStorageTarget {
        val storeHash = getStorageHash(fullUserCacheKey)
        return UserCacheStorageTarget(
            storeName = "${USER_CACHE_STORE_PREFIX}_$storeHash",
            storageKey = "${USER_CACHE_KEY}_$storeHash"
        )
    }
}

/**
 * Cache-key mapping shared by all Store instances.
 *
 * The companion state is the same-process coordination point. The single persisted blob is only the
 * durable backing state, so reads after load avoid disk and multi-client writes cannot overwrite
 * each other with stale local map snapshots. Multi-process is out-of-scope for now.
 */
private class SharedCacheKeyMapping(
    private val keyValueStorage: KeyValueStorage<String>,
    private val gson: Gson
) {
    companion object {
        private val mutex = Mutex()
        private val mappings = ConcurrentHashMap<String, CacheKeyMappingValue>()
        private var isLoaded = false

        @VisibleForTesting
        internal fun resetForTesting() {
            mappings.clear()
            isLoaded = false
        }

        @VisibleForTesting
        internal fun sizeForTesting(): Int = mappings.size
    }

    suspend fun ensureLoaded() {
        if (isLoaded) {
            return
        }

        mutex.withLock {
            if (isLoaded) {
                return@withLock
            }
            mappings.clear()
            mappings.putAll(readPersisted())
            isLoaded = true
        }
    }

    fun readFullUserCacheKey(scopedCacheKey: String): String? =
        mappings[scopedCacheKey]?.fullUserCacheKey

    fun upsertInMemory(scopedCacheKey: String, fullUserCacheKey: String) {
        mappings[scopedCacheKey] = newMappingValue(fullUserCacheKey)
    }

    suspend fun commit(scopedCacheKey: String, fullUserCacheKey: String): String? = mutex.withLock {
        val previousFullUserCacheKey =
            mappings.put(scopedCacheKey, newMappingValue(fullUserCacheKey))?.fullUserCacheKey
        writePersisted()
        previousFullUserCacheKey
    }

    suspend fun removeOverflowEntries(maxEntries: Int, exemptScopedCacheKey: String): List<String> =
        mutex.withLock {
            val overflow = mappings.size - maxEntries
            if (overflow <= 0) {
                return@withLock emptyList()
            }

            val removedEntries = mappings.entries
                .filter { it.key != exemptScopedCacheKey }
                .sortedWith(
                    compareBy<Map.Entry<String, CacheKeyMappingValue>> { it.value.lastUsedAt }
                        .thenBy { it.key }
                )
                .take(overflow)
                .onEach { mappings.remove(it.key) }

            if (removedEntries.isNotEmpty()) {
                writePersisted()
            }

            removedEntries.map { it.value.fullUserCacheKey }
        }

    suspend fun isFullUserCacheKeyReferenced(fullUserCacheKey: String): Boolean = mutex.withLock {
        mappings.values.any { it.fullUserCacheKey == fullUserCacheKey }
    }

    private suspend fun readPersisted(): Map<String, CacheKeyMappingValue> {
        val serialized = keyValueStorage.readValue(STORE_NAME, CACHE_KEY_MAPPING_KEY)
            ?: return emptyMap()

        tryLoadPersistedMapping(serialized)?.let { return it }
        tryLoadLegacyMapping(serialized)?.let { return it }

        keyValueStorage.removeValue(STORE_NAME, CACHE_KEY_MAPPING_KEY)
        return emptyMap()
    }

    private suspend fun writePersisted() {
        val persisted = mappings.entries
            .sortedBy { it.key }
            .associate { (scopedCacheKey, value) -> scopedCacheKey to value }

        keyValueStorage.writeValue(
            STORE_NAME,
            CACHE_KEY_MAPPING_KEY,
            gson.toJson(persisted)
        )
    }

    private fun newMappingValue(fullUserCacheKey: String): CacheKeyMappingValue =
        CacheKeyMappingValue(
            fullUserCacheKey = fullUserCacheKey,
            lastUsedAt = System.currentTimeMillis()
        )

    private fun tryLoadPersistedMapping(serialized: String): Map<String, CacheKeyMappingValue>? {
        return try {
            val type = object : TypeToken<Map<String, CacheKeyMappingValue>>() {}.type
            val persisted: Map<String, CacheKeyMappingValue> = gson.fromJson(
                serialized,
                type
            )
                ?: return null
            persisted
                .filter { (scopedKey, value) ->
                    scopedKey.isNotEmpty() && value.isValid()
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryLoadLegacyMapping(serialized: String): Map<String, CacheKeyMappingValue>? {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val legacyMapping: Map<String, String> =
                gson.fromJson(serialized, type) ?: return null
            val now = System.currentTimeMillis()
            legacyMapping
                .mapValues { (_, fullKey) ->
                    CacheKeyMappingValue(fullUserCacheKey = fullKey, lastUsedAt = now)
                }
                .filter { (scopedKey, value) ->
                    scopedKey.isNotEmpty() && value.isValid()
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun CacheKeyMappingValue.isValid(): Boolean = fullUserCacheKey.isNotEmpty()
}

private class SerialBackgroundQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher
) {
    private val lock = Any()
    private var pendingJob: Job? = null

    fun enqueue(action: suspend () -> Unit) {
        synchronized(lock) {
            val priorJob = pendingJob
            pendingJob = scope.launch(dispatcher) {
                priorJob?.join()
                try {
                    action()
                } catch (_: Exception) {
                    // Best effort background work should not fail evaluation.
                }
            }
        }
    }

    suspend fun await() {
        val pending = synchronized(lock) { pendingJob }
        pending?.join()
        synchronized(lock) {
            if (pendingJob?.isCompleted == true) {
                pendingJob = null
            }
        }
    }
}

@VisibleForTesting
internal fun resetSharedCacheKeyMappingStoreForTesting() {
    SharedCacheKeyMapping.resetForTesting()
}

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
    private val userCacheStorage = UserCacheStorage(
        keyValueStorage,
        ::readSerializedUserCache,
        { cache -> gson.toJson(toPersistedCache(cache)) }
    )
    private val cacheKeyMappingStore = SharedCacheKeyMapping(keyValueStorage, gson)
    private val saveQueue = SerialBackgroundQueue(statsigScope, dispatcherProvider.io)
    private val cacheMaintenanceQueue = SerialBackgroundQueue(statsigScope, dispatcherProvider.io)
    private var currentScopedCacheKey: String
    private var currentFullUserCacheKey: String
    private var userCacheByKey: ConcurrentHashMap<String, Cache>
    private var currentCache: Cache
    private var stickyDeviceExperiments: ConcurrentHashMap<String, APIDynamicConfig>
    private var localOverrides: StatsigOverrides
    private var currentUser: StatsigUser

    init {
        val userCacheKeys = getUserCacheKeys(user)
        currentScopedCacheKey = userCacheKeys.scopedCacheKey
        currentFullUserCacheKey = userCacheKeys.fullUserCacheKey
        userCacheByKey = ConcurrentHashMap()
        currentCache = createEmptyCache()
        stickyDeviceExperiments = ConcurrentHashMap()
        localOverrides = StatsigOverrides.empty()
        sourceV2 = EvalSource.Uninitialized
        currentUser = user
    }

    fun syncLoadFromLocalStorage() {
        runBlocking(dispatcherProvider.io) {
            loadFromLocalStorage()
        }
    }

    suspend fun loadFromLocalStorage() {
        sourceV2 = EvalSource.Loading
        userCacheByKey = ConcurrentHashMap()

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

        cacheKeyMappingStore.ensureLoaded()

        loadCacheForCurrentUserAsync()
    }

    fun resetUser(user: StatsigUser) {
        val userCacheKeys = getUserCacheKeys(user)
        currentScopedCacheKey = userCacheKeys.scopedCacheKey
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
            cacheKeyMappingStore.upsertInMemory(
                currentScopedCacheKey,
                currentFullUserCacheKey
            )
            userCacheByKey[currentFullUserCacheKey] = currentCache
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
        val fullHashCachedValues = userCacheByKey[userCacheKeys.fullUserCacheKey]
        if (fullHashCachedValues != null) {
            return fullHashCachedValues
        }
        var cachedValues = userCacheByKey[userCacheKeys.scopedCacheKey]
        if (cachedValues != null) {
            return cachedValues
        }

        val mappedFullUserCacheKey =
            cacheKeyMappingStore.readFullUserCacheKey(userCacheKeys.scopedCacheKey)
        if (mappedFullUserCacheKey != null) {
            cachedValues = userCacheByKey[mappedFullUserCacheKey]
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
        if (!applySaveToMemory(data, userCacheKeys)) {
            return
        }
        persistSave(userCacheKeys)
    }

    fun saveAsync(
        data: InitializeResponse.SuccessfulInitializeResponse,
        user: StatsigUser,
        onPersisted: suspend () -> Unit = {}
    ) {
        val userCacheKeys = getUserCacheKeys(user)
        if (!applySaveToMemory(data, userCacheKeys)) {
            return
        }
        saveQueue.enqueue {
            persistSave(userCacheKeys)
            onPersisted()
        }
    }

    suspend fun awaitPendingSave() {
        saveQueue.await()
    }

    @VisibleForTesting
    internal suspend fun awaitCacheMaintenance() {
        cacheMaintenanceQueue.await()
    }

    private fun applySaveToMemory(
        data: InitializeResponse.SuccessfulInitializeResponse,
        userCacheKeys: UserCacheKeys
    ): Boolean {
        val scopedCacheKey = userCacheKeys.scopedCacheKey
        val fullUserCacheKey = userCacheKeys.fullUserCacheKey
        val isCurrentUser = scopedCacheKey == currentScopedCacheKey
        if (isCurrentUser) {
            currentFullUserCacheKey = fullUserCacheKey
            receivedValuesAt = System.currentTimeMillis()
            if (data.hasUpdates) {
                val cache = userCacheByKey[fullUserCacheKey] ?: createEmptyCache()
                cache.values = data
                cache.evaluationTime = receivedValuesAt
                cache.userHash = userCacheKeys.userHash
                userCacheByKey[fullUserCacheKey] = cache

                currentCache = cache
                sourceV2 = EvalSource.Network
            } else {
                sourceV2 = EvalSource.NetworkNotModified
                return false
            }
        }

        val cacheToPersist = userCacheByKey[fullUserCacheKey] ?: currentCache
        userCacheByKey[fullUserCacheKey] = cacheToPersist

        return true
    }

    private suspend fun persistSave(userCacheKeys: UserCacheKeys) {
        var previousFullUserCacheKey: String? = null
        val cacheToPersist = userCacheByKey[userCacheKeys.fullUserCacheKey] ?: currentCache
        userCacheStorage.withFullUserCacheKeyLock(userCacheKeys.fullUserCacheKey) {
            userCacheStorage.write(userCacheKeys.fullUserCacheKey, cacheToPersist)
            previousFullUserCacheKey = cacheKeyMappingStore.commit(
                userCacheKeys.scopedCacheKey,
                userCacheKeys.fullUserCacheKey
            )
        }

        // New writes are per-user stores; keep legacy key read-only fallback.
        keyValueStorage.removeValue(STORE_NAME, CACHE_BY_USER_KEY)
        enqueueCacheMaintenance(
            currentScopedCacheKey = userCacheKeys.scopedCacheKey,
            replacedFullUserCacheKey = previousFullUserCacheKey
        )
    }

    private fun enqueueCacheMaintenance(
        currentScopedCacheKey: String,
        replacedFullUserCacheKey: String?
    ) {
        cacheMaintenanceQueue.enqueue {
            cleanupReplacedAndEvictedCaches(
                currentScopedCacheKey,
                replacedFullUserCacheKey
            )
        }
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
        userCacheStorage.withFullUserCacheKeyLock(currentFullUserCacheKey) {
            userCacheStorage.write(currentFullUserCacheKey, currentCache)
        }
        keyValueStorage.writeValue(
            STORE_NAME,
            STICKY_DEVICE_EXPERIMENTS_KEY,
            gson.toJson(stickyDeviceExperiments)
        )
    }

    private suspend fun loadCacheForUserFromStorage(userCacheKeys: UserCacheKeys): Cache? {
        val fullUserCacheKey = userCacheKeys.fullUserCacheKey
        val scopedCacheKey = userCacheKeys.scopedCacheKey

        userCacheStorage.read(fullUserCacheKey)?.let { loaded ->
            userCacheByKey[fullUserCacheKey] = loaded
            return loaded
        }

        val mappedFullUserCacheKey = cacheKeyMappingStore
            .readFullUserCacheKey(scopedCacheKey)
        if (mappedFullUserCacheKey != null) {
            userCacheStorage.read(mappedFullUserCacheKey)?.let { loaded ->
                userCacheByKey[mappedFullUserCacheKey] = loaded
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
                localCache.containsKey(fullUserCacheKey) -> fullUserCacheKey
                mappedFullUserCacheKey != null &&
                    localCache.containsKey(mappedFullUserCacheKey) -> mappedFullUserCacheKey
                localCache.containsKey(scopedCacheKey) -> scopedCacheKey
                else -> return null
            }

        val canonicalKey = if (resolvedKey == scopedCacheKey) {
            fullUserCacheKey
        } else {
            resolvedKey
        }
        val loaded = localCache[resolvedKey] ?: return null
        userCacheByKey[canonicalKey] = loaded
        cacheKeyMappingStore.upsertInMemory(
            scopedCacheKey,
            canonicalKey
        )
        return loaded
    }

    private suspend fun readSerializedUserCache(
        serialized: String,
        userCacheStorageTarget: UserCacheStorageTarget
    ): Cache? = try {
        tryLoadPersistedCache(serialized) ?: gson.fromJson(serialized, Cache::class.java)
    } catch (_: Exception) {
        keyValueStorage.removeValue(
            userCacheStorageTarget.storeName,
            userCacheStorageTarget.storageKey
        )
        null
    }

    private suspend fun cleanupReplacedAndEvictedCaches(
        currentScopedCacheKey: String,
        replacedFullUserCacheKey: String?
    ) {
        val evictedFullCacheKeys = cacheKeyMappingStore.removeOverflowEntries(
            maxEntries = MAX_USER_CACHE_ENTRIES,
            exemptScopedCacheKey = currentScopedCacheKey
        )

        (listOfNotNull(replacedFullUserCacheKey) + evictedFullCacheKeys)
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { fullUserCacheKey ->
                deleteUnreferencedUserCacheIfNeeded(fullUserCacheKey)
            }
    }

    private suspend fun deleteUnreferencedUserCacheIfNeeded(fullUserCacheKey: String) {
        userCacheStorage.withFullUserCacheKeyLock(fullUserCacheKey) {
            if (cacheKeyMappingStore.isFullUserCacheKeyReferenced(fullUserCacheKey)) {
                return@withFullUserCacheKeyLock
            }

            userCacheByKey.remove(fullUserCacheKey)
            userCacheStorage.delete(fullUserCacheKey)
        }
    }

    @VisibleForTesting
    internal fun getCacheKeyMappingSizeForTesting(): Int = SharedCacheKeyMapping.sizeForTesting()

    @VisibleForTesting
    internal fun getFullUserCacheKeyForTesting(scopedCacheKey: String): String? =
        cacheKeyMappingStore.readFullUserCacheKey(scopedCacheKey)

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
