package com.statsig.androidsdk

import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

private const val INITIALIZE_RESPONSE_KEY: String = "Statsig.INITIALIZE_RESPONSE"
private const val STICKY_USER_EXPERIMENTS_KEY: String = "Statsig.STICKY_USER_EXPERIMENTS"
private const val STICKY_DEVICE_EXPERIMENTS_KEY: String = "Statsig.STICKY_DEVICE_EXPERIMENTS"

private data class StickyUserExperiments(
    @SerializedName("user_id") val userID: String?,
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

internal class Store (userID: String?) {
    private val gson = Gson()
    private var cache: InitializeResponse
    private var stickyDeviceExperiments: MutableMap<String, APIDynamicConfig>
    private lateinit var stickyUserExperiments: StickyUserExperiments

    init {
        val cachedResponse = Statsig.getSharedPrefs().getString(INITIALIZE_RESPONSE_KEY, null)
        val cachedDeviceValues = Statsig.getSharedPrefs().getString(STICKY_DEVICE_EXPERIMENTS_KEY, null)

        cache = InitializeResponse(mapOf(), mapOf(), false, 0)
        if (cachedResponse != null) {
            cache = gson.fromJson(cachedResponse, InitializeResponse::class.java)
        }

        stickyDeviceExperiments = mutableMapOf()
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            stickyDeviceExperiments = gson.fromJson(cachedDeviceValues, type) ?: stickyDeviceExperiments
        }

        loadAndResetStickyUserValues(userID)
    }

    fun loadAndResetStickyUserValues(newUserID: String?) {
        val cachedUserValues = Statsig.getSharedPrefs().getString(STICKY_USER_EXPERIMENTS_KEY, null)
        stickyUserExperiments = StickyUserExperiments(newUserID, mutableMapOf())
        if (cachedUserValues != null) {
            stickyUserExperiments = gson.fromJson(cachedUserValues, StickyUserExperiments::class.java) ?: stickyUserExperiments
            if (stickyUserExperiments.userID != newUserID) {
                stickyUserExperiments = StickyUserExperiments(newUserID, mutableMapOf())
                Statsig.saveStringToSharedPrefs(STICKY_USER_EXPERIMENTS_KEY, gson.toJson(stickyUserExperiments))
            }
        }
    }

    fun save(data: InitializeResponse) {
        cache = data
        Statsig.saveStringToSharedPrefs(INITIALIZE_RESPONSE_KEY, gson.toJson(cache))
    }

    fun checkGate(gateName: String): APIFeatureGate {
        val hashName = StatsigUtil.getHashedString(gateName)
        if (
            cache.featureGates == null ||
            !cache.featureGates!!.containsKey(hashName)) {
            return APIFeatureGate(gateName, false, "")
        }
        return cache.featureGates!![hashName] ?: APIFeatureGate(gateName, false, "")
    }

    fun getConfig(configName: String): DynamicConfig {
        val hashName = StatsigUtil.getHashedString(configName)
        if (
            cache.configs == null ||
            !cache.configs!!.containsKey(hashName)) {
            return DynamicConfig(configName)
        }
        var config = cache.configs!![hashName]
        return DynamicConfig(configName, config?.value ?: mapOf(), config?.ruleID ?: "",
            config?.secondaryExposures ?: arrayOf())
    }

    fun getExperiment(experimentName: String, keepDeviceValue: Boolean): DynamicConfig {
        val hashName = StatsigUtil.getHashedString(experimentName)
        val stickyValue = stickyUserExperiments.experiments[hashName] ?: stickyDeviceExperiments[hashName]
        val latestValue = cache.configs?.get(hashName)

        // If flag is false, or experiment is NOT active, simply remove the sticky experiment value, and return the latest value
        if (!keepDeviceValue || latestValue?.isExperimentActive == false) {
            removeStickyValue(hashName)
            return getConfig(experimentName)
        }

        // If sticky value is already in cache, use it
        if (stickyValue != null) {
            return DynamicConfig(experimentName, stickyValue?.value ?: mapOf(), stickyValue?.ruleID ?: "",
                stickyValue?.secondaryExposures ?: arrayOf())
        }

        // If the user has NOT been exposed before, and is in this active experiment, then we save the value as sticky
        if (latestValue != null && latestValue.isExperimentActive && latestValue.isUserInExperiment) {
            if (latestValue?.isDeviceBased) {
                stickyDeviceExperiments[hashName] = latestValue
            } else {
                stickyUserExperiments.experiments[hashName] = latestValue
            }
            cacheStickyValues()
        }

        return getConfig(experimentName)
    }

    private fun removeStickyValue(key: String) {
        stickyUserExperiments.experiments.remove(key)
        stickyDeviceExperiments.remove(key)
        cacheStickyValues()
    }

    private fun cacheStickyValues() {
        Statsig.saveStringToSharedPrefs(STICKY_USER_EXPERIMENTS_KEY, gson.toJson(stickyUserExperiments))
        Statsig.saveStringToSharedPrefs(STICKY_DEVICE_EXPERIMENTS_KEY, gson.toJson(stickyDeviceExperiments))
    }
}
