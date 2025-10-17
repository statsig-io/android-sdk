package com.statsig.androidsdk.evaluator

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

internal data class SpecsResponse(
    @SerializedName("dynamic_configs") val dynamicConfigs: List<Spec>,
    @SerializedName("feature_gates") val featureGates: List<Spec>,
    @SerializedName("layer_configs") val layerConfigs: List<Spec>,
    @SerializedName("param_stores") val paramStores: Map<String, SpecParamStore>?,
    @SerializedName("layers") val layers: Map<String, List<String>>?,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("diagnostics") val diagnostics: Map<String, Int>? = null,
    @SerializedName("default_environment") val defaultEnvironment: String? = null
)

internal data class Spec(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: ReturnableValue,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: List<SpecRule>,
    @SerializedName("idType") val idType: String,
    @SerializedName("entity") val entity: String,
    @SerializedName("explicitParameters") val explicitParameters: List<String>?,
    @SerializedName("hasSharedParams") val hasSharedParams: Boolean?,
    @SerializedName("targetAppIDs") val targetAppIDs: List<String>? = null,
    @SerializedName("version") val version: Int? = null
)

internal data class SpecRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Double,
    @SerializedName("returnValue") val returnValue: ReturnableValue,
    @SerializedName("id") val id: String,
    @SerializedName("salt") val salt: String?,
    @SerializedName("conditions") val conditions: List<SpecCondition>,
    @SerializedName("idType") val idType: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("configDelegate") val configDelegate: String?,
    @SerializedName("isExperimentGroup") val isExperimentGroup: Boolean?
)

internal data class SpecCondition(
    @SerializedName("type") val type: String,
    @SerializedName("targetValue") val targetValue: Any?,
    @SerializedName("operator") val operator: String?,
    @SerializedName("field") val field: String?,
    @SerializedName("additionalValues") val additionalValues: Map<String, Any>?,
    @SerializedName("idType") val idType: String
)

internal data class SpecParamStore(
    @SerializedName("targetAppIDs") val targetAppIDs: List<String>,
    @SerializedName("parameters") val parameters: Map<String, Map<String, Any>>
)

@JsonAdapter(ReturnableValue.CustomSerializer::class)
internal data class ReturnableValue(
    val booleanValue: Boolean? = null,
    val rawJson: String = "null",
    val mapValue: Map<String, Any>? = null
) {
    fun getValue(): Any? {
        if (booleanValue != null) {
            return booleanValue
        }

        if (mapValue != null) {
            return mapValue
        }

        return null
    }

    internal class CustomSerializer :
        JsonDeserializer<ReturnableValue>,
        JsonSerializer<ReturnableValue> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): ReturnableValue {
            if (json == null) {
                return ReturnableValue()
            }

            if (json.isJsonPrimitive && json.asJsonPrimitive.isBoolean) {
                val booleanValue = json.asJsonPrimitive.asBoolean
                return ReturnableValue(booleanValue, json.toString(), null)
            }

            if (json.isJsonObject) {
                val jsonObject = json.asJsonObject
                val mapValue = context?.deserialize<Map<String, Any>>(jsonObject, Map::class.java)
                    ?: emptyMap()
                return ReturnableValue(null, json.toString(), mapValue)
            }

            return ReturnableValue()
        }

        override fun serialize(
            src: ReturnableValue?,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            if (src == null) {
                return JsonNull.INSTANCE
            }

            return JsonParser.parseString(src.rawJson)
        }
    }
}
