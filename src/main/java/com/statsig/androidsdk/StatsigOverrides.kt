package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigOverrides(
    @SerializedName("gates")
    val gates: Map<String, Boolean>,

    @SerializedName("configs")
    val configs: Map<String, DynamicConfig>
    )
{}