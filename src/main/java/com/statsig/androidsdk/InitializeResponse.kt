package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import java.lang.Exception

enum class InitializeFailReason {
    CoroutineTimeout,
    NetworkTimeout,
    NetworkError,
    InternalError,
}

sealed class InitializeResponse {
    data class FailedInitializeResponse(
        @SerializedName("reason") val reason: InitializeFailReason,
        @SerializedName("exception") val exception: Exception? = null,
        @SerializedName("statusCode") val statusCode: Int? = null,
    ) : InitializeResponse()
    internal data class SuccessfulInitializeResponse(
        @SerializedName("feature_gates") val featureGates: Map<String, APIFeatureGate>?,
        @SerializedName("dynamic_configs") val configs: Map<String, APIDynamicConfig>?,
        @SerializedName("layer_configs") var layerConfigs: Map<String, APIDynamicConfig>?,
        @SerializedName("has_updates") val hasUpdates: Boolean,
        @SerializedName("hash_used") val hashUsed: HashAlgorithm? = null,
        @SerializedName("time") val time: Long,
        @SerializedName("derived_fields") val derivedFields: Map<String, String>?,
        @SerializedName("param_stores") val paramStores: Map<String, Map<String, Map<String, Any>>>? = null,
        @SerializedName("full_checksum") val fullChecksum: String? = null,
        @SerializedName("sdk_flags") val sdkFlags: Map<String, Any>? = null,
    ) : InitializeResponse()
}

internal data class APIFeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("secondary_exposures") val secondaryExposures: Array<Map<String, String>>? = arrayOf(),
    @SerializedName("id_type") val idType: String? = null,
)

internal data class APIDynamicConfig(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("secondary_exposures") val secondaryExposures: Array<Map<String, String>>? = arrayOf(),
    @SerializedName("undelegated_secondary_exposures") val undelegatedSecondaryExposures: Array<Map<String, String>>? = arrayOf(),
    @SerializedName("is_device_based") val isDeviceBased: Boolean = false,
    @SerializedName("is_user_in_experiment") val isUserInExperiment: Boolean = false,
    @SerializedName("is_experiment_active") val isExperimentActive: Boolean = false,
    @SerializedName("allocated_experiment_name") val allocatedExperimentName: String? = null,
    @SerializedName("explicit_parameters") val explicitParameters: Array<String>? = arrayOf(),
    @SerializedName("passed") val rulePassed: Boolean? = null,
)
