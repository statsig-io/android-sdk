package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class InitializeResponse(
    @SerializedName("featureGates") val featureGates: Map<String, FeatureGate>?,
    @SerializedName("configs") val configs: Map<String, Config>?
)

data class FeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean,
    @SerializedName("rule") val rule: String
)