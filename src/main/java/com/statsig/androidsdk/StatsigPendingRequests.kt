package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigPendingRequests(
    @SerializedName("requests") val requests: List<StatsigOfflineRequest>
)

data class StatsigOfflineRequest(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("requestBody") val requestBody: String
)