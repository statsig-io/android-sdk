package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

internal data class InitializeResponse(
    @SerializedName("feature_gates") val featureGates: Map<String, APIFeatureGate>?,
    @SerializedName("dynamic_configs") val configs: Map<String, APIDynamicConfig>?,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("time") val time: Long,
)

internal data class APIFeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("rule_id") val ruleID: String = ""
)

internal data class APIDynamicConfig(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val ruleID: String?
)
