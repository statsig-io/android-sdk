package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class InitializeResponse(
    @SerializedName("feature_gates") val featureGates: Map<String, FeatureGate>?,
    @SerializedName("dynamic_configs") val configs: Map<String, Config>?
)

data class FeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean,
    @SerializedName("rule_id") val rule: String
)