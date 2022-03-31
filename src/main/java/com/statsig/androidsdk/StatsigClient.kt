package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.*

private const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"
private const val STABLE_ID_KEY: String = "STABLE_ID"

internal class StatsigClient()  {

    private lateinit var store: Store
    private lateinit var user: StatsigUser
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var options: StatsigOptions
    private lateinit var lifecycleListener: StatsigActivityLifecycleListener
    private lateinit var logger: StatsigLogger
    private lateinit var statsigMetadata: StatsigMetadata

    private var pollingJob: Job? = null
    private val statsigJob = SupervisorJob()
    private val statsigScope = CoroutineScope(statsigJob + Dispatchers.Main)

    @VisibleForTesting
    internal var statsigNetwork: StatsigNetwork = StatsigNetwork()

    fun initializeAsync(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        callback: IStatsigCallback? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        setup(application, sdkKey, user, options)
        statsigScope.launch {
            setupAsync()
            // The scope's dispatcher may change in the future. This "withContext" will ensure we keep true to the documentation above.
            withContext(Dispatchers.Main.immediate) {
                callback?.onStatsigInitialize()
            }
        }
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
    ) {
        withContext(Dispatchers.Main.immediate) { // Run on main thread immediately if already in it, else post to the main looper
            setup(application, sdkKey, user, options)
            setupAsync()
        }
    }

    private suspend fun setupAsync() {
        withContext(Dispatchers.Main.immediate) {
            val initResponse = statsigNetwork.initialize(
                this@StatsigClient.options.api,
                this@StatsigClient.sdkKey,
                this@StatsigClient.user,
                this@StatsigClient.statsigMetadata,
                this@StatsigClient.options.initTimeoutMs,
                this@StatsigClient.getSharedPrefs()
            )

            if (initResponse != null) {
                this@StatsigClient.store.save(initResponse)
            }

            this@StatsigClient.pollForUpdates()

            this@StatsigClient.statsigNetwork.apiRetryFailedLogs(this@StatsigClient.options.api, this@StatsigClient.sdkKey)
        }
    }

    private fun setup(
        application: Application,
        sdkKey: String,
        user: StatsigUser? = null,
        options: StatsigOptions = StatsigOptions(),
    ) {
        if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
            throw IllegalArgumentException("Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console")
        }
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        this.user = normalizeUser(user)

        val stableID = getLocalStorageStableID()
        statsigMetadata = StatsigMetadata(stableID)
        populateStatsigMetadata()

        lifecycleListener = StatsigActivityLifecycleListener()
        application.registerActivityLifecycleCallbacks(lifecycleListener)
        logger = StatsigLogger(
            statsigScope,
            sdkKey,
            options.api,
            statsigMetadata,
            statsigNetwork
        )
        store = Store(user?.userID, user?.customIDs, getSharedPrefs())
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized
     * user
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun checkGate(gateName: String): Boolean {
        enforceInitialized("checkGate")
        val res = store.checkGate(gateName)
        statsigScope.launch {
            logger.logGateExposure(gateName, res.value, res.ruleID, res.secondaryExposures, user)
        }
        return res.value
    }

    /**
     * Check the value of a Dynamic Config configured in the Statsig console for the initialized
     * user
     * @param configName the name of the Dynamic Config to check
     * @return the Dynamic Config the initialized user
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun getConfig(configName: String): DynamicConfig {
        enforceInitialized("getConfig")
        val res = store.getConfig(configName)
        statsigScope.launch {
            logger.logConfigExposure(configName, res.getRuleID(), res.getSecondaryExposures(), user)
        }
        return res
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
        enforceInitialized("getExperiment")
        val res = store.getExperiment(experimentName, keepDeviceValue)
        statsigScope.launch {
            logger.logConfigExposure(experimentName, res.getRuleID(), res.getSecondaryExposures(), user)
        }
        return res
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
        enforceInitialized("getLayer")
        return store.getLayer(this, layerName, keepDeviceValue)
    }

    internal fun logLayerParameterExposure(layer: Layer, parameterName: String) {
        if (!isInitialized()) {
            return;
        }

        statsigScope.launch {
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
                isExplicit
            )
        }
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event, for aggregations/analysis
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, value: Double? = null, metadata: Map<String, String>? = null) {
        enforceInitialized("logEvent")
        val event = LogEvent(eventName)
        event.value = value
        event.metadata = metadata
        event.user = user

        if (!options.disableCurrentActivityLogging) {
            val className = lifecycleListener.currentActivity?.javaClass?.simpleName
            if (className != null) {
                event.statsigMetadata = mapOf("currentPage" to className)
            }
        }
        statsigScope.launch {
            logger.log(event)
        }
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, value: String, metadata: Map<String, String>? = null) {
        enforceInitialized("logEvent")
        val event = LogEvent(eventName)
        event.value = value
        event.metadata = metadata
        event.user = user
        statsigScope.launch {
            logger.log(event)
        }
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun logEvent(eventName: String, metadata: Map<String, String>) {
        enforceInitialized("logEvent")
        val event = LogEvent(eventName)
        event.value = null
        event.metadata = metadata
        event.user = user
        statsigScope.launch {
            logger.log(event)
        }
    }

    /**
     * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same
     * user with additional properties
     *
     * @param user the updated user
     * @param callback a callback to invoke upon update completion. Before this callback is
     * invoked, checking Gates will return false, getting Configs will return null, and
     * Log Events will be dropped
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun updateUserAsync(user: StatsigUser?, callback: IStatsigCallback? = null) {
        statsigScope.launch {
            updateUser(user)
            withContext(Dispatchers.Main.immediate) {
                callback?.onStatsigUpdateUser()
            }
        }
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
        logger.onUpdateUser()
        pollingJob?.cancel()
        this.user = normalizeUser(user)
        store.loadAndResetForUser(user?.userID, user?.customIDs)

        val initResponse = statsigNetwork.initialize(
            options.api,
            sdkKey,
            user,
            statsigMetadata,
            options.initTimeoutMs,
            getSharedPrefs(),
        )
        if (initResponse != null) {
            store.save(initResponse)
        }
        pollForUpdates()
    }

    suspend fun shutdownSuspend() {
        enforceInitialized("shutdown")
        pollingJob?.cancel()
        logger.shutdown()
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    fun shutdown() {
        runBlocking {
            withContext(Dispatchers.Main.immediate) {
                shutdownSuspend()
            }
        }
    }

    /**
     * @return the current Statsig stableID
     */
    fun getStableID(): String {
        return statsigMetadata.stableID
    }

    internal fun getStore(): Store {
        return store;
    }

    private fun getLocalStorageStableID(): String {
        var stableID = this@StatsigClient.getSharedPrefs().getString(STABLE_ID_KEY, null)
        if (stableID == null) {
            stableID = UUID.randomUUID().toString()
            this@StatsigClient.saveStringToSharedPrefs(STABLE_ID_KEY, stableID)
        }
        return stableID
    }

    internal fun isInitialized(): Boolean {
        return this::store.isInitialized
    }

    internal fun enforceInitialized(functionName: String) {
        if (!this::store.isInitialized) {
            throw IllegalStateException("The SDK must be initialized prior to invoking $functionName")
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = StatsigUser("")
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
        pollingJob = statsigNetwork.pollForChanges(
            options.api,
            sdkKey,
            user,
            statsigMetadata
        ).onEach {
            if (it?.hasUpdates == true) {
                store.save(it)
            }
        }.launchIn(statsigScope)
    }

    private fun populateStatsigMetadata() {
        statsigMetadata.overrideStableID(options.overrideStableID)

        val stringID: Int? = application.applicationInfo?.labelRes
        if (stringID != null) {
            if (stringID == 0) {
                application.applicationInfo.nonLocalizedLabel.toString()
            } else {
                application.getString(stringID)
            }
        }

        try {
            if (application.packageManager != null) {
                val pInfo: PackageInfo =
                    application.packageManager.getPackageInfo(application.packageName, 0)
                statsigMetadata.appVersion = pInfo.versionName
            }
        } catch (e: PackageManager.NameNotFoundException) {
        }
    }

    internal fun getSharedPrefs(): SharedPreferences {
        return application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
    }

    internal fun saveStringToSharedPrefs(key: String, value: String) {
        StatsigUtil.saveStringToSharedPrefs(getSharedPrefs(), key, value)
    }

    internal fun removeFromSharedPrefs(key: String) {
        StatsigUtil.removeFromSharedPrefs(getSharedPrefs(), key)
    }

    private inner class StatsigActivityLifecycleListener : Application.ActivityLifecycleCallbacks {
        var currentActivity: Activity? = null

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            currentActivity = activity
        }

        override fun onActivityStarted(activity: Activity) {
            currentActivity = activity
        }

        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {
        }

        override fun onActivityStopped(activity: Activity) {
            currentActivity = null
            this@StatsigClient.statsigScope.launch {
                logger.flush()
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        }

        override fun onActivityDestroyed(activity: Activity) {
            currentActivity = null
        }
    }
}