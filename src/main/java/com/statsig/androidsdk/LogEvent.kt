package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class LogEvent(@SerializedName("eventName") val eventName: String) {
    @SerializedName("value") var value: Any? = null
    @SerializedName("metadata") var metadata: Map<String, String>? = null
    @SerializedName("user") var user: StatsigUser? = null
    @SerializedName("time") val time: Long = System.currentTimeMillis() / 1000
}

