package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigOptions(
    @SerializedName("api") val api: String = "https://api.statsig.com/v1"
) {

}