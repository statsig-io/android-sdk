package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@VisibleForTesting
internal const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"
private const val STABLE_ID_KEY: String = "STABLE_ID"

class StatsigClient : LifecycleEventListener {
    private companion object {
        private const val TAG: String = "statsig::StatsigClient"
    }
    private lateinit var store: Store
    private lateinit var user: StatsigUser
    private lateinit var application: Application
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sdkKey: String
    private lateinit var lifecycleListener: StatsigActivityLifecycleListener
    private lateinit var logger: StatsigLogger

    // This field is named `statsigClientMetadata` instead of `statsigMetadata` so
    // @VisibleForTesting isn't transitively applied to the public getter (which returns a copy).
    @VisibleForTesting
    internal lateinit var statsigClientMetadata: StatsigMetadata
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private var lifetimeCallback: IStatsigLifetimeCallback? = null

    private lateinit var diagnostics: Diagnostics

    private var initTime: Long = System.currentTimeMillis()

    @VisibleForTesting
    var urlConnectionProvider: UrlConnectionProvider = defaultProvider

    private var dispatcherProvider = CoroutineDispatcherProvider()
    private val errorScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    internal var errorBoundary = ErrorBoundary(errorScope)

    private var pollingJob: Job? = null
    private var statsigJob = SupervisorJob()
    private var initialized = AtomicBoolean(false)
    private var isBootstrapped = AtomicBoolean(false)
    private var isInitializing = AtomicBoolean(false)
    private var onDeviceEvalAdapter: OnDeviceEvalAdapter? = null
    private val retryScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    private val gson = StatsigUtil.getOrBuildGson()

    @VisibleForTesting
    internal lateinit var statsigScope: CoroutineScope

    @VisibleForTesting
    internal lateinit var statsigNetwork: StatsigNetwork

    @VisibleForTesting
    internal lateinit var options: StatsigOptions

    /**
     * Initializes the SDK for the given user. Initialization is complete when the callback is
     * invoked
     * @param application
     * - the Android application Statsig is operating in
     * @param sdkKey
     * - a client or test SDK Key from the Statsig console
     * @param user
     * - the user to associate with feature gate checks, config fetches, and logging
     * @param callback
     * - a callback to execute when initialization is complete
     * @param options
     * - advanced SDK setup Checking Gates/Configs before initialization calls back will return
     * default values Logging Events before initialization will drop those events Subsequent calls to
     * initialize will be ignored. To switch the user or update user values, use updateUser()
     */
    fun initializeAsync(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions()
    ) {
        if (isInitializing.getAndSet(true)) {
            Log.w(
                TAG,
                "initializeAsync() called on a client that is already started or starting - this is a no-op."
            )
            return
        }
        errorBoundary.initialize(sdkKey, urlConnectionProvider)
        errorBoundary.capture(
            {
                val normalizedUser = setup(application, sdkKey, user, options)
                statsigScope.launch {
                    val initDetails = setupAsync(normalizedUser)
                    initDetails.duration = System.currentTimeMillis() - initTime
                    // The scope's dispatcher may change in the future.
                    // This "withContext" will ensure that initialization is complete when the
                    // callback is invoked
                    withContext(dispatcherProvider.main) {
                        try {
                            callback?.onStatsigInitialize(initDetails)
                            Log.v(TAG, "initializeAsync completed. Success: ${initDetails.success}")
                        } catch (e: Exception) {
                            throw ExternalException(e.message)
                        }
                    }
                }
            },
            recover = {
                logEndDiagnosticsWhenException(ContextType.INITIALIZE, it)
                try {
                    val initDetails =
                        InitializationDetails(
                            System.currentTimeMillis() - initTime,
                            false,
                            InitializeResponse.FailedInitializeResponse(
                                InitializeFailReason.InternalError,
                                it
                            )
                        )
                    callback?.onStatsigInitialize(initDetails)
                } catch (e: Exception) {
                    throw ExternalException(e.message)
                }
            }
        )
    }

    /**
     * Initializes the SDK for the given user
     * @param application
     * - the Android application Statsig is operating in
     * @param sdkKey
     * - a client or test SDK Key from the Statsig console
     * @param user
     * - the user to associate with feature gate checks, config fetches, and logging
     * @param options
     * - advanced SDK setup
     * @throws IllegalArgumentException if and Invalid SDK Key provided Checking Gates/Configs
     * before initialization calls back will return default values Logging Events before
     * initialization will drop those events Subsequent calls to initialize will be ignored. To
     * switch the user or update user values, use updateUser()
     */
    suspend fun initialize(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions()
    ): InitializationDetails? {
        if (isInitializing.getAndSet(true)) {
            Log.w(
                TAG,
                "initialize() called on a client that is already started or starting - this is a no-op."
            )
            return null
        }
        errorBoundary.initialize(sdkKey, urlConnectionProvider)
        return errorBoundary.captureAsync(
            {
                val normalizedUser = setup(application, sdkKey, user, options)
                val response = setupAsync(normalizedUser)
                response.duration = System.currentTimeMillis() - initTime
                Log.v(TAG, "initialize completed. Success: ${response.success}")
                return@captureAsync response
            },
            {
                logEndDiagnosticsWhenException(ContextType.INITIALIZE, it)
                return@captureAsync InitializationDetails(
                    System.currentTimeMillis() - initTime,
                    false,
                    InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.InternalError,
                        it
                    )
                )
            }
        )
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized user
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGate(gateName: String): Boolean {
        val functionName = "checkGate"
        enforceInitialized(functionName)

        var result: FeatureGate? = null
        errorBoundary.capture(
            {
                val gate = getFeatureGateEvaluation(gateName)
                logExposure(gateName, gate)
                result = gate
            },
            tag = functionName,
            configName = gateName
        )

        val res = result ?: FeatureGate.getError(gateName)
        options.evaluationCallback?.invoke(res)
        return res.getValue()
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized user,
     * but do not log an exposure
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGateWithExposureLoggingDisabled(gateName: String): Boolean {
        val functionName = "checkGateWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var result: FeatureGate? = null
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(gateName)
                val gate = getFeatureGateEvaluation(gateName)
                result = gate
            },
            tag = functionName,
            configName = gateName
        )
        val res = result ?: FeatureGate.getError(gateName)
        options.evaluationCallback?.invoke(res)
        return res.getValue()
    }

    fun getFeatureGate(gateName: String): FeatureGate {
        val functionName = "getFeatureGate"
        enforceInitialized(functionName)
        var result: FeatureGate? = null

        errorBoundary.capture(
            {
                val gate = getFeatureGateEvaluation(gateName)
                logExposure(gateName, gate)
                result = gate
            },
            tag = functionName,
            configName = gateName
        )

        val res = result ?: FeatureGate.getError(gateName)
        options.evaluationCallback?.invoke(res)
        return res
    }

    fun getFeatureGateWithExposureLoggingDisabled(gateName: String): FeatureGate {
        val functionName = "getFeatureGateWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var result: FeatureGate? = null
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(gateName)
                val gate = getFeatureGateEvaluation(gateName)
                result = gate
            },
            tag = functionName,
            configName = gateName
        )
        val res = result ?: FeatureGate.getError(gateName)
        options.evaluationCallback?.invoke(res)
        return res
    }

    /**
     * Check the value of a Dynamic Config configured in the Statsig console for the initialized
     * user
     * @param configName the name of the Dynamic Config to check
     * @return the Dynamic Config the initialized user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getConfig(configName: String): DynamicConfig {
        val functionName = "getConfig"
        enforceInitialized(functionName)
        var result: DynamicConfig = DynamicConfig.getError(configName)
        errorBoundary.capture(
            {
                val config = getDynamicConfigEvaluation(configName)
                logExposure(configName, config)
                result = config
            },
            tag = functionName,
            configName = configName
        )
        options.evaluationCallback?.invoke(result)
        return result
    }

    /**
     * Check the value of a Dynamic Config configured in the Statsig console for the initialized
     * user, but do not log an exposure
     * @param configName the name of the Dynamic Config to check
     * @return the Dynamic Config the initialized user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getConfigWithExposureLoggingDisabled(configName: String): DynamicConfig {
        val functionName = "getConfigWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var result: DynamicConfig = DynamicConfig.getError(configName)
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(configName)
                result = getDynamicConfigEvaluation(configName)
            },
            tag = functionName,
            configName = configName
        )
        options.evaluationCallback?.invoke(result)
        return result
    }

    /**
     * Check the value of an Experiment configured in the Statsig console for the initialized user
     * @param experimentName the name of the Experiment to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device
     * for the duration of the experiment
     * @return the Dynamic Config backing the experiment
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getExperiment(experimentName: String, keepDeviceValue: Boolean = false): DynamicConfig {
        val functionName = "getExperiment"
        enforceInitialized(functionName)
        var res: DynamicConfig = DynamicConfig.getError(experimentName)
        errorBoundary.capture(
            {
                res = getExperimentEvaluation(experimentName, keepDeviceValue)
                if (keepDeviceValue) {
                    updateStickyValues()
                }
                logExposure(experimentName, res)
            },
            tag = functionName,
            configName = experimentName
        )
        options.evaluationCallback?.invoke(res)
        return res
    }

    /**
     * Check the value of an Experiment configured in the Statsig console for the initialized user,
     * but do not log an exposure
     * @param experimentName the name of the Experiment to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device
     * for the duration of the experiment
     * @return the Dynamic Config backing the experiment
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getExperimentWithExposureLoggingDisabled(
        experimentName: String,
        keepDeviceValue: Boolean = false
    ): DynamicConfig {
        val functionName = "getExperimentWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var exp: DynamicConfig = DynamicConfig.getError(experimentName)
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(experimentName)
                exp = getExperimentEvaluation(experimentName, keepDeviceValue)
                if (keepDeviceValue) {
                    updateStickyValues()
                }
            },
            configName = experimentName,
            tag = functionName
        )
        options.evaluationCallback?.invoke(exp)
        return exp
    }

    /**
     * Check the value of an Layer configured in the Statsig console for the initialized user
     * @param layerName the name of the Layer to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device
     * for the duration of any active experiments
     * @return the current layer values as a Layer object
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getLayer(layerName: String, keepDeviceValue: Boolean = false): Layer {
        val functionName = "getLayer"
        enforceInitialized(functionName)
        var layer: Layer = Layer.getError(layerName)
        errorBoundary.capture(
            {
                layer = getLayerEvaluation(this, layerName, keepDeviceValue)
                if (keepDeviceValue) {
                    updateStickyValues()
                }
            },
            tag = functionName,
            configName = layerName
        )
        options.evaluationCallback?.invoke(layer)
        return layer
    }

    /**
     * Check the value of a Layer configured in the Statsig console for the initialized user, but
     * never log exposures from this Layer
     * @param layerName the name of the Layer to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device
     * for the duration of any active experiments
     * @return the current layer values as a Layer object
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getLayerWithExposureLoggingDisabled(
        layerName: String,
        keepDeviceValue: Boolean = false
    ): Layer {
        val functionName = "getLayerWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var layer: Layer = Layer.getError(layerName)
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(layerName)
                layer = getLayerEvaluation(null, layerName, keepDeviceValue)
                if (keepDeviceValue) {
                    updateStickyValues()
                }
            },
            tag = functionName,
            configName = layerName
        )
        options.evaluationCallback?.invoke(layer)
        return layer
    }

    fun getParameterStore(
        parameterStoreName: String,
        options: ParameterStoreEvaluationOptions? = null
    ): ParameterStore {
        val functionName = "getParameterStore"
        enforceInitialized(functionName)
        var paramStore = ParameterStore(
            this,
            HashMap(),
            parameterStoreName,
            store.getEvaluationDetails(false),
            options
        )
        errorBoundary.capture(
            {
                this.logger.addNonExposedCheck(parameterStoreName)
                paramStore = store.getParamStore(this, parameterStoreName, options)
                paramStore = onDeviceEvalAdapter?.getParamStore(this, paramStore) ?: paramStore
            },
            tag = functionName,
            configName = parameterStoreName
        )
        return paramStore
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event, for aggregations/analysis
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, value: Double? = null, metadata: Map<String, String>? = null) {
        val functionName = "logEvent"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val event = LogEvent(eventName)
                event.value = value
                event.metadata = metadata
                event.user = user

                if (!options.disableCurrentActivityLogging) {
                    lifecycleListener.getCurrentActivity()?.let {
                        event.statsigMetadata = mapOf("currentPage" to it.javaClass.simpleName)
                    }
                }

                statsigScope.launch { logger.log(event) }
            },
            tag = functionName
        )
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, value: String, metadata: Map<String, String>? = null) {
        val functionName = "logEvent"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val event = LogEvent(eventName)
                event.value = value
                event.metadata = metadata
                event.user = user
                statsigScope.launch { logger.log(event) }
            },
            tag = functionName
        )
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, metadata: Map<String, String>) {
        val functionName = "logEvent"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val event = LogEvent(eventName)
                event.value = null
                event.metadata = metadata
                event.user = user
                statsigScope.launch { logger.log(event) }
            },
            tag = functionName
        )
    }

    /**
     * Update the Statsig SDK with the given runtime-mutable options
     *
     * @param runtimeMutableOptions: the desired new options values
     */
    fun updateRuntimeOptions(runtimeMutableOptions: StatsigRuntimeMutableOptions) {
        val functionName = "updateRuntimeOptions"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                this.logger.setLoggingEnabled(runtimeMutableOptions.loggingEnabled)
                Log.v(TAG, "Runtime options successfully updated")
            },
            tag = functionName
        )
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same user
     * with additional properties. Will make network call in a separate coroutine. But fetch cached
     * values from memory synchronously.
     *
     * @param user the updated user
     * @param callback a callback to invoke upon update completion. Before this callback is invoked,
     * checking Gates will return false, getting Configs will return null, and Log Events will be
     * dropped
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun updateUserAsync(
        user: StatsigUser?,
        callback: IStatsigCallback? = null,
        values: Map<String, Any>? = null
    ) {
        val functionName = "updateUserAsync"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                diagnostics.markStart(
                    KeyType.OVERALL,
                    overrideContext = ContextType.UPDATE_USER
                )
                this.user = normalizeUser(user)
                this.resetUser()
                if (values != null) {
                    this.store.bootstrap(values, this.user)
                    logEndDiagnostics(true, ContextType.UPDATE_USER, null)
                    Log.v(TAG, "updateUserAsync completed with provided values")
                    callback?.onStatsigUpdateUser()
                    lifetimeCallback?.onValuesUpdated()
                } else {
                    this.store.loadCacheForCurrentUser()
                    statsigScope.launch {
                        updateUserImpl()
                        withContext(dispatcherProvider.main) {
                            try {
                                Log.v(TAG, "updateUserAsync completed")
                                callback?.onStatsigUpdateUser()
                            } catch (e: Exception) {
                                throw ExternalException(e.message)
                            }
                        }
                    }
                }
            },
            tag = functionName
        )
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same user
     * with additional properties
     *
     * @param user the updated user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    suspend fun updateUser(user: StatsigUser?, values: Map<String, Any>? = null) {
        enforceInitialized("updateUser")
        errorBoundary.captureAsync {
            diagnostics.markStart(KeyType.OVERALL, overrideContext = ContextType.UPDATE_USER)
            this.user = normalizeUser(user)
            this.resetUser()
            if (values != null) {
                this.store.bootstrap(values, this.user)
                logEndDiagnostics(true, ContextType.UPDATE_USER, null)
                Log.v(TAG, "updateUser completed with provided values")
            } else {
                this.store.loadCacheForCurrentUser()
                updateUserImpl()
                Log.v(TAG, "updateUser completed")
            }
        }
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for the current user
     *
     * @param callback a callback to invoke upon update completion. Before this callback is invoked,
     * checking Gates will return false, getting Configs will return null, and Log Events will be
     * dropped
     * @throws IllegalStateException if the SDK has not been initialized
     */
    suspend fun refreshCacheAsync(callback: IStatsigCallback? = null) {
        val functionName = "refreshCacheAsync"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                diagnostics.markStart(
                    KeyType.OVERALL,
                    overrideContext = ContextType.UPDATE_USER
                )
                statsigScope.launch {
                    updateUserImpl()
                    withContext(dispatcherProvider.main) {
                        try {
                            callback?.onStatsigUpdateUser()
                            Log.v(TAG, "refreshCacheAsync completed")
                        } catch (e: Exception) {
                            throw ExternalException(e.message)
                        }
                    }
                }
            },
            tag = functionName
        )
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for the current user
     *
     * @throws IllegalStateException if the SDK has not been initialized
     */
    suspend fun refreshCache() {
        enforceInitialized("refreshCache")
        errorBoundary.captureAsync {
            diagnostics.markStart(KeyType.OVERALL, overrideContext = ContextType.UPDATE_USER)
            updateUserImpl()
            Log.v(TAG, "refreshCache completed")
        }
    }

    /**
     * @return Initialize response currently being used in JSON and evaluation details
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getInitializeResponseJson(): ExternalInitializeResponse {
        val functionName = "getInitializeResponseJson"
        var result: ExternalInitializeResponse? = null
        enforceInitialized(functionName)
        errorBoundary.capture(
            { result = store.getCurrentCacheValuesAndEvaluationReason() },
            tag = functionName
        )
        return result ?: ExternalInitializeResponse.getUninitialized()
    }

    suspend fun shutdownSuspend() {
        enforceInitialized("shutdownSuspend")
        errorBoundary.captureAsync { shutdownImpl() }
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun shutdown() {
        enforceInitialized("shutdown")
        runBlocking { withContext(Dispatchers.Main.immediate) { shutdownSuspend() } }
    }

    suspend fun flush() {
        enforceInitialized("flush")
        errorBoundary.captureAsync {
            Log.v(TAG, "starting manual flush...")
            this.logger.flush()
        }
    }

    /**
     * @param gateName the name of the gate you want to override
     * @param value the result to be returned when checkGate is called
     */
    fun overrideGate(gateName: String, value: Boolean) {
        errorBoundary.capture(
            {
                this.store.overrideGate(gateName, value)
                statsigScope.launch { this@StatsigClient.store.saveOverridesToLocalStorage() }
                Log.v(TAG, "overrideGate() completed")
            },
            tag = "overrideGate"
        )
    }

    /**
     * @param configName the name of the config or experiment you want to override
     * @param value the resulting values to be returned when getConfig or getExperiment is called
     */
    fun overrideConfig(configName: String, value: Map<String, Any>) {
        errorBoundary.capture(
            {
                this.store.overrideConfig(configName, value)
                statsigScope.launch { this@StatsigClient.store.saveOverridesToLocalStorage() }
                Log.v(TAG, "overrideConfig() completed")
            },
            tag = "overrideConfig"
        )
    }

    /**
     * @param layerName the name of the layer you want to override
     * @param value the resulting values to be returned in a Layer object when getLayer is called
     */
    fun overrideLayer(configName: String, value: Map<String, Any>) {
        errorBoundary.capture(
            {
                this.store.overrideLayer(configName, value)
                statsigScope.launch { this@StatsigClient.store.saveOverridesToLocalStorage() }
                Log.v(TAG, "overrideLayer() completed")
            },
            tag = "overrideLayer"
        )
    }

    /**
     * @param name the name of the overridden gate, config or experiment you want to clear an
     * override from
     */
    fun removeOverride(name: String) {
        errorBoundary.capture({
            this.store.removeOverride(name)
            statsigScope.launch { this@StatsigClient.store.saveOverridesToLocalStorage() }
            Log.v(TAG, "removeOverride() completed")
        })
    }

    /** Throw away all overridden values */
    fun removeAllOverrides() {
        errorBoundary.capture({
            this.store.removeAllOverrides()
            statsigScope.launch { this@StatsigClient.store.saveOverridesToLocalStorage() }
            Log.v(TAG, "removeAllOverrides() completed")
        })
    }

    /** @return the current Statsig stableID, or an empty [String] prior to completion of async initialization */
    @Deprecated(
        "This function will be deprecated in a future release - the field is available in StatsigMetadata",
        ReplaceWith("getStatsigMetadata().stableID")
    )
    fun getStableID(): String {
        val functionName = "getStableID"
        enforceInitialized(functionName)
        var result = ""
        errorBoundary.capture({
            result = statsigClientMetadata.stableID ?: ""
        }, tag = "getStableID")
        return result
    }

    /**
     * @return the current Statsig sessionID
     */
    @Deprecated(
        "This function will be deprecated in a future release - the field is available in StatsigMetadata",
        ReplaceWith("getStatsigMetadata().stableID")
    )
    fun getSessionID(): String {
        val functionName = "getSessionID"
        enforceInitialized(functionName)
        var result = ""
        errorBoundary.capture({ result = statsigClientMetadata.sessionID }, tag = "getSessionID")
        return result
    }

    /**
     * @return the current [StatsigMetadata] of this client instance
     */
    fun getStatsigMetadata(): StatsigMetadata {
        val functionName = "getStatsigMetadata()"
        enforceInitialized(functionName)
        return statsigClientMetadata.copy()
    }

    /**
     * Log an exposure for a given config
     * @param configName the name of the config to log an exposure for
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun manuallyLogGateExposure(gateName: String) {
        val functionName = "logManualGateExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val gate = store.checkGate(gateName)
                logExposure(gateName, gate, isManual = true)
            },
            tag = functionName,
            configName = gateName
        )
    }

    fun manuallyLogConfigExposure(configName: String) {
        val functionName = "logManualConfigExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val config = store.getConfig(configName)
                logExposure(configName, config, isManual = true)
            },
            tag = functionName,
            configName = configName
        )
    }

    /**
     * Log an exposure for a given experiment
     * @param experimentName the name of the experiment to log an exposure for
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun manuallyLogExperimentExposure(configName: String, keepDeviceValue: Boolean) {
        val functionName = "logManualExperimentExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val exp = store.getExperiment(configName, keepDeviceValue)
                logExposure(configName, exp, isManual = true)
            },
            tag = functionName,
            configName = configName
        )
    }

    /**
     * Log an exposure for a given parameter in a given layer
     * @param layerName the relevant layer
     * @param parameterName the specific parameter
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun manuallyLogLayerParameterExposure(
        layerName: String,
        parameterName: String,
        keepDeviceValue: Boolean
    ) {
        val functionName = "logManualLayerExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                val layer = store.getLayer(null, layerName, keepDeviceValue)
                logLayerParameterExposure(layer, parameterName, isManual = true)
            },
            configName = layerName,
            tag = functionName
        )
    }

    fun manuallyLogGateExposure(gate: FeatureGate) {
        val functionName = "logManualGateExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                logExposure(gate.getName(), gate, isManual = true)
            },
            tag = functionName,
            configName = gate.getName()
        )
    }

    fun manuallyLogConfigExposure(config: DynamicConfig) {
        val functionName = "logManualConfigExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                logExposure(config.getName(), config, isManual = true)
            },
            tag = functionName,
            configName = config.getName()
        )
    }

    fun manuallyLogExperimentExposure(experiment: DynamicConfig) {
        val functionName = "logManualExperimentExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                logExposure(experiment.getName(), experiment, isManual = true)
            },
            tag = functionName,
            configName = experiment.getName()
        )
    }

    fun manuallyLogLayerParameterExposure(layer: Layer, parameterName: String) {
        val functionName = "logManualLayerExposure"
        enforceInitialized(functionName)
        errorBoundary.capture(
            {
                logLayerParameterExposure(layer, parameterName, isManual = true)
            },
            configName = layer.getName(),
            tag = functionName
        )
    }

    /** @return the overrides that are currently applied */
    fun getAllOverrides(): StatsigOverrides {
        var result: StatsigOverrides? = null
        errorBoundary.capture({ result = getStore().getAllOverrides() })
        return result ?: StatsigOverrides.empty()
    }

    fun openDebugView(context: Context, callback: DebugViewCallback? = null) {
        errorBoundary.capture({
            val currentValues = store.getCurrentValuesAsString()
            val map =
                mapOf(
                    "values" to currentValues,
                    "evalReason" to store.reason,
                    "user" to user.getCopyForEvaluation(),
                    "options" to options.toMap()
                )
            DebugView.show(context, sdkKey, map, callback)
        })
    }

    @VisibleForTesting
    internal suspend fun setupAsync(user: StatsigUser): InitializationDetails {
        return withContext(dispatcherProvider.io) {
            return@withContext errorBoundary.captureAsync(
                {
                    if (this@StatsigClient.isBootstrapped.get()) {
                        val evalDetails = store.getGlobalEvaluationDetails()
                        this@StatsigClient.diagnostics.markEnd(
                            KeyType.OVERALL,
                            evalDetails.reason === EvaluationReason.Bootstrap,
                            additionalMarker = Marker(evaluationDetails = evalDetails)
                        )
                        logger.logDiagnostics()
                        return@captureAsync InitializationDetails(
                            0,
                            true,
                            null
                        )
                    }
                    if (this@StatsigClient.options.loadCacheAsync) {
                        diagnostics.markStart(
                            KeyType.INITIALIZE,
                            StepType.LOAD_CACHE,
                            Marker(isBlocking = false)
                        )
                        this@StatsigClient.store.syncLoadFromLocalStorage()
                        diagnostics.markEnd(KeyType.INITIALIZE, true, StepType.LOAD_CACHE)
                    }
                    val initResponse =
                        if (this@StatsigClient.options.initializeOffline) {
                            store.getCachedInitializationResponse()
                        } else {
                            statsigNetwork.initialize(
                                this@StatsigClient.options.api,
                                user,
                                this@StatsigClient.store.getLastUpdateTime(
                                    this@StatsigClient.user
                                ),
                                this@StatsigClient.statsigClientMetadata,
                                statsigScope,
                                ContextType.INITIALIZE,
                                this@StatsigClient.diagnostics,
                                if (this@StatsigClient.options.disableHashing == true) {
                                    HashAlgorithm.NONE
                                } else {
                                    HashAlgorithm.DJB2
                                },
                                this@StatsigClient.store.getPreviousDerivedFields(
                                    this@StatsigClient.user
                                ),
                                this@StatsigClient.store.getFullChecksum(
                                    this@StatsigClient.user
                                )
                            )
                        }
                    if (initResponse is InitializeResponse.SuccessfulInitializeResponse &&
                        !options.initializeOffline
                    ) {
                        this@StatsigClient.diagnostics.markStart(
                            KeyType.INITIALIZE,
                            StepType.PROCESS
                        )
                        this@StatsigClient.store.save(initResponse, user)
                        if (initResponse.hasUpdates) {
                            statsigScope.launch(dispatcherProvider.main) {
                                lifetimeCallback?.onValuesUpdated()
                            }
                        }
                        this@StatsigClient.diagnostics.markEnd(
                            KeyType.INITIALIZE,
                            true,
                            StepType.PROCESS
                        )
                    }

                    this@StatsigClient.pollForUpdates()

                    if (this@StatsigClient.options.disableLogEventRetries != true) {
                        retryScope.launch(dispatcherProvider.io) {
                            try {
                                this@StatsigClient.statsigNetwork.apiRetryFailedLogs(
                                    this@StatsigClient.options.eventLoggingAPI,
                                    this@StatsigClient.options.logEventFallbackUrls
                                )
                            } catch (e: Exception) {
                                // best effort attempt to capture failed log events
                            }
                        }
                    }

                    val success =
                        initResponse is InitializeResponse.SuccessfulInitializeResponse
                    logEndDiagnostics(success, ContextType.INITIALIZE, initResponse)
                    InitializationDetails(
                        0,
                        success,
                        if (initResponse is InitializeResponse.FailedInitializeResponse) {
                            initResponse
                        } else {
                            null
                        }
                    )
                },
                { e: Exception ->
                    logEndDiagnosticsWhenException(ContextType.INITIALIZE, e)
                    InitializationDetails(
                        0,
                        false,
                        InitializeResponse.FailedInitializeResponse(
                            if (e is TimeoutCancellationException) {
                                InitializeFailReason.CoroutineTimeout
                            } else {
                                InitializeFailReason.InternalError
                            },
                            e
                        )
                    )
                }
            )
        }
    }

    @VisibleForTesting
    private fun setup(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions()
    ): StatsigUser {
        if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
            throw IllegalArgumentException(
                "Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console"
            )
        }
        initTime = System.currentTimeMillis()
        HttpUtils.maybeInitializeHttpClient(application)
        this.diagnostics = Diagnostics(options.getLoggingCopy())
        diagnostics.markStart(KeyType.OVERALL)
        this.application = application
        this.sharedPreferences =
            application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        this.sdkKey = sdkKey
        this.options = options
        val normalizedUser = normalizeUser(user)
        val initializeValues = options.initializeValues
        this.user = normalizedUser
        this.lifetimeCallback = options.lifetimeCallback
        exceptionHandler = errorBoundary.getExceptionHandler()
        statsigScope = CoroutineScope(statsigJob + dispatcherProvider.main + exceptionHandler)
        val networkFallbackResolver =
            NetworkFallbackResolver(
                sharedPreferences,
                statsigScope,
                gson
            )
        store = Store(statsigScope, sharedPreferences, normalizedUser, sdkKey, options, gson)
        // Prevent overwriting mocked network in tests
        if (!this::statsigNetwork.isInitialized) {
            statsigNetwork =
                StatsigNetwork(
                    application,
                    sdkKey,
                    sharedPreferences,
                    options,
                    networkFallbackResolver,
                    statsigScope,
                    store,
                    urlConnectionProvider,
                    gson
                )
        }
        statsigClientMetadata =
            if (options.optOutNonSdkMetadata) {
                createCoreStatsigMetadata()
            } else {
                createStatsigMetadata()
            }
        errorBoundary.setMetadata(statsigClientMetadata)

        onDeviceEvalAdapter = options.onDeviceEvalAdapter
        this.initialized.set(true)

        lifecycleListener = StatsigActivityLifecycleListener(application, this)

        logger =
            StatsigLogger(
                statsigScope,
                sdkKey,
                options.eventLoggingAPI,
                statsigClientMetadata,
                statsigNetwork,
                normalizedUser,
                diagnostics,
                options.logEventFallbackUrls,
                options.loggingEnabled,
                gson
            )
        populateStatsigMetadata()

        if (options.overrideStableID == null) {
            val stableID = getLocalStorageStableID()
            this@StatsigClient.statsigClientMetadata.overrideStableID(stableID)
        }

        if (!this@StatsigClient.options.loadCacheAsync) {
            diagnostics.markStart(
                KeyType.INITIALIZE,
                StepType.LOAD_CACHE,
                Marker(isBlocking = true)
            )
            this@StatsigClient.store.syncLoadFromLocalStorage()
            diagnostics.markEnd(KeyType.INITIALIZE, true, StepType.LOAD_CACHE)
        }

        if (initializeValues != null) {
            this@StatsigClient.store.bootstrap(initializeValues, this@StatsigClient.user)
            this@StatsigClient.isBootstrapped.set(true)
        }
        return normalizedUser
    }

    private fun resetUser() {
        errorBoundary.capture({
            logger.onUpdateUser()
            pollingJob?.cancel()
            this.store.resetUser(this.user)
        })
    }

    private suspend fun updateUserImpl() {
        withContext(dispatcherProvider.io) {
            errorBoundary.captureAsync(
                {
                    val sinceTime = store.getLastUpdateTime(this@StatsigClient.user)
                    val previousDerivedFields =
                        store.getPreviousDerivedFields(this@StatsigClient.user)
                    val fullChecksum = store.getFullChecksum(this@StatsigClient.user)
                    val initResponse =
                        statsigNetwork.initialize(
                            options.api,
                            this@StatsigClient.user,
                            sinceTime,
                            statsigClientMetadata,
                            statsigScope,
                            ContextType.UPDATE_USER,
                            diagnostics = this@StatsigClient.diagnostics,
                            hashUsed = if (this@StatsigClient.options.disableHashing ==
                                true
                            ) {
                                HashAlgorithm.NONE
                            } else {
                                HashAlgorithm.DJB2
                            },
                            previousDerivedFields = previousDerivedFields,
                            fullChecksum = fullChecksum
                        )
                    if (initResponse is InitializeResponse.SuccessfulInitializeResponse) {
                        diagnostics.markStart(
                            KeyType.INITIALIZE,
                            StepType.PROCESS,
                            overrideContext = ContextType.UPDATE_USER
                        )
                        store.save(initResponse, this@StatsigClient.user)
                        if (initResponse.hasUpdates) {
                            statsigScope.launch(dispatcherProvider.main) {
                                lifetimeCallback?.onValuesUpdated()
                            }
                        }
                        diagnostics.markEnd(
                            KeyType.INITIALIZE,
                            true,
                            StepType.PROCESS,
                            overrideContext = ContextType.UPDATE_USER
                        )
                    }
                    pollForUpdates()
                    logEndDiagnostics(
                        initResponse is InitializeResponse.SuccessfulInitializeResponse,
                        ContextType.UPDATE_USER,
                        initResponse
                    )
                },
                { logEndDiagnosticsWhenException(ContextType.UPDATE_USER, it) }
            )
        }
    }

    private fun getFeatureGateEvaluation(gateName: String): FeatureGate {
        val gate = store.checkGate(gateName)
        return onDeviceEvalAdapter?.getGate(gate, user) ?: gate
    }

    private fun getDynamicConfigEvaluation(configName: String): DynamicConfig {
        val config = store.getConfig(configName)
        return onDeviceEvalAdapter?.getDynamicConfig(config, user) ?: config
    }

    private fun getExperimentEvaluation(
        experimentName: String,
        keepDeviceValue: Boolean
    ): DynamicConfig {
        val experiment = store.getExperiment(experimentName, keepDeviceValue)
        return onDeviceEvalAdapter?.getDynamicConfig(experiment, user) ?: experiment
    }

    private fun getLayerEvaluation(
        client: StatsigClient?,
        layerName: String,
        keepDeviceValue: Boolean
    ): Layer {
        val layer = store.getLayer(client, layerName, keepDeviceValue)
        return onDeviceEvalAdapter?.getLayer(client, layer, user) ?: layer
    }

    internal fun logLayerParameterExposure(
        layer: Layer,
        parameterName: String,
        isManual: Boolean = false
    ) {
        if (!isInitialized()) {
            return
        }

        var exposures = layer.getUndelegatedSecondaryExposures()
        var allocatedExperiment = ""
        val isExplicit = layer.getExplicitParameters()?.contains(parameterName) == true
        if (isExplicit) {
            exposures = layer.getSecondaryExposures()
            allocatedExperiment = layer.getAllocatedExperimentName() ?: ""
        }

        logger.logLayerExposure(
            layer.getName(),
            layer.getRuleIDForParameter(parameterName),
            exposures,
            user,
            allocatedExperiment,
            parameterName,
            isExplicit,
            layer.getEvaluationDetails(),
            isManual
        )
    }

    internal fun getStore(): Store = store

    private fun logExposure(name: String, config: DynamicConfig, isManual: Boolean = false) {
        logger.logExposure(name, config, user, isManual)
    }

    private fun logExposure(name: String, gate: FeatureGate, isManual: Boolean = false) {
        logger.logExposure(name, gate, user, isManual)
    }

    private fun updateStickyValues() {
        statsigScope.launch(dispatcherProvider.io) { store.persistStickyValues() }
    }

    private fun getLocalStorageStableID(): String {
        var stableID = this@StatsigClient.sharedPreferences.getString(STABLE_ID_KEY, null)
        if (stableID == null) {
            stableID = UUID.randomUUID().toString()
            statsigScope.launch {
                withContext(dispatcherProvider.io) {
                    this@StatsigClient.saveStringToSharedPrefs(STABLE_ID_KEY, stableID)
                }
            }
        }
        return stableID
    }

    fun isInitialized(): Boolean = this.initialized.get()

    internal fun enforceInitialized(functionName: String) {
        if (!this.initialized.get()) {
            throw IllegalStateException(
                "The SDK must be initialized prior to invoking $functionName"
            )
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = StatsigUser(null)
        if (user != null) {
            normalizedUser = user.getCopyForEvaluation()
        }
        normalizedUser.statsigEnvironment = options.getEnvironment()
        options.userObjectValidator?.let { it(normalizedUser) }
        return normalizedUser
    }

    private fun pollForUpdates() {
        if (!this@StatsigClient.options.enableAutoValueUpdate) {
            return
        }
        pollingJob?.cancel()
        pollingJob =
            statsigNetwork
                .pollForChanges(
                    options.api,
                    user,
                    statsigClientMetadata,
                    (options.autoValueUpdateIntervalMinutes * 60 * 1000).toLong(),
                    options.initializeFallbackUrls
                )
                .onEach {
                    if (it != null) {
                        store.save(it, user)
                        if (it.hasUpdates) {
                            Log.i(TAG, "enableAutoValueUpdate: values changed")
                            withContext(dispatcherProvider.main) {
                                lifetimeCallback?.onValuesUpdated()
                            }
                        }
                    }
                }
                .launchIn(statsigScope)
    }

    private fun populateStatsigMetadata() {
        statsigClientMetadata.overrideStableID(options.overrideStableID)
        try {
            if (application.packageManager != null && !options.optOutNonSdkMetadata) {
                val pInfo: PackageInfo =
                    application.packageManager.getPackageInfo(application.packageName, 0)
                statsigClientMetadata.appVersion = pInfo.versionName
                statsigClientMetadata.appIdentifier = pInfo.packageName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // noop
        }
    }

    internal suspend fun saveStringToSharedPrefs(key: String, value: String) {
        StatsigUtil.saveStringToSharedPrefs(sharedPreferences, key, value)
    }

    private suspend fun shutdownImpl() {
        Log.v(TAG, "shutting down...")
        initialized.set(false)
        pollingJob?.cancel()
        logger.shutdown()
        lifecycleListener.shutdown()
        isBootstrapped.set(false)
        errorBoundary = ErrorBoundary(errorScope)
        statsigJob = SupervisorJob()
        isInitializing.set(false)
        Log.v(TAG, "shutdown completed.")
    }

    private fun logEndDiagnostics(
        success: Boolean,
        context: ContextType,
        initResponse: InitializeResponse?
    ) {
        this@StatsigClient.diagnostics.markEnd(
            KeyType.OVERALL,
            success,
            additionalMarker =
            Marker(
                evaluationDetails = store.getGlobalEvaluationDetails(),
                error =
                if (initResponse is InitializeResponse.FailedInitializeResponse) {
                    Diagnostics.formatFailedResponse(
                        initResponse
                    )
                } else {
                    null
                }
            ),
            overrideContext = context
        )
        logger.logDiagnostics(context)
    }

    private fun logEndDiagnosticsWhenException(context: ContextType, e: Exception?) {
        try {
            if (this::diagnostics.isInitialized && this::logger.isInitialized) {
                this@StatsigClient.diagnostics.markEnd(
                    KeyType.OVERALL,
                    false,
                    additionalMarker =
                    Marker(
                        error =
                        Marker.ErrorMessage(
                            message = "${e?.javaClass?.name}: ${e?.message}"
                        )
                    ),
                    overrideContext = context
                )
                this@StatsigClient.logger.logDiagnostics(context)
                statsigScope.launch(dispatcherProvider.io) { this@StatsigClient.logger.flush() }
            }
        } catch (e: Exception) {
            // no-op
        }
    }

    override fun onAppFocus() {
        if (this.options.disableLogEventRetries) {
            return
        }
        retryScope.launch(dispatcherProvider.io) {
            statsigNetwork.apiRetryFailedLogs(
                this@StatsigClient.options.eventLoggingAPI,
                this@StatsigClient.options.logEventFallbackUrls
            )
        }
    }

    override fun onAppBlur() {
        statsigScope.launch { logger.flush() }
    }
}
