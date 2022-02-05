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

    @VisibleForTesting
    internal val client: StatsigClient = StatsigClient()

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
        if (client.isInitialized()) {
            return
        }
        client.initializeAsync(application, sdkKey, user, callback, options)
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
        if (client.isInitialized()) {
            return
        }
        client.initialize(application, sdkKey, user, options)
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
        return client.checkGate(gateName)
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
        return client.getConfig(configName)
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
        return client.getExperiment(experimentName, keepDeviceValue)
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
        return client.logEvent(eventName, value, metadata)
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
        return client.logEvent(eventName, value, metadata)
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
        return client.logEvent(eventName, null, metadata)
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
        enforceInitialized("updateUserAsync")
        client.updateUserAsync(user, callback)
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
        client.updateUser(user)
    }

    @JvmSynthetic
    suspend fun shutdownSuspend() {
        enforceInitialized("shutdownSuspend")
        client.shutdownSuspend()
    }

    /**
     * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
     * @throws IllegalStateException if the SDK has not been initialized
     */
    @JvmStatic
    fun shutdown() {
        enforceInitialized("shutdown")
        client.shutdown()
    }

    /**
     * @return the current Statsig stableID
     */
    @JvmStatic
    fun getStableID(): String {
        enforceInitialized("getStableID")
        return client.getStableID()
    }

    fun overrideGate(gateName: String, value: Boolean) {
        client.getStore().overrideGate(gateName, value)
    }

    fun overrideConfig(configName: String, value: DynamicConfig) {
        client.getStore().overrideConfig(configName, value)
    }

    fun removeOverride(name: String) {
        client.getStore().removeOverride(name)
    }

    fun removeAllOverrides() {
        client.getStore().removeAllOverrides()
    }

    fun getAllOverrides(): StatsigOverrides {
        return client.getStore().getAllOverrides()
    }

    private fun enforceInitialized(functionName: String) {
        client.enforceInitialized(functionName)
    }
}
