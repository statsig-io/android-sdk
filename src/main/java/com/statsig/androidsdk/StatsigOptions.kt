package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

enum class Tier {
    PRODUCTION,
    STAGING,
    DEVELOPMENT,
}

private const val TIER_KEY: String = "tier"

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 * @property disableCurrentActivityLogging prevents the SDK from auto logging the current, top-level
 * activity for event logs
 * @property initTimeoutMs the amount of time to wait for an initialize() response from the server
 * NOTE: gates/configs will still be fetched in the background if this time is exceeded, but the
 * callback to initialize will fire after, at most, the time specified
 */
class StatsigOptions(
    @SerializedName("api") var api: String = "https://api.statsig.com/v1",
    @SerializedName("disableCurrentActivityLogging") var disableCurrentActivityLogging: Boolean = false,
    @SerializedName("disableDiagnosticsLogging") var disableDiagnosticsLogging: Boolean = false,
    @SerializedName("initTimeoutMs") var initTimeoutMs: Long = 3000L,
    @SerializedName("enableAutoValueUpdate") var enableAutoValueUpdate: Boolean = false,
    @SerializedName("overrideStableID") var overrideStableID: String? = null,
    @SerializedName("loadCacheAsync") var loadCacheAsync: Boolean = false,
    @SerializedName("initializeValues") var initializeValues: Map<String, Any>? = null,
    @SerializedName("disableHashing") var disableHashing: Boolean? = false,
) {

    private var environment: MutableMap<String, String>? = null

    fun setTier(tier: Tier) {
        setEnvironmentParameter(TIER_KEY, tier.toString().lowercase())
    }

    fun setEnvironmentParameter(key: String, value: String) {
        val env = environment
        if (env == null) {
            environment = mutableMapOf(key to value)
            return
        }

        env[key] = value
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }

    internal fun toMap(): Map<String, Any?> {
        return mapOf(
            "api" to api,
            "disableCurrentActivityLogging" to disableCurrentActivityLogging,
            "disableDiagnosticsLogging" to disableDiagnosticsLogging,
            "initTimeoutMs" to initTimeoutMs,
            "enableAutoValueUpdate" to enableAutoValueUpdate,
            "overrideStableID" to overrideStableID,
            "loadCacheAsync" to loadCacheAsync,
            "initializeValues" to initializeValues,
            "disableHashing" to disableHashing,
            "environment" to environment,
        )
    }
}
