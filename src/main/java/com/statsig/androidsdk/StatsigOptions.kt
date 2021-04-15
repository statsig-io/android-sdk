package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 */
data class StatsigOptions(
    @SerializedName("api") val api: String = "https://api.statsig.com/v1"
) {

}