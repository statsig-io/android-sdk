package com.statsig.androidsdk

import android.app.Application
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Callback interface for Statsig calls. All callbacks will be run on the main thread.
 */
@FunctionalInterface
interface IStatsigCallback {
  // DEPRECATED: DO NOT USE
  fun onStatsigInitialize()

  fun onStatsigInitialize(initDetails: InitializationDetails) {
    return this.onStatsigInitialize()
  }

  fun onStatsigUpdateUser()
}

/**
 * A singleton class for interfacing with gates, configs, and logging in the Statsig console
 */
object Statsig {

  @VisibleForTesting
  internal var client: StatsigClient = StatsigClient()
  internal var errorBoundary = ErrorBoundary()

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
    errorBoundary.setKey(sdkKey)
    errorBoundary.capture({
      client.initializeAsync(application, sdkKey, user, callback, options)
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

    errorBoundary.setKey(sdkKey)
    errorBoundary.captureAsync({
      client.initialize(application, sdkKey, user, options)
    })
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
    var result = false
    errorBoundary.capture({
      result = client.checkGate(gateName)
    })
    return result
  }

  /**
   * Check the value of a Feature Gate configured in the Statsig console for the initialized
   * user, but do not log an exposure
   * @param gateName the name of the feature gate to check
   * @return the value of the gate for the initialized user, or false if not found
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun checkGateWithExposureLoggingDisabled(gateName: String): Boolean {
    enforceInitialized("checkGateWithExposureLoggingDisabled")
    var result = false
    errorBoundary.capture({
      result = client.checkGateWithExposureLoggingDisabled(gateName)
    })
    return result
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
    var result: DynamicConfig? = null
    errorBoundary.capture({
      result = client.getConfig(configName)
    })
    return result ?: DynamicConfig.getUninitialized(configName)
  }

  /**
   * Check the value of a Dynamic Config configured in the Statsig console for the initialized
   * user, but do not log an exposure
   * @param configName the name of the Dynamic Config to check
   * @return the Dynamic Config the initialized user
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun getConfigWithExposureLoggingDisabled(configName: String): DynamicConfig {
    enforceInitialized("getConfigWithExposureLoggingDisabled")
    var result: DynamicConfig? = null
    errorBoundary.capture({
      result = client.getConfigWithExposureLoggingDisabled(configName)
    })
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
  @JvmOverloads
  @JvmStatic
  fun getExperiment(experimentName: String, keepDeviceValue: Boolean = false): DynamicConfig {
    enforceInitialized("getExperiment")
    var result: DynamicConfig? = null
    errorBoundary.capture({
      result = client.getExperiment(experimentName, keepDeviceValue)
    })
    return result ?: DynamicConfig.getUninitialized(experimentName)
  }

  /**
   * Check the value of an Experiment configured in the Statsig console for the initialized
   * user, but do not log an exposure
   * @param experimentName the name of the Experiment to check
   * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of the experiment
   * @return the Dynamic Config backing the experiment
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmOverloads
  @JvmStatic
  fun getExperimentWithExposureLoggingDisabled(
    experimentName: String,
    keepDeviceValue: Boolean = false
  ): DynamicConfig {
    enforceInitialized("getExperimentWithExposureLoggingDisabled")
    var result: DynamicConfig? = null
    errorBoundary.capture({
      result = client.getExperimentWithExposureLoggingDisabled(experimentName, keepDeviceValue)
    })
    return result ?: DynamicConfig.getUninitialized(experimentName)
  }

  /**
   * Check the value of a Layer configured in the Statsig console for the initialized
   * user
   * @param layerName the name of the Layer to check
   * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of any active experiments
   * @return the current layer values as a Layer object
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmOverloads
  @JvmStatic
  fun getLayer(layerName: String, keepDeviceValue: Boolean = false): Layer {
    enforceInitialized("getLayer")
    var result: Layer? = null
    errorBoundary.capture({
      result = client.getLayer(layerName, keepDeviceValue)
    })
    return result ?: Layer.getUninitialized(layerName)
  }

  /**
   * Check the value of a Layer configured in the Statsig console for the initialized
   * user, but never log exposures from this Layer
   * @param layerName the name of the Layer to check
   * @param keepDeviceValue whether the value returned should be kept for the user on the device for the duration of any active experiments
   * @return the current layer values as a Layer object
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmOverloads
  @JvmStatic
  fun getLayerWithExposureLoggingDisabled(
    layerName: String,
    keepDeviceValue: Boolean = false
  ): Layer {
    enforceInitialized("getLayerWithExposureLoggingDisabled")
    var result: Layer? = null
    errorBoundary.capture({
      result = client.getLayerWithExposureLoggingDisabled(layerName, keepDeviceValue)
    })
    return result ?: Layer.getUninitialized(layerName)
  }

  /**
   * Log an exposure for a given gate
   * @param gateName the name of the gate to log an exposure for
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun manuallyLogGateExposure(gateName: String) {
    enforceInitialized("manuallyLogGateExposure")
    errorBoundary.capture({
      client.logManualGateExposure(gateName)
    })
  }

  /**
   * Log an exposure for a given config
   * @param configName the name of the config to log an exposure for
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun manuallyLogConfigExposure(configName: String) {
    enforceInitialized("manuallyLogConfigExposure")
    errorBoundary.capture({
      client.logManualConfigExposure(configName)
    })
  }

  /**
   * Log an exposure for a given experiment
   * @param experimentName the name of the experiment to log an exposure for
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun manuallyLogExperimentExposure(experimentName: String, keepDeviceValue: Boolean = false) {
    enforceInitialized("manuallyLogExperimentExposure")
    errorBoundary.capture({
      client.logManualExperimentExposure(experimentName, keepDeviceValue)
    })
  }

  /**
   * Log an exposure for a given parameter in a given layer
   * @param layerName the relevant layer
   * @param parameterName the specific parameter
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun manuallyLogLayerParameterExposure(layerName: String, parameterName: String, keepDeviceValue: Boolean = false) {
    enforceInitialized("manuallyLogLayerParameterExposure")
    errorBoundary.capture({
      client.logManualLayerExposure(layerName, parameterName, keepDeviceValue)
    })
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
    errorBoundary.capture({
      client.logEvent(eventName, value, metadata)
    })
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
    errorBoundary.capture({
      client.logEvent(eventName, value, metadata)
    })
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
    errorBoundary.capture({
      client.logEvent(eventName, null, metadata)
    })
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
    errorBoundary.capture({
      client.updateUserAsync(user, callback)
    })
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
    errorBoundary.captureAsync({
      client.updateUser(user)
    })
  }

  @JvmSynthetic
  suspend fun shutdownSuspend() {
    enforceInitialized("shutdownSuspend")
    errorBoundary.captureAsync({
      client.shutdownSuspend()
      client = StatsigClient()
    })
  }

  /**
   * Informs the Statsig SDK that the client is shutting down to complete cleanup saving state
   * @throws IllegalStateException if the SDK has not been initialized
   */
  @JvmStatic
  fun shutdown() {
    enforceInitialized("shutdown")
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
    enforceInitialized("getStableID")
    var result: String = ""
    errorBoundary.capture({
      result = client.getStableID()
    })
    return result
  }

  /**
   * @param gateName the name of the gate you want to override
   * @param value the result to be returned when checkGate is called
   */
  @JvmStatic
  fun overrideGate(gateName: String, value: Boolean) {
    errorBoundary.capture({
      client.overrideGate(gateName, value)
    })
  }

  /**
   * @param configName the name of the config or experiment you want to override
   * @param value the resulting values to be returned when getConfig or getExperiment is called
   */
  @JvmStatic
  fun overrideConfig(configName: String, value: Map<String, Any>) {
    errorBoundary.capture({
      client.overrideConfig(configName, value)
    })
  }

  /**
   * @param layerName the name of the layer you want to override
   * @param value the resulting values to be returned in a Layer object when getLayer is called
   */
  @JvmStatic
  fun overrideLayer(layerName: String, value: Map<String, Any>) {
    errorBoundary.capture({
      client.overrideLayer(layerName, value)
    })
  }

  /**
   * @param name the name of the overridden gate, config or experiment you want to clear an override from
   */
  @JvmStatic
  fun removeOverride(name: String) {
    errorBoundary.capture({
      client.removeOverride(name)
    })
  }

  /**
   * Throw away all overridden values
   */
  @JvmStatic
  fun removeAllOverrides() {
    errorBoundary.capture({
      client.removeAllOverrides()
    })
  }

  /**
   * @return the overrides that are currently applied
   */
  @JvmStatic
  fun getAllOverrides(): StatsigOverrides {
    var result: StatsigOverrides? = null
    errorBoundary.capture({
      result = client.getStore().getAllOverrides()
    })
    return result ?: StatsigOverrides.empty()
  }

  private fun enforceInitialized(functionName: String) {
    client.enforceInitialized(functionName)
  }
}
