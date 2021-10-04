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
    @SerializedName("initTimeoutMs") var initTimeoutMs: Long = 3000L,
    @SerializedName("enableAutoValueUpdate") var enableAutoValueUpdate: Boolean = false,
) {

    private var environment: MutableMap<String, String>? = null

    fun setTier(tier : Tier) {
        setEnvironmentParameter(TIER_KEY, tier.toString().toLowerCase())
    }

    fun setEnvironmentParameter(key: String, value: String){
        if (environment == null) {
            environment = mutableMapOf(key to value)
            return
        }
        environment!![key] = value
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }

}
