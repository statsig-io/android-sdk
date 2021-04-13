package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigUser(@SerializedName("userID") var userID: String? = null) {
    @SerializedName("email") var email: String? = null
    @SerializedName("ip") var ip: String? = null
    @SerializedName("userAgent") var userAgent: String? = null
    @SerializedName("country") var country: String? = null
    @SerializedName("locale") var locale: String? = null
    @SerializedName("clientVersion") var clientVersion: String? = null
    @SerializedName("custom") var custom: Map<String, Any>? = null
}