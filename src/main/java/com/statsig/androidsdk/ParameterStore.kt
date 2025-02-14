package com.statsig.androidsdk

data class ParameterStoreEvaluationOptions(
    /**
     * Prevents an exposure log being created for checks on this parameter store
     *
     * default: `false`
     */
    val disableExposureLog: Boolean = false,
)

enum class RefType(val value: String) {
    GATE("gate"),
    EXPERIMENT("experiment"),
    LAYER("layer"),
    DYNAMIC_CONFIG("dynamic_config"),
    STATIC("static"),
    UNKNOWN("unknown"),
    ;

    override fun toString(): String {
        return value
    }

    companion object {
        fun fromString(value: String): RefType {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

enum class ParamType(val value: String) {
    BOOLEAN("boolean"),
    STRING("string"),
    NUMBER("number"),
    OBJECT("object"),
    ARRAY("array"),
    UNKNOWN("unknown"),
    ;

    override fun toString(): String {
        return value
    }

    companion object {
        fun fromString(value: String): ParamType {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

class ParameterStore(
    private val statsigClient: StatsigClient,
    private val paramStore: Map<String, Map<String, Any>>,
    public val name: String,
    public val evaluationDetails: EvaluationDetails,
    public val options: ParameterStoreEvaluationOptions?,
) {
    fun getBoolean(paramName: String, fallback: Boolean): Boolean {
        return getValue(paramName, fallback)
    }

    fun getString(paramName: String, fallback: String?): String? {
        return getValue(paramName, fallback)
    }

    fun getDouble(paramName: String, fallback: Double): Double {
        return getValue(paramName, fallback)
    }

    fun getDictionary(paramName: String, fallback: Map<String, Any>?): Map<String, Any>? {
        return getValue(paramName, fallback)
    }

    fun getArray(paramName: String, fallback: Array<*>?): Array<*>? {
        return getValue(paramName, fallback)
    }

    // --------evaluation--------
    private inline fun <reified T> getValue(topLevelParamName: String, fallback: T): T {
        try {
            val param = paramStore[topLevelParamName] ?: return fallback
            val referenceTypeString = param["ref_type"] as? String ?: return fallback
            val paramTypeString = param["param_type"] as? String ?: return fallback
            val refType = RefType.fromString(referenceTypeString)
            val paramType = ParamType.fromString(paramTypeString)

            when (paramType) {
                ParamType.BOOLEAN -> if (fallback !is Boolean) return fallback
                ParamType.STRING -> if (fallback !is String) return fallback
                ParamType.NUMBER -> if (fallback !is Number) return fallback
                ParamType.OBJECT -> if (fallback !is Map<*, *>) return fallback
                ParamType.ARRAY -> if (fallback !is Array<*> && fallback !is List<*>) return fallback
                else -> return fallback
            }

            return when (refType) {
                RefType.GATE -> evaluateFeatureGate(paramType, param, fallback)
                RefType.STATIC -> evaluateStaticValue(paramType, param, fallback)
                RefType.LAYER -> evaluateLayerParameter(paramType, param, fallback)
                RefType.DYNAMIC_CONFIG -> evaluateDynamicConfigParameter(paramType, param, fallback)
                RefType.EXPERIMENT -> evaluateExperimentParameter(paramType, param, fallback)
                else -> fallback
            }
        } catch (e: Exception) {
            return fallback
        }
    }

    private inline fun <reified T> evaluateFeatureGate(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T,
    ): T {
        val passValue = param["pass_value"]
        val failValue = param["fail_value"]
        val gateName = param["gate_name"] as? String
        if (passValue == null || failValue == null || gateName == null) {
            return fallback
        }
        val passes = if (options?.disableExposureLog == true) {
            statsigClient.checkGateWithExposureLoggingDisabled(gateName)
        } else {
            statsigClient.checkGate(gateName)
        }
        val retVal = if (passes) passValue else failValue
        if (paramType == ParamType.NUMBER) {
            return (retVal as Number).toDouble() as T
        } else if (paramType == ParamType.ARRAY) {
            return when (retVal) {
                is Array<*> -> return retVal as T
                is ArrayList<*> -> return retVal.toTypedArray() as T
                else -> fallback
            }
        }
        return retVal as T
    }

    private inline fun <reified T> evaluateStaticValue(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T,
    ): T {
        return when (paramType) {
            ParamType.BOOLEAN -> param["value"] as? T ?: fallback
            ParamType.STRING -> param["value"] as? T ?: fallback
            ParamType.NUMBER -> (param["value"] as Number).toDouble() as? T ?: fallback
            ParamType.OBJECT -> param["value"] as? T ?: fallback
            ParamType.ARRAY -> {
                when (val returnValue = param["value"]) {
                    is Array<*> -> returnValue as T
                    is ArrayList<*> -> (returnValue.toTypedArray()) as T
                    else -> fallback
                }
            }
            else -> fallback
        }
    }

    private inline fun <reified T> evaluateLayerParameter(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T,
    ): T {
        val layerName = param["layer_name"] as? String
        val paramName = param["param_name"] as? String
        if (layerName == null || paramName == null) {
            return fallback
        }
        val layer = if (options?.disableExposureLog == true) {
            statsigClient.getLayerWithExposureLoggingDisabled(layerName)
        } else {
            statsigClient.getLayer(layerName)
        }
        return when (paramType) {
            ParamType.BOOLEAN -> layer.getBoolean(
                paramName,
                fallback as? Boolean ?: return fallback,
            ) as T
            ParamType.STRING -> layer.getString(
                paramName,
                fallback as? String ?: return fallback,
            ) as T
            ParamType.NUMBER -> layer.getDouble(
                paramName,
                fallback as? Double ?: return fallback,
            ) as T
            ParamType.OBJECT -> layer.getDictionary(
                paramName,
                fallback as? Map<String, Any> ?: return fallback,
            ) as T
            ParamType.ARRAY -> layer.getArray(
                paramName,
                fallback as? Array<*> ?: return fallback,
            ) as T
            else -> fallback
        }
    }

    private inline fun <reified T> evaluateDynamicConfigParameter(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T,
    ): T {
        val configName = param["config_name"] as? String
        val paramName = param["param_name"] as? String
        if (configName == null || paramName == null) {
            return fallback
        }
        val config = if (options?.disableExposureLog == true) {
            statsigClient.getConfigWithExposureLoggingDisabled(configName)
        } else {
            statsigClient.getConfig(configName)
        }
        return when (paramType) {
            ParamType.BOOLEAN -> config.getBoolean(
                paramName,
                fallback as? Boolean ?: return fallback,
            ) as T
            ParamType.STRING -> config.getString(
                paramName,
                fallback as? String ?: return fallback,
            ) as T
            ParamType.NUMBER -> config.getDouble(
                paramName,
                fallback as? Double ?: return fallback,
            ) as T
            ParamType.OBJECT -> config.getDictionary(
                paramName,
                fallback as? Map<String, Any> ?: return fallback,
            ) as T
            ParamType.ARRAY -> config.getArray(
                paramName,
                fallback as? Array<*> ?: return fallback,
            ) as T
            else -> fallback
        }
    }

    private inline fun <reified T> evaluateExperimentParameter(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T,
    ): T {
        val experimentName = param["experiment_name"] as? String
        val paramName = param["param_name"] as? String
        if (experimentName == null || paramName == null) {
            return fallback
        }
        val experiment = if (options?.disableExposureLog == true) {
            statsigClient.getExperimentWithExposureLoggingDisabled(experimentName)
        } else {
            statsigClient.getExperiment(experimentName)
        }
        return when (paramType) {
            ParamType.BOOLEAN -> experiment.getBoolean(
                paramName,
                fallback as? Boolean ?: return fallback,
            ) as T
            ParamType.STRING -> experiment.getString(
                paramName,
                fallback as? String ?: return fallback,
            ) as T
            ParamType.NUMBER -> experiment.getDouble(
                paramName,
                fallback as? Double ?: return fallback,
            ) as T
            ParamType.OBJECT -> experiment.getDictionary(
                paramName,
                fallback as? Map<String, Any> ?: return fallback,
            ) as T
            ParamType.ARRAY -> experiment.getArray(
                paramName,
                fallback as? Array<*> ?: return fallback,
            ) as T
            else -> fallback
        }
    }
}
