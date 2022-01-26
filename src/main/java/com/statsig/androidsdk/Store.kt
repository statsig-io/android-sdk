package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

private const val INITIALIZE_RESPONSE_KEY: String = "Statsig.INITIALIZE_RESPONSE_V2"
private const val STICKY_USER_EXPERIMENTS_KEY: String = "Statsig.STICKY_USER_EXPERIMENTS"
private const val STICKY_DEVICE_EXPERIMENTS_KEY: String = "Statsig.STICKY_DEVICE_EXPERIMENTS"
private const val STATSIG_NULL_USER: String = "Statsig.NULL_USER"

private data class StickyUserExperiments(
    @SerializedName("user_id") val userID: String?,
    @SerializedName("values") val experiments: MutableMap<String, APIDynamicConfig>,
)

internal class Store (private var userID: String?, private val sharedPrefs: SharedPreferences) {
    private val gson = Gson()
    private val defaultCache = InitializeResponse(mapOf(), mapOf(), false, 0)
    private var cacheById: MutableMap<String, InitializeResponse>
    private var stickyDeviceExperiments: MutableMap<String, APIDynamicConfig>
    private lateinit var stickyUserExperiments: StickyUserExperiments

    init {
        val cachedResponse = StatsigUtil.getFromSharedPrefs(sharedPrefs, INITIALIZE_RESPONSE_KEY)
        val cachedDeviceValues = StatsigUtil.getFromSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY)

        cacheById = mutableMapOf()

        if (cachedResponse != null) {
            val type = object : TypeToken<MutableMap<String, InitializeResponse>>() {}.type
            cacheById = gson.fromJson(cachedResponse, type) ?: cacheById
        }

        stickyDeviceExperiments = mutableMapOf()
        if (cachedDeviceValues != null) {
            val type = object : TypeToken<MutableMap<String, APIDynamicConfig>>() {}.type
            stickyDeviceExperiments = gson.fromJson(cachedDeviceValues, type) ?: stickyDeviceExperiments
        }


        loadAndResetStickyUserValues(userID)
    }

    fun loadAndResetForUser(newUserID: String?) {
        userID = newUserID
        loadAndResetStickyUserValues(newUserID)
    }

    private fun loadAndResetStickyUserValues(newUserID: String?) {
        val cachedUserValues = StatsigUtil.getFromSharedPrefs(sharedPrefs, STICKY_USER_EXPERIMENTS_KEY)
        stickyUserExperiments = StickyUserExperiments(newUserID, mutableMapOf())
        if (cachedUserValues != null) {
            stickyUserExperiments = gson.fromJson(cachedUserValues, StickyUserExperiments::class.java) ?: stickyUserExperiments
            if (stickyUserExperiments.userID != newUserID) {
                stickyUserExperiments = StickyUserExperiments(newUserID, mutableMapOf())
                StatsigUtil.saveStringToSharedPrefs(sharedPrefs, STICKY_USER_EXPERIMENTS_KEY, gson.toJson(stickyUserExperiments))
            }
        }
    }

    fun save(data: InitializeResponse) {
        cacheById[userID ?: STATSIG_NULL_USER] = data;
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, INITIALIZE_RESPONSE_KEY, gson.toJson(cacheById))
    }

    fun checkGate(gateName: String): APIFeatureGate {
        val hashName = StatsigUtil.getHashedString(gateName)
        if (
            cache()?.featureGates == null ||
            !cache()?.featureGates!!.containsKey(hashName)) {
            return APIFeatureGate(gateName, false, "")
        }
        return cache()?.featureGates!![hashName] ?: APIFeatureGate(gateName, false, "")
    }

    fun getConfig(configName: String): DynamicConfig {
        val hashName = StatsigUtil.getHashedString(configName)
        if (
            cache()?.configs == null ||
            !cache()?.configs!!.containsKey(hashName)) {
            return DynamicConfig(configName)
        }
        var config = cache()?.configs!![hashName]
        return DynamicConfig(configName, config?.value ?: mapOf(), config?.ruleID ?: "",
            config?.secondaryExposures ?: arrayOf())
    }

    fun getExperiment(experimentName: String, keepDeviceValue: Boolean): DynamicConfig {
        val hashName = StatsigUtil.getHashedString(experimentName)
        val stickyValue = stickyUserExperiments.experiments[hashName] ?: stickyDeviceExperiments[hashName]
        val latestValue = cache()?.configs?.get(hashName)

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
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, STICKY_USER_EXPERIMENTS_KEY, gson.toJson(stickyUserExperiments))
        StatsigUtil.saveStringToSharedPrefs(sharedPrefs, STICKY_DEVICE_EXPERIMENTS_KEY, gson.toJson(stickyDeviceExperiments))
    }

    private fun cache(): InitializeResponse {
        val cache = cacheById[userID ?: STATSIG_NULL_USER]
        return cache ?: defaultCache
    }
}
