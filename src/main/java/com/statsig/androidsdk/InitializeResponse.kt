package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

internal data class InitializeResponse(
    @SerializedName("feature_gates") val featureGates: Map<String, APIFeatureGate>?,
    @SerializedName("dynamic_configs") val configs: Map<String, APIDynamicConfig>?,
    @SerializedName("layer_configs") var layerConfigs: Map<String, APIDynamicConfig>?,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("time") val time: Long,
)

internal data class APIFeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("secondary_exposures") val secondaryExposures: Array<Map<String, String>> = arrayOf(),
)

internal data class APIDynamicConfig(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val ruleID: String?,
    @SerializedName("secondary_exposures") val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    @SerializedName("undelegated_secondary_exposures") val undelegatedSecondaryExposures: Array<Map<String, String>> = arrayOf(),
    @SerializedName("is_device_based") val isDeviceBased: Boolean = false,
    @SerializedName("is_user_in_experiment") val isUserInExperiment: Boolean = false,
    @SerializedName("is_experiment_active") val isExperimentActive: Boolean = false,
    @SerializedName("allocated_experiment_name") val allocatedExperimentName: String? = null,
    @SerializedName("explicit_parameters") val explicitParameters: Array<String> = arrayOf()
)

internal data class FeatureGate(
    val name: String,
    val details: EvaluationDetails,
    val value: Boolean = false,
    val ruleID: String = "",
    val secondaryExposures: Array<Map<String, String>> = arrayOf()
)