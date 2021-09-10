package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class LogEvent(@SerializedName("eventName") val eventName: String) {
    @SerializedName("value")
    var value: Any? = null

    @SerializedName("metadata")
    var metadata: Map<String, String?>? = null

    @SerializedName("user")
    var user: StatsigUser? = null
        set(value) {
            // We need to use a special copy of the user object that strips out private attributes for logging purposes
            field = value?.getCopyForLogging()
        }

    @SerializedName("time")
    val time: Long = System.currentTimeMillis()

    @SerializedName("statsigMetadata")
    var statsigMetadata: Map<String, String?>? = null

    @SerializedName("secondaryExposures")
    var secondaryExposures: Array<Map<String, String>>? = arrayOf()
}

