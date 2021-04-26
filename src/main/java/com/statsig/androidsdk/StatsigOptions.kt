package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

/**
 * An object of properties for initializing the sdk with advanced options
 * @property disableCurrentActivityLogging prevents the SDK from auto logging the current, top-level
 * activity for event logs
 * @property api the api endpoint to use for initialization and logging
 */
data class StatsigOptions(
    @SerializedName("disableCurrentActivityLogging") val disableCurrentActivityLogging: Boolean = false,
    @SerializedName("api") val api: String = "https://api.statsig.com/v1"
) {

}