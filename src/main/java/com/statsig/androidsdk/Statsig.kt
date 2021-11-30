package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.google.gson.Gson
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

/**
 * Callback interface for Statsig calls. All callbacks will be run on the main thread.
 */
@FunctionalInterface
interface IStatsigCallback {
    fun onStatsigInitialize()

    fun onStatsigUpdateUser()
}

/**
 * A singleton class for interfacing with gates, configs, and logging in the Statsig console
 */
object Statsig {

    private const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"

    private val statsigJob = SupervisorJob()
    private val statsigScope = CoroutineScope(statsigJob + Dispatchers.Main)

    private lateinit var store: Store
    private lateinit var user: StatsigUser

    private var pollingJob: Job? = null
    private lateinit var application: Application
    private lateinit var sdkKey: String
    private lateinit var options: StatsigOptions
    private lateinit var lifecycleListener: StatsigActivityLifecycleListener

    internal lateinit var logger: StatsigLogger
    internal lateinit var statsigMetadata: StatsigMetadata

    @VisibleForTesting
    internal var statsigNetwork: StatsigNetwork = StatsigNetwork()

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
    @JvmOverloads
    @JvmStatic
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
    @JvmSynthetic
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
                this@Statsig.options.api,
                this@Statsig.sdkKey,
                this@Statsig.user,
                this@Statsig.statsigMetadata,
                this@Statsig.options.initTimeoutMs,
            )

            if (initResponse != null) {
                this@Statsig.store.save(initResponse)
            }

            this@Statsig.pollForUpdates()

            this@Statsig.statsigNetwork.apiRetryFailedLogs(Statsig.options.api, Statsig.sdkKey)
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
        if (this::store.isInitialized) {
            return
        }
        this.application = application
        this.sdkKey = sdkKey
        this.options = options
        this.user = normalizeUser(user)

        statsigMetadata = StatsigMetadata()
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
        store = Store(user?.userID)
    }

    /**
     * Check the value of a Feature Gate configured in the Statsig console for the initialized
     * user
     * @param gateName the name of the feature gate to check
     * @return the value of the gate for the initialized user, or false if not found
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmStatic
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
    @JvmStatic
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
    @JvmOverloads
    @JvmStatic
    fun getExperiment(experimentName: String, keepDeviceValue: Boolean = false): DynamicConfig {
        enforceInitialized("getExperiment")
        val res = store.getExperiment(experimentName, keepDeviceValue)
        statsigScope.launch {
            logger.logConfigExposure(experimentName, res.getRuleID(), res.getSecondaryExposures(), user)
        }
        return res
    }

    /**
     * Log an event to Statsig for the current user
     * @param eventName the name of the event to track
     * @param value an optional value assocaited with the event, for aggregations/analysis
     * @param metadata an optional map of metadata associated with the event
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmOverloads
    @JvmStatic
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
    @JvmOverloads
    @JvmStatic
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
    @JvmStatic
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
    @JvmStatic
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
    @JvmSynthetic // Hide this from Java files
    suspend fun updateUser(user: StatsigUser?) {
        enforceInitialized("updateUser")
        pollingJob?.cancel()
        this.user = normalizeUser(user)
        store.loadAndResetStickyUserValues(user?.userID)

        val initResponse = statsigNetwork.initialize(
            options.api,
            sdkKey,
            user,
            statsigMetadata,
            options.initTimeoutMs,
        )
        if (initResponse != null) {
            store.save(initResponse)
        }
        pollForUpdates()
    }

    @JvmSynthetic
    suspend fun shutdownSuspend() {
        enforceInitialized("shutdown")
        pollingJob?.cancel()
        logger.shutdown()
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmStatic
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
    @JvmStatic
    fun getStableID(): String {
        return statsigMetadata.stableID
    }

    private fun enforceInitialized(functionName: String) {
        if (!this::store.isInitialized) {
            throw IllegalStateException("The SDK must be initialized prior to invoking $functionName")
        }
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        var normalizedUser = user ?: StatsigUser("")
        normalizedUser.statsigEnvironment = options.getEnvironment()
        return normalizedUser
    }

    private fun pollForUpdates() {
        if (!Statsig.options.enableAutoValueUpdate) {
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
        val editor = getSharedPrefs().edit()
        editor.putString(key, value)
        editor.apply()
    }

    internal fun removeFromSharedPrefs(key: String) {
        val editor = getSharedPrefs().edit()
        editor.remove(key)
        editor.apply()
    }

    private class StatsigActivityLifecycleListener : Application.ActivityLifecycleCallbacks {
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
            statsigScope.launch {
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
