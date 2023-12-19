package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"
private const val STABLE_ID_KEY: String = "STABLE_ID"

class StatsigClient() : LifecycleEventListener {

    private lateinit var store: Store
    private lateinit var user: StatsigUser
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var lifecycleListener: StatsigActivityLifecycleListener
    private lateinit var logger: StatsigLogger
    private lateinit var statsigMetadata: StatsigMetadata
    private lateinit var exceptionHandler: CoroutineExceptionHandler
    private lateinit var statsigScope: CoroutineScope
    private lateinit var diagnostics: Diagnostics

    internal var errorBoundary = ErrorBoundary()

    private var pollingJob: Job? = null
    private var statsigJob = SupervisorJob()
    private var dispatcherProvider = CoroutineDispatcherProvider()
    private var initialized = AtomicBoolean(false)
    private var isBootstrapped = AtomicBoolean(false)

    @VisibleForTesting
    internal lateinit var statsigNetwork: StatsigNetwork

    @VisibleForTesting
    internal lateinit var options: StatsigOptions

    /**
     * Initializes the SDK for the given user.  Initialization is complete when the callback
     * is invoked
     * @param application - the Android application Statsig is operating in
     * @param sdkKey - a client or test SDK Key from the Statsig console
     * @param user - the user to associate with feature gate checks, config fetches, and logging
     * @param callback - a callback to execute when initialization is complete
     * @param options - advanced SDK setup
     * Checking Gates/Configs before initialization calls back will return default values
     * Logging Events before initialization will drop those events
     * Susequent calls to initialize will be ignored.  To switch the user or update user values,
     * use updateUser()
     */
    fun initializeAsync(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        if (isInitialized()) {
            return
        }
        errorBoundary.setKey(sdkKey)
        errorBoundary.capture({
            val normalizedUser = setup(application, sdkKey, user, options)
            statsigScope.launch {
                val initDetails = setupAsync(normalizedUser)
                // The scope's dispatcher may change in the future.
                // This "withContext" will ensure that initialization is complete when the callback is invoked
                withContext(dispatcherProvider.main) {
                    try {
                        callback?.onStatsigInitialize(initDetails)
                    } catch (e: Exception) {
                        throw ExternalException(e.message)
                    }
                }
            }
        })
    }

    /**
     * Initializes the SDK for the given user
     * @param application - the Android application Statsig is operating in
     * @param sdkKey - a client or test SDK Key from the Statsig console
     * @param user - the user to associate with feature gate checks, config fetches, and logging
     * @param options - advanced SDK setup
     * @throws IllegalArgumentException if and Invalid SDK Key provided
     * Checking Gates/Configs before initialization calls back will return default values
     * Logging Events before initialization will drop those events
     * Susequent calls to initialize will be ignored.  To switch the user or update user values,
     * use updateUser()
     */
    suspend fun initialize(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions(),
    ): InitializationDetails? {
        if (this@StatsigClient.isInitialized()) {
            return null
        }
        errorBoundary.setKey(sdkKey)
        return errorBoundary.captureAsync {
            val normalizedUser = setup(application, sdkKey, user, options)
            val response = setupAsync(normalizedUser)
            return@captureAsync response
        }
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized
     * user
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGate(gateName: String): Boolean {
        val functionName = "checkGate"
        enforceInitialized(functionName)
        var result = false
        errorBoundary.capture({
            val gate = store.checkGate(gateName)
            result = gate.value
            logExposure(gateName, gate)
        }, tag = functionName, configName = gateName)
        return result
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized
     * user, but do not log an exposure
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGateWithExposureLoggingDisabled(gateName: String): Boolean {
        val functionName = "checkGateWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var result = false
        errorBoundary.capture({
            val gate = store.checkGate(gateName)
            result = gate.value
        }, tag = functionName, configName = gateName)
        return result
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
        var result: DynamicConfig? = null
        errorBoundary.capture({
            result = store.getConfig(configName)
            logExposure(configName, result!!)
        }, tag = functionName, configName = configName)
        return result ?: DynamicConfig.getUninitialized(configName)
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
        var result: DynamicConfig? = null
        errorBoundary.capture({
            result = store.getConfig(configName)
        }, tag = functionName, configName = configName)
        return result ?: DynamicConfig.getUninitialized(configName)
    }

    /**
     * Check the value of an Experiment configured in the Statsig console for the initialized
     * user
     * @param experimentName the name of the Experiment to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of the experiment
     * @return the Dynamic Config backing the experiment
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getExperiment(experimentName: String, keepDeviceValue: Boolean = false): DynamicConfig {
        val functionName = "getExperiment"
        enforceInitialized(functionName)
        var res: DynamicConfig? = null
        errorBoundary.capture({
            res = store.getExperiment(experimentName, keepDeviceValue)
            updateStickyValues()
            logExposure(experimentName, res!!)
        }, tag = functionName, configName = experimentName)

        return res ?: DynamicConfig.getUninitialized(experimentName)
    }

    /**
     * Check the value of an Experiment configured in the Statsig console for the initialized
     * user, but do not log an exposure
     * @param experimentName the name of the Experiment to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of the experiment
     * @return the Dynamic Config backing the experiment
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getExperimentWithExposureLoggingDisabled(
        experimentName: String,
        keepDeviceValue: Boolean = false,
    ): DynamicConfig {
        val functionName = "getExperimentWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var exp: DynamicConfig? = null
        errorBoundary.capture({
            exp = store.getExperiment(experimentName, keepDeviceValue)
            updateStickyValues()
        }, configName = experimentName, tag = functionName)
        return exp ?: DynamicConfig.getUninitialized(experimentName)
    }

    /**
     * Check the value of an Layer configured in the Statsig console for the initialized
     * user
     * @param layerName the name of the Layer to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of any active experiments
     * @return the current layer values as a Layer object
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getLayer(layerName: String, keepDeviceValue: Boolean = false): Layer {
        val functionName = "getLayer"
        enforceInitialized(functionName)
        var layer: Layer? = null
        errorBoundary.capture({
            layer = store.getLayer(this, layerName, keepDeviceValue)
            updateStickyValues()
        }, tag = functionName, configName = layerName)

        return layer ?: Layer.getUninitialized(layerName)
    }

    /**
     * Check the value of a Layer configured in the Statsig console for the initialized
     * user, but never log exposures from this Layer
     * @param layerName the name of the Layer to check
     * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of any active experiments
     * @return the current layer values as a Layer object
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getLayerWithExposureLoggingDisabled(
        layerName: String,
        keepDeviceValue: Boolean = false,
    ): Layer {
        val functionName = "getLayerWithExposureLoggingDisabled"
        enforceInitialized(functionName)
        var layer: Layer? = null
        errorBoundary.capture({
            layer = store.getLayer(null, layerName, keepDeviceValue)
            updateStickyValues()
        }, tag = functionName, configName = layerName)

        return layer ?: Layer.getUninitialized(layerName)
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
        errorBoundary.capture({
            val event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = user

            if (!options.disableCurrentActivityLogging) {
                lifecycleListener.getCurrentActivity()?.let {
                    event.statsigMetadata = mapOf("currentPage" to it.javaClass.simpleName)
                }
            }

            statsigScope.launch {
                logger.log(event)
            }
        }, tag = functionName)
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
        errorBoundary.capture({
            val event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = user
            statsigScope.launch {
                logger.log(event)
            }
        }, tag = functionName)
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
        errorBoundary.capture({
            val event = LogEvent(eventName)
            event.value = null
            event.metadata = metadata
            event.user = user
            statsigScope.launch {
                logger.log(event)
            }
        }, tag = functionName)
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same
     * user with additional properties.
     * Will make network call in a separate coroutine.
     * But fetch cached values from memory synchronously.
     *
     * @param user the updated user
     * @param callback a callback to invoke upon update completion. Before this callback is
     * invoked, checking Gates will return false, getting Configs will return null, and
     * Log Events will be dropped
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun updateUserAsync(user: StatsigUser?, callback: IStatsigCallback? = null) {
        val functionName = "updateUserAsync"
        enforceInitialized(functionName)
        errorBoundary.capture({
            updateUserCache(user)
            statsigScope.launch {
                updateUserImpl()
                withContext(dispatcherProvider.main) {
                    try {
                        callback?.onStatsigUpdateUser()
                    } catch (e: Exception) {
                        throw ExternalException(e.message)
                    }
                }
            }
        }, tag = functionName)
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same
     * user with additional properties
     *
     * @param user the updated user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    suspend fun updateUser(user: StatsigUser?) {
        enforceInitialized("updateUser")
        errorBoundary.captureAsync {
            updateUserCache(user)
            updateUserImpl()
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
        errorBoundary.capture({
            result = store.getCurrentCacheValuesAndEvaluationReason()
        }, tag = functionName)
        return result ?: ExternalInitializeResponse.getUninitialized()
    }

    suspend fun shutdownSuspend() {
        enforceInitialized("shutdownSuspend")
        errorBoundary.captureAsync {
            shutdownImpl()
        }
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun shutdown() {
        enforceInitialized("shutdown")
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                shutdownSuspend()
            }
        }
    }

    /**
     * @param gateName the name of the gate you want to override
     * @param value the result to be returned when checkGate is called
     */
    fun overrideGate(gateName: String, value: Boolean) {
        errorBoundary.capture({
            this.store.overrideGate(gateName, value)
            statsigScope.launch {
                this@StatsigClient.store.saveOverridesToLocalStorage()
            }
        }, tag = "overrideGate")
    }

    /**
     * @param configName the name of the config or experiment you want to override
     * @param value the resulting values to be returned when getConfig or getExperiment is called
     */
    fun overrideConfig(configName: String, value: Map<String, Any>) {
        errorBoundary.capture({
            this.store.overrideConfig(configName, value)
            statsigScope.launch {
                this@StatsigClient.store.saveOverridesToLocalStorage()
            }
        }, tag = "overrideConfig")
    }

    /**
     * @param layerName the name of the layer you want to override
     * @param value the resulting values to be returned in a Layer object when getLayer is called
     */
    fun overrideLayer(configName: String, value: Map<String, Any>) {
        errorBoundary.capture({
            this.store.overrideLayer(configName, value)
            statsigScope.launch {
                this@StatsigClient.store.saveOverridesToLocalStorage()
            }
        }, tag = "overrideLayer")
    }

    /**
     * @param name the name of the overridden gate, config or experiment you want to clear an override from
     */
    fun removeOverride(name: String) {
        errorBoundary.capture({
            this.store.removeOverride(name)
            statsigScope.launch {
                this@StatsigClient.store.saveOverridesToLocalStorage()
            }
        })
    }

    /**
     * Throw away all overridden values
     */
    fun removeAllOverrides() {
        errorBoundary.capture({
            this.store.removeAllOverrides()
            statsigScope.launch {
                this@StatsigClient.store.saveOverridesToLocalStorage()
            }
        })
    }

    /**
     * @return the current Statsig stableID
     * Null prior to completion of async initialization
     */
    fun getStableID(): String {
        val functionName = "getStableID"
        enforceInitialized(functionName)
        var result = ""
        errorBoundary.capture({
            result = statsigMetadata.stableID ?: ""
        }, tag = "getStableID")
        return result
    }

    /**
     * Log an exposure for a given config
     * @param configName the name of the config to log an exposure for
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun manuallyLogGateExposure(gateName: String) {
        val functionName = "logManualGateExposure"
        enforceInitialized(functionName)
        errorBoundary.capture({
            val gate = store.checkGate(gateName)
            logExposure(gateName, gate, isManual = true)
        }, tag = functionName, configName = gateName)
    }

    fun manuallyLogConfigExposure(configName: String) {
        val functionName = "logManualConfigExposure"
        enforceInitialized(functionName)
        errorBoundary.capture({
            val config = store.getConfig(configName)
            logExposure(configName, config, isManual = true)
        }, tag = functionName, configName = configName)
    }

    /**
     * Log an exposure for a given experiment
     * @param experimentName the name of the experiment to log an exposure for
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun manuallyLogExperimentExposure(configName: String, keepDeviceValue: Boolean) {
        val functionName = "logManualExperimentExposure"
        enforceInitialized(functionName)
        errorBoundary.capture({
            val exp = store.getExperiment(configName, keepDeviceValue)
            logExposure(configName, exp, isManual = true)
        }, tag = functionName, configName = configName)
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
        keepDeviceValue: Boolean,
    ) {
        val functionName = "logManualLayerExposure"
        enforceInitialized(functionName)
        errorBoundary.capture({
            val layer = store.getLayer(null, layerName, keepDeviceValue)
            logLayerParameterExposure(layer, parameterName, isManual = true)
        }, configName = layerName, tag = functionName)
    }

    /**
     * @return the overrides that are currently applied
     */
    fun getAllOverrides(): StatsigOverrides {
        var result: StatsigOverrides? = null
        errorBoundary.capture({
            result = getStore().getAllOverrides()
        })
        return result ?: StatsigOverrides.empty()
    }

    fun openDebugView(context: Context) {
        errorBoundary.capture({
            val currentValues = store.getCurrentValuesAsString()
            val map = mapOf(
                "values" to currentValues,
                "evalReason" to store.reason,
                "user" to user.getCopyForEvaluation(),
                "options" to options.toMap(),
            )
            DebugView.show(context, sdkKey, map)
        })
    }

    @VisibleForTesting
    internal suspend fun setupAsync(user: StatsigUser): InitializationDetails {
        return withContext(dispatcherProvider.io) {
            val initStartTime = System.currentTimeMillis()
            return@withContext errorBoundary.captureAsync({
                if (this@StatsigClient.isBootstrapped.get()) {
                    return@captureAsync InitializationDetails(
                        System.currentTimeMillis() - initStartTime,
                        true,
                        null,
                    )
                }
                var success = false
                if (this@StatsigClient.options.loadCacheAsync) {
                    this@StatsigClient.store.syncLoadFromLocalStorage()
                }
                val initResponse = if (this@StatsigClient.options.initializeOffline) {
                    val cacheResponse = store.getCachedInitializationResponse()
                    if (cacheResponse != null) {
                        success = true
                    }
                    cacheResponse
                } else {
                    statsigNetwork.initialize(
                        this@StatsigClient.options.api,
                        user,
                        this@StatsigClient.store.getLastUpdateTime(this@StatsigClient.user),
                        this@StatsigClient.statsigMetadata,
                        this@StatsigClient.options.initTimeoutMs,
                        this@StatsigClient.getSharedPrefs(),
                        this@StatsigClient.diagnostics,
                        if (this@StatsigClient.options.disableHashing == true) HashAlgorithm.NONE else HashAlgorithm.DJB2,
                        this@StatsigClient.store.getPreviousDerivedFields(this@StatsigClient.user),
                    )
                }

                if (initResponse is InitializeResponse.SuccessfulInitializeResponse && initResponse.hasUpdates && !options.initializeOffline) {
                    this@StatsigClient.diagnostics.markStart(KeyType.INITIALIZE, StepType.PROCESS)
                    this@StatsigClient.store.save(initResponse, user)
                    this@StatsigClient.diagnostics.markEnd(
                        KeyType.INITIALIZE,
                        true,
                        StepType.PROCESS,
                    )
                    success = true
                }
                val duration = System.currentTimeMillis() - initStartTime

                this@StatsigClient.pollForUpdates()

                this@StatsigClient.statsigNetwork.apiRetryFailedLogs(this@StatsigClient.options.api)
                this@StatsigClient.diagnostics.markEnd(
                    KeyType.OVERALL,
                    success,
                    additionalMarker =
                    Marker(
                        evaluationDetails = store.getGlobalEvaluationDetails(),
                        error = if (initResponse is InitializeResponse.FailedInitializeResponse) {
                            Diagnostics.formatFailedResponse(
                                initResponse,
                            )
                        } else {
                            null
                        },
                    ),
                )
                logger.logDiagnostics()
                InitializationDetails(
                    duration,
                    success,
                    if (initResponse is InitializeResponse.FailedInitializeResponse) initResponse else null,
                )
            }, { e: Exception ->
                val duration = System.currentTimeMillis() - initStartTime
                this@StatsigClient.diagnostics.markEnd(
                    KeyType.OVERALL,
                    false,
                    additionalMarker = Marker(error = Marker.ErrorMessage(message = "${e.javaClass.name}: ${e.message}")),
                )
                logger.logDiagnostics()
                InitializationDetails(
                    duration,
                    false,
                    InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.InternalError,
                        e,
                    ),
                )
            })
        }
    }

    private fun setup(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions(),
    ): StatsigUser {
        if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
            throw IllegalArgumentException("Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console")
        }
        this.diagnostics = Diagnostics(options.disableDiagnosticsLogging)
        diagnostics.markStart(KeyType.OVERALL)
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        val normalizedUser = normalizeUser(user)
        val initializeValues = options.initializeValues
        this.user = normalizedUser
        // Prevent overwriting mocked network in tests
        if (!this::statsigNetwork.isInitialized) {
            statsigNetwork = StatsigNetwork(application, sdkKey, errorBoundary)
        }
        statsigMetadata = StatsigMetadata()
        errorBoundary.setMetadata(statsigMetadata)
        errorBoundary.setDiagnostics(diagnostics)
        populateStatsigMetadata()

        exceptionHandler = errorBoundary.getExceptionHandler()
        statsigScope = CoroutineScope(statsigJob + dispatcherProvider.main + exceptionHandler)

        lifecycleListener = StatsigActivityLifecycleListener(application, this)

        logger = StatsigLogger(
            statsigScope,
            sdkKey,
            options.api,
            statsigMetadata,
            statsigNetwork,
            normalizedUser,
            diagnostics,
        )
        store = Store(statsigScope, getSharedPrefs(), normalizedUser, sdkKey)

        if (options.overrideStableID == null) {
            val stableID = getLocalStorageStableID()
            this@StatsigClient.statsigMetadata.overrideStableID(stableID)
        }

        if (!this@StatsigClient.options.loadCacheAsync) {
            this@StatsigClient.store.syncLoadFromLocalStorage()
        }

        if (initializeValues != null) {
            this@StatsigClient.store.bootstrap(initializeValues, this@StatsigClient.user)
            this@StatsigClient.isBootstrapped.set(true)
        }

        this.initialized.set(true)
        return normalizedUser
    }

    private fun updateUserCache(user: StatsigUser?) {
        errorBoundary.capture({
            enforceInitialized("updateUser")
            logger.onUpdateUser()
            pollingJob?.cancel()
            this@StatsigClient.user = normalizeUser(user)
            store.loadAndResetForUser(this@StatsigClient.user)
        })
    }

    private suspend fun updateUserImpl() {
        withContext(dispatcherProvider.io) {
            errorBoundary.captureAsync {
                val sinceTime = store.getLastUpdateTime(this@StatsigClient.user)
                val previousDerivedFields = store.getPreviousDerivedFields(this@StatsigClient.user)

                val initResponse = statsigNetwork.initialize(
                    options.api,
                    this@StatsigClient.user,
                    sinceTime,
                    statsigMetadata,
                    options.initTimeoutMs,
                    getSharedPrefs(),
                    hashUsed = if (this@StatsigClient.options.disableHashing == true) HashAlgorithm.NONE else HashAlgorithm.DJB2,
                    previousDerivedFields = previousDerivedFields,
                )
                if (initResponse is InitializeResponse.SuccessfulInitializeResponse && initResponse.hasUpdates) {
                    store.save(initResponse, this@StatsigClient.user)
                }
                pollForUpdates()
            }
        }
    }

    internal fun logLayerParameterExposure(
        layer: Layer,
        parameterName: String,
        isManual: Boolean = false,
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
            layer.getRuleID(),
            exposures,
            user,
            allocatedExperiment,
            parameterName,
            isExplicit,
            layer.getEvaluationDetails(),
            isManual,
        )
    }

    internal fun getStore(): Store {
        return store
    }

    private fun logExposure(name: String, config: DynamicConfig, isManual: Boolean = false) {
        logger.logExposure(name, config, user, isManual)
    }

    private fun logExposure(name: String, gate: FeatureGate, isManual: Boolean = false) {
        logger.logExposure(name, gate, user, isManual)
    }

    private fun updateStickyValues() {
        statsigScope.launch(dispatcherProvider.io) {
            store.persistStickyValues()
        }
    }

    private fun getLocalStorageStableID(): String {
        var stableID = this@StatsigClient.getSharedPrefs().getString(STABLE_ID_KEY, null)
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

    internal fun isInitialized(): Boolean {
        return this.initialized.get()
    }

    internal fun enforceInitialized(functionName: String) {
        if (!this.initialized.get()) {
            throw IllegalStateException("The SDK must be initialized prior to invoking $functionName")
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = StatsigUser(null)
        if (user != null) {
            normalizedUser = user.getCopyForEvaluation()
        }
        normalizedUser.statsigEnvironment = options.getEnvironment()
        return normalizedUser
    }

    private fun pollForUpdates() {
        if (!this@StatsigClient.options.enableAutoValueUpdate) {
            return
        }
        pollingJob?.cancel()
        val sinceTime = store.getLastUpdateTime(user)
        pollingJob =
            statsigNetwork.pollForChanges(options.api, user, sinceTime, statsigMetadata).onEach {
                if (it?.hasUpdates == true) {
                    store.save(it, user)
                }
            }.launchIn(statsigScope)
    }

    private fun populateStatsigMetadata() {
        statsigMetadata.overrideStableID(options.overrideStableID)
        try {
            if (application.packageManager != null) {
                val pInfo: PackageInfo =
                    application.packageManager.getPackageInfo(application.packageName, 0)
                statsigMetadata.appVersion = pInfo.versionName
                statsigMetadata.appIdentifier = pInfo.packageName
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // noop
        }
    }

    internal fun getSharedPrefs(): SharedPreferences {
        return application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
    }

    internal suspend fun saveStringToSharedPrefs(key: String, value: String) {
        StatsigUtil.saveStringToSharedPrefs(getSharedPrefs(), key, value)
    }

    private suspend fun shutdownImpl() {
        pollingJob?.cancel()
        logger.shutdown()
        lifecycleListener.shutdown()
        initialized = AtomicBoolean()
        isBootstrapped = AtomicBoolean()
        errorBoundary = ErrorBoundary()
        statsigJob = SupervisorJob()
    }

    override fun onAppFocus() {
        statsigScope.launch {
            statsigNetwork.apiRetryFailedLogs(this@StatsigClient.options.api)
        }
    }

    override fun onAppBlur() {
        statsigScope.launch {
            logger.flush()
        }
    }
}
