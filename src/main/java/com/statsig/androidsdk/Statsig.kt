package com.statsig.androidsdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val completableJob = Job()
private val coroutineScope = CoroutineScope(Dispatchers.Main + completableJob)

@FunctionalInterface
interface StatsigCallback {
    fun onStatsigReady()
}

/**
 * A singleton class for interfacing with gates, configs, and logging in the Statsig console
 */
class Statsig {

    companion object {

        private const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"

        private const val INITIALIZE_RESPONSE_KEY: String = "Statsig.INITIALIZE_RESPONSE"

        private var state: StatsigState? = null
        private var user: StatsigUser? = null

        private var callback: StatsigCallback? = null
        private lateinit var application: Application
        private lateinit var sdkKey: String
        private lateinit var options: StatsigOptions
        private lateinit var lifecycleListener: StatsigActivityLifecycleListener

        private lateinit var logger: StatsigLogger
        private lateinit var statsigMetadata: StatsigMetadata

        /**
         * Initializes the SDK for the given user.  Initialization is complete when the callback
         * is invoked
         * @param application - the Android application Statsig is operating in
         * @param sdkKey - a client or test SDK Key from the Statsig console
         * @param user - the user to associate with feature gate checks, config fetches, and logging
         * @param callback - invoked when initialization is complete
         * @param options - advanced SDK setup
         * Checking Gates/Configs before initialization calls back will return default values
         * Logging Events before initialization will drop those events
         * Susequent calls to initialize will be ignored.  To switch the user or update user values,
         * use updateUser()
         */
        @JvmOverloads
        @JvmStatic
        fun initialize(
            application: Application,
            sdkKey: String,
            user: StatsigUser? = null,
            callback: StatsigCallback? = null,
            options: StatsigOptions? = null
        ) {
            if (!sdkKey.startsWith("client-") && !sdkKey.startsWith("test-")) {
                throw IllegalArgumentException("Invalid SDK Key provided.  You must provide a client SDK Key from the API Key page of your Statsig console")
            }
            if (this::sdkKey.isInitialized) {
                // initialize has already been called
                return
            }
            this.application = application
            this.sdkKey = sdkKey
            this.user = user
            this.callback = callback
            if (options == null) {
                this.options = StatsigOptions()
            } else {
                this.options = options
            }

            this.statsigMetadata = StatsigMetadata()
            this.populateStatsigMetadata()

            this.lifecycleListener = StatsigActivityLifecycleListener()
            this.application.registerActivityLifecycleCallbacks(this.lifecycleListener)
            val sharedPrefs: SharedPreferences? = this.getSharedPrefs()
            this.logger = StatsigLogger(sdkKey, this.options.api, this.statsigMetadata, sharedPrefs)

            loadFromCache()

            var body = mapOf("user" to user, "statsigMetadata" to this.statsigMetadata)
            StatsigNetwork.apiPost(
                this.options.api,
                this.options.initTimeoutMs,
                "initialize",
                sdkKey,
                Gson().toJson(body),
                ::setState,
            )

            if (sharedPrefs != null) {
                StatsigNetwork.apiRetryFailedLogs(
                    this.options.api,
                    sdkKey,
                    sharedPrefs
                )
            }
        }

        /**
         * Check the value of a Feature Gate configured in the Statsig console for the initialized
         * user
         * @param gateName the name of the feature gate to check
         * @return the value of the gate for the initialized user, or false if not found
         * or the SDK is not initialized
         */
        @JvmStatic
        fun checkGate(gateName: String): Boolean {
            if (this.state == null) {
                return false
            }

            val gateValue = this.state!!.checkGate(StatsigUtil.getHashedString(gateName))
            if (gateValue != null) {
                this.logger.logGateExposure(gateName, gateValue.value, gateValue.rule, this.user)
                return gateValue.value
            }
            return false
        }

        /**
         * Check the value of a Dynamic Config configured in the Statsig console for the initialized
         * user
         * @param configName the name of the Dynamic Config to check
         * @return the Dynamic Config the initialized user, or null if not found (or the SDK
         * has not been initialized)
         */
        @JvmStatic
        fun getConfig(configName: String): DynamicConfig? {
            if (this.state == null) {
                return null
            }
            val config = this.state!!.getConfig(StatsigUtil.getHashedString(configName))
            if (config != null) {
                this.logger.logConfigExposure(configName, config.getRuleID(), this.user)
            }
            return config
        }

        /**
         * Log an event to Statsig for the current user
         * @param eventName the name of the event to track
         * @param value an optional value assocaited with the event, for aggregations/analysis
         * @param metadata an optional map of metadata associated with the event
         */
        @JvmOverloads
        @JvmStatic
        fun logEvent(
            eventName: String,
            value: Double? = null,
            metadata: Map<String, String>? = null
        ) {
            if (this.state == null) {
                return
            }
            var event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = this.user

            if (!this.options.disableCurrentActivityLogging) {
                var className = this.lifecycleListener.currentActivity?.javaClass?.simpleName
                if (className != null) {
                    event.statsigMetadata = mapOf("currentPage" to className)
                }
            }
            logger.log(event)
        }

        /**
         * Log an event to Statsig for the current user
         * @param eventName the name of the event to track
         * @param value an optional value assocaited with the event
         * @param metadata an optional map of metadata associated with the event
         */
        @JvmStatic
        fun logEvent(eventName: String, value: String, metadata: Map<String, String>? = null) {
            if (this.state == null) {
                return
            }
            var event = LogEvent(eventName)
            event.value = value
            event.metadata = metadata
            event.user = this.user
            logger.log(event)
        }

        /**
         * Log an event to Statsig for the current user
         * @param eventName the name of the event to track
         * @param metadata an optional map of metadata associated with the event
         */
        @JvmStatic
        fun logEvent(eventName: String, metadata: Map<String, String>) {
            if (this.state == null) {
                return
            }
            var event = LogEvent(eventName)
            event.value = null
            event.metadata = metadata
            event.user = this.user
            logger.log(event)
        }

        /**
         * Update the Statsig SDK with Feature Gate and Dynamic Configs for a new user, or the same
         * user with additional properties
         *
         * @param user the updated user
         * @param callback a callback to invoke upon update completion. Before this callback is
         * invoked, checking Gates will return false, getting Configs will return null, and
         * Log Events will be dropped
         */
        @JvmStatic
        fun updateUser(user: StatsigUser?, callback: StatsigCallback) {
            clearCache()
            this.state = null
            if (this.user?.userID !== user?.userID) {
                this.statsigMetadata.stableID = StatsigId.getNewStableID(this.getSharedPrefs())
                this.logger.onUpdateUser()
            } else {
                this.logger.flush()
            }
            this.user = user
            this.callback = callback
            this.statsigMetadata.sessionID = StatsigId.getNewSessionID()

            var body = mapOf("user" to user, "statsigMetadata" to this.statsigMetadata)
            StatsigNetwork.apiPost(
                this.options.api,
                this.options.initTimeoutMs,
                "initialize",
                sdkKey,
                Gson().toJson(body),
                ::setState,
            )
        }

        /**
         * Checks to see if the SDK is in a ready state to check gates and configs
         * If the SDK is initializing, or switching users, it is not in a ready state.
         * @return the ready state of the SDK
         */
        @JvmStatic
        fun isReady(): Boolean {
            return this.state != null
        }

        /**
         * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
         */
        @JvmStatic
        fun shutdown() {
            this.logger.flush()
        }

        private fun populateStatsigMetadata() {
            this.statsigMetadata.stableID = StatsigId.getStableID(this.getSharedPrefs())
            val stringID: Int? = this.application.applicationInfo?.labelRes;
            if (stringID != null) {
                if (stringID == 0) application.applicationInfo.nonLocalizedLabel.toString() else application.getString(
                    stringID
                )
            }

            try {
                if (application.packageManager != null) {
                    val pInfo: PackageInfo =
                        application.packageManager.getPackageInfo(application.packageName, 0)
                    this.statsigMetadata.appVersion = pInfo.versionName
                }
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }

        private fun loadFromCache() {
            val sharedPrefs = this.getSharedPrefs()
            if (sharedPrefs == null) {
                return
            }
            val cachedResponse = sharedPrefs.getString(INITIALIZE_RESPONSE_KEY, null) ?: return
            val json = Gson().fromJson(cachedResponse, InitializeResponse::class.java)
            this.state = StatsigState(json)
        }

        private fun saveToCache(initializeData: InitializeResponse) {
            val sharedPrefs = this.getSharedPrefs()
            if (sharedPrefs == null) {
                return
            }
            val json = Gson().toJson(initializeData)
            sharedPrefs.edit().putString(INITIALIZE_RESPONSE_KEY, json).apply()
        }

        private fun clearCache() {
            val sharedPrefs = this.getSharedPrefs()
            if (sharedPrefs == null) {
                return
            }
            sharedPrefs.edit().remove(INITIALIZE_RESPONSE_KEY).apply()
        }

        private fun setState(
            result: InitializeResponse?,
            dispatcher: CoroutineDispatcher? = Dispatchers.Main
        ) {
            if (result != null) {
                this.state = StatsigState(result)
                saveToCache(result)
            }
            val cb = this.callback
            this.callback = null
            if (cb != null) {
                if (dispatcher != null) {
                    coroutineScope.launch(dispatcher) {
                        cb.onStatsigReady()
                    }
                } else {
                    cb.onStatsigReady()
                }
            }
        }

        private fun getSharedPrefs(): SharedPreferences? {
            return application.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        }

        private class StatsigActivityLifecycleListener : Application.ActivityLifecycleCallbacks {
            public var currentActivity: Activity? = null

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
                shutdown()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
                currentActivity = null
            }

        }
    }

}