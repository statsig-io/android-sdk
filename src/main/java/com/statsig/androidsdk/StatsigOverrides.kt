package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigOverrides(
    @SerializedName("gates")
    val gates: MutableMap<String, Boolean>,

    @SerializedName("configs")
    val configs: MutableMap<String, Map<String, Any>>
    )
{}