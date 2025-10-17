package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

internal data class StatsigPendingRequests(
    @SerializedName("requests") val requests: List<StatsigOfflineRequest>
)

internal data class StatsigOfflineRequest(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("requestBody") val requestBody: String,
    @SerializedName("retryCount") val retryCount: Int = 0
)
