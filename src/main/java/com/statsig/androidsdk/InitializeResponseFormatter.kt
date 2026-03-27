package com.statsig.androidsdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.LinkedHashMap

internal const val INIT_RESPONSE_FORMAT_V2 = "init-v2"
internal const val PERSISTED_CACHE_SCHEMA_V2 = 2

internal data class PersistedFeatureGate(
    @SerializedName("v") val value: Boolean? = null,
    @SerializedName("r") val ruleID: String? = null,
    @SerializedName("gn") val groupName: String? = null,
    @SerializedName("s") val secondaryExposures: List<String>? = null,
    @SerializedName("i") val idType: String? = null
)

internal data class PersistedDynamicConfig(
    @SerializedName("v") val valueKey: String? = null,
    @SerializedName("r") val ruleID: String? = null,
    @SerializedName("gn") val groupName: String? = null,
    @SerializedName("s") val secondaryExposures: List<String>? = null,
    @SerializedName("us") val undelegatedSecondaryExposures: List<String>? = null,
    @SerializedName("d") val isDeviceBased: Boolean? = null,
    @SerializedName("ue") val isUserInExperiment: Boolean? = null,
    @SerializedName("ea") val isExperimentActive: Boolean? = null,
    @SerializedName("ae") val allocatedExperimentName: String? = null,
    @SerializedName("ep") val explicitParameters: Array<String>? = null,
    @SerializedName("p") val rulePassed: Boolean? = null,
    @SerializedName("pr") val parameterRuleIDs: Map<String, String>? = null
)

internal data class PersistedInitializeResponse(
    @SerializedName("feature_gates")
    val featureGates: Map<String, PersistedFeatureGate>? = null,
    @SerializedName("dynamic_configs")
    val configs: Map<String, PersistedDynamicConfig>? = null,
    @SerializedName("layer_configs")
    val layerConfigs: Map<String, PersistedDynamicConfig>? = null,
    @SerializedName("has_updates")
    val hasUpdates: Boolean,
    @SerializedName("hash_used")
    val hashUsed: HashAlgorithm? = null,
    @SerializedName("time")
    val time: Long,
    @SerializedName("derived_fields")
    val derivedFields: Map<String, String>? = null,
    @SerializedName("param_stores")
    val paramStores: Map<String, Map<String, Map<String, Any>>>? = null,
    @SerializedName("full_checksum")
    val fullChecksum: String? = null,
    @SerializedName("sdk_flags")
    val sdkFlags: Map<String, Any>? = null,
    @SerializedName("sdk_configs")
    val sdkConfigs: Map<String, Any>? = null,
    @SerializedName("exposures")
    val exposures: Map<String, Map<String, String>>? = null,
    @SerializedName("values")
    val values: Map<String, Map<String, Any>>? = null,
    @SerializedName("response_format")
    val responseFormat: String? = INIT_RESPONSE_FORMAT_V2
) {
    fun toRuntime(): InitializeResponse.SuccessfulInitializeResponse {
        val runtimeFeatureGates = featureGates?.let { persistedFeatureGates ->
            LinkedHashMap<String, APIFeatureGate>(persistedFeatureGates.size).apply {
                for ((name, gate) in persistedFeatureGates) {
                    this[name] = APIFeatureGate(
                        name = name,
                        value = gate.value == true,
                        ruleID = gate.ruleID ?: "default",
                        groupName = gate.groupName,
                        secondaryExposures = resolveExposures(gate.secondaryExposures),
                        idType = gate.idType
                    )
                }
            }
        }

        val runtimeConfigs = configs?.let { persistedConfigs ->
            LinkedHashMap<String, APIDynamicConfig>(persistedConfigs.size).apply {
                for ((name, config) in persistedConfigs) {
                    this[name] = config.toRuntime(
                        name,
                        this@PersistedInitializeResponse.values,
                        exposures
                    )
                }
            }
        }

        val runtimeLayerConfigs = layerConfigs?.let { persistedLayerConfigs ->
            LinkedHashMap<String, APIDynamicConfig>(persistedLayerConfigs.size).apply {
                for ((name, config) in persistedLayerConfigs) {
                    this[name] = config.toRuntime(
                        name,
                        this@PersistedInitializeResponse.values,
                        exposures
                    )
                }
            }
        }

        return InitializeResponse.SuccessfulInitializeResponse(
            featureGates = runtimeFeatureGates,
            configs = runtimeConfigs,
            layerConfigs = runtimeLayerConfigs,
            hasUpdates = hasUpdates,
            hashUsed = hashUsed,
            time = time,
            derivedFields = derivedFields,
            paramStores = paramStores,
            fullChecksum = fullChecksum,
            sdkFlags = sdkFlags,
            sdkConfigs = sdkConfigs
        )
    }

    private fun resolveExposures(ids: List<String>?): Array<Map<String, String>> {
        if (ids.isNullOrEmpty() || exposures.isNullOrEmpty()) {
            return arrayOf()
        }

        val resolved = ArrayList<Map<String, String>>(ids.size)
        for (id in ids) {
            val exposure = exposures[id] ?: continue
            resolved.add(exposure)
        }

        return if (resolved.isEmpty()) {
            arrayOf()
        } else {
            resolved.toTypedArray()
        }
    }
}

internal object InitializeResponseFormatter {
    fun deserialize(
        rawResponse: String,
        gson: Gson
    ): InitializeResponse.SuccessfulInitializeResponse = try {
        deserializeV2(rawResponse, gson)
    } catch (_: Exception) {
        deserializeV1(rawResponse, gson)
    }

    fun deserializeV1(
        rawResponse: String,
        gson: Gson
    ): InitializeResponse.SuccessfulInitializeResponse = gson.fromJson(
        rawResponse,
        InitializeResponse.SuccessfulInitializeResponse::class.java
    )

    fun deserializeV2(
        rawResponse: String,
        gson: Gson
    ): InitializeResponse.SuccessfulInitializeResponse {
        val compact = gson.fromJson(
            rawResponse,
            PersistedInitializeResponse::class.java
        )
        require(compact.responseFormat == INIT_RESPONSE_FORMAT_V2) {
            "Expected init-v2 response format"
        }
        return compact.toRuntime()
    }

    fun toPersistedResponse(
        response: InitializeResponse.SuccessfulInitializeResponse,
        gson: Gson
    ): PersistedInitializeResponse {
        val exposuresByID = LinkedHashMap<String, Map<String, String>>()
        val exposureIndex = LinkedHashMap<String, String>()
        val valuesByID = LinkedHashMap<String, Map<String, Any>>()
        val valueIndex = LinkedHashMap<String, String>()

        fun indexExposure(exposure: Map<String, String>): String {
            val serialized = gson.toJson(exposure)
            return exposureIndex.getOrPut(serialized) {
                val id = exposureIndex.size.toString()
                exposuresByID[id] = exposure
                id
            }
        }

        fun indexValue(value: Map<String, Any>): String {
            val serialized = gson.toJson(value)
            return valueIndex.getOrPut(serialized) {
                val id = valueIndex.size.toString()
                valuesByID[id] = value
                id
            }
        }

        return PersistedInitializeResponse(
            featureGates = response.featureGates?.let { featureGates ->
                LinkedHashMap<String, PersistedFeatureGate>(featureGates.size).apply {
                    for ((name, gate) in featureGates) {
                        this[name] = PersistedFeatureGate(
                            value = gate.value.takeIf { it },
                            ruleID = gate.ruleID.takeUnless { it == "default" },
                            groupName = gate.groupName,
                            secondaryExposures = gate.secondaryExposures.toExposureIDs(
                                ::indexExposure
                            ),
                            idType = gate.idType
                        )
                    }
                }
            },
            configs = response.configs?.let { configs ->
                LinkedHashMap<String, PersistedDynamicConfig>(configs.size).apply {
                    for ((name, config) in configs) {
                        this[name] = config.toPersisted(::indexValue, ::indexExposure)
                    }
                }
            },
            layerConfigs = response.layerConfigs?.let { layerConfigs ->
                LinkedHashMap<String, PersistedDynamicConfig>(layerConfigs.size).apply {
                    for ((name, config) in layerConfigs) {
                        this[name] = config.toPersisted(::indexValue, ::indexExposure)
                    }
                }
            },
            hasUpdates = response.hasUpdates,
            hashUsed = response.hashUsed,
            time = response.time,
            derivedFields = response.derivedFields,
            paramStores = response.paramStores,
            fullChecksum = response.fullChecksum,
            sdkFlags = response.sdkFlags,
            sdkConfigs = response.sdkConfigs,
            exposures = exposuresByID.takeIf { it.isNotEmpty() },
            values = valuesByID.takeIf { it.isNotEmpty() }
        )
    }
}

private fun PersistedDynamicConfig.toRuntime(
    name: String,
    values: Map<String, Map<String, Any>>?,
    exposures: Map<String, Map<String, String>>?
): APIDynamicConfig = APIDynamicConfig(
    name = name,
    value = values?.get(valueKey) ?: mapOf(),
    ruleID = ruleID ?: "default",
    groupName = groupName,
    secondaryExposures = secondaryExposures.toExposureMaps(exposures),
    undelegatedSecondaryExposures = undelegatedSecondaryExposures.toExposureMaps(exposures),
    isDeviceBased = isDeviceBased == true,
    isUserInExperiment = isUserInExperiment == true,
    isExperimentActive = isExperimentActive == true,
    allocatedExperimentName = allocatedExperimentName,
    explicitParameters = explicitParameters ?: arrayOf(),
    rulePassed = rulePassed,
    parameterRuleIDs = parameterRuleIDs
)

private fun APIDynamicConfig.toPersisted(
    indexValue: (Map<String, Any>) -> String,
    indexExposure: (Map<String, String>) -> String
): PersistedDynamicConfig = PersistedDynamicConfig(
    valueKey = indexValue(value),
    ruleID = ruleID.takeUnless { it == "default" },
    groupName = groupName,
    secondaryExposures = secondaryExposures.toExposureIDs(indexExposure),
    undelegatedSecondaryExposures = undelegatedSecondaryExposures.toExposureIDs(indexExposure),
    isDeviceBased = isDeviceBased.takeIf { it },
    isUserInExperiment = isUserInExperiment.takeIf { it },
    isExperimentActive = isExperimentActive.takeIf { it },
    allocatedExperimentName = allocatedExperimentName,
    explicitParameters = explicitParameters?.takeIf { it.isNotEmpty() },
    rulePassed = rulePassed,
    parameterRuleIDs = parameterRuleIDs
)

private fun List<String>?.toExposureMaps(
    exposures: Map<String, Map<String, String>>?
): Array<Map<String, String>> {
    if (this.isNullOrEmpty() || exposures.isNullOrEmpty()) {
        return arrayOf()
    }

    val resolved = ArrayList<Map<String, String>>(this.size)
    for (id in this) {
        val exposure = exposures[id] ?: continue
        resolved.add(exposure)
    }

    return if (resolved.isEmpty()) {
        arrayOf()
    } else {
        resolved.toTypedArray()
    }
}

private fun Array<Map<String, String>>?.toExposureIDs(
    indexExposure: (Map<String, String>) -> String
): List<String>? {
    if (this.isNullOrEmpty()) {
        return null
    }

    val ids = ArrayList<String>(this.size)
    for (exposure in this) {
        ids.add(indexExposure(exposure))
    }

    return ids.takeIf { it.isNotEmpty() }
}
