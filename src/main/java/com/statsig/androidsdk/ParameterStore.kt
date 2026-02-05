package com.statsig.androidsdk

data class ParameterStoreEvaluationOptions(
    /**
     * Prevents an exposure log being created for checks on this parameter store
     *
     * default: `false`
     */
    val disableExposureLog: Boolean = false
)

enum class RefType(val value: String) {
    GATE("gate"),
    EXPERIMENT("experiment"),
    LAYER("layer"),
    DYNAMIC_CONFIG("dynamic_config"),
    STATIC("static"),
    UNKNOWN("unknown")
    ;

    override fun toString(): String = value

    companion object {
        fun fromString(value: String): RefType = values().find { it.value == value } ?: UNKNOWN
    }
}

enum class ParamType(val value: String) {
    BOOLEAN("boolean"),
    STRING("string"),
    NUMBER("number"),
    OBJECT("object"),
    ARRAY("array"),
    UNKNOWN("unknown")
    ;

    override fun toString(): String = value

    companion object {
        fun fromString(value: String): ParamType = values().find { it.value == value } ?: UNKNOWN
    }
}

class ParameterStore(
    private val statsigClient: StatsigClient,
    private val paramStore: Map<String, Map<String, Any>>,
    public val name: String,
    public val evaluationDetails: EvaluationDetails,
    public val options: ParameterStoreEvaluationOptions?
) {
    fun getBoolean(paramName: String, fallback: Boolean): Boolean =
        getValueFromRef(paramName, fallback, Layer::getBoolean, DynamicConfig::getBoolean)

    fun getString(paramName: String, fallback: String?): String? =
        getValueFromRef(paramName, fallback, Layer::getString, DynamicConfig::getString)

    fun getDouble(paramName: String, fallback: Double): Double =
        getValueFromRef(paramName, fallback, Layer::getDouble, DynamicConfig::getDouble)

    fun getDictionary(paramName: String, fallback: Map<String, Any>?): Map<String, Any>? =
        getValueFromRef(paramName, fallback, Layer::getDictionary, DynamicConfig::getDictionary)

    fun getArray(paramName: String, fallback: Array<*>?): Array<*>? =
        getValueFromRef(paramName, fallback, Layer::getArray, DynamicConfig::getArray)

    fun getKeys(): List<String> = paramStore.keys.toList()

    // --------evaluation--------

    private inline fun <reified T> getValueFromRef(
        topLevelParamName: String,
        fallback: T,
        getLayerValue: Layer.(String, T) -> T,
        getDynamicConfigValue: DynamicConfig.(String, T) -> T
    ): T {
        val param = paramStore[topLevelParamName] ?: return fallback
        val referenceTypeString = param["ref_type"] as? String ?: return fallback
        val paramTypeString = param["param_type"] as? String ?: return fallback
        val refType = RefType.fromString(referenceTypeString)
        val paramType = ParamType.fromString(paramTypeString)

        return when (refType) {
            RefType.GATE -> evaluateFeatureGate(paramType, param, fallback)
            RefType.STATIC -> evaluateStaticValue(paramType, param, fallback)
            RefType.LAYER -> evaluateLayerParameter(param, fallback) { layer, paramName ->
                var v = layer.getLayerValue(paramName, fallback)
                return v
            }
            RefType.DYNAMIC_CONFIG -> evaluateDynamicConfigParameter(param, fallback) {
                    config,
                    paramName
                ->
                config.getDynamicConfigValue(paramName, fallback)
            }
            RefType.EXPERIMENT -> evaluateExperimentParameter(param, fallback) {
                    experiment,
                    paramName
                ->
                experiment.getDynamicConfigValue(paramName, fallback)
            }
            else -> fallback
        }
    }

    private inline fun <reified T> evaluateFeatureGate(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T
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
            return (retVal as? Number)?.toDouble() as? T ?: fallback
        } else if (paramType == ParamType.ARRAY) {
            return when (retVal) {
                is Array<*> -> return retVal as? T ?: fallback
                is ArrayList<*> -> return retVal.toTypedArray() as? T ?: fallback
                else -> fallback
            }
        }
        return retVal as? T ?: fallback
    }

    private inline fun <reified T> evaluateStaticValue(
        paramType: ParamType,
        param: Map<String, Any>,
        fallback: T
    ): T = when (paramType) {
        ParamType.BOOLEAN -> param["value"] as? T ?: fallback
        ParamType.STRING -> param["value"] as? T ?: fallback
        ParamType.NUMBER -> (param["value"] as? Number)?.toDouble() as? T ?: fallback
        ParamType.OBJECT -> param["value"] as? T ?: fallback
        ParamType.ARRAY -> {
            when (val returnValue = param["value"]) {
                is Array<*> -> returnValue as? T ?: fallback
                is ArrayList<*> -> (returnValue.toTypedArray()) as? T ?: fallback
                else -> fallback
            }
        }
        else -> fallback
    }

    private inline fun <reified T> evaluateLayerParameter(
        param: Map<String, Any>,
        fallback: T,
        getValue: (Layer, String) -> T
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
        return getValue(layer, paramName)
    }

    private inline fun <reified T> evaluateDynamicConfigParameter(
        param: Map<String, Any>,
        fallback: T,
        getValue: (DynamicConfig, String) -> T
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
        return getValue(config, paramName)
    }

    private inline fun <reified T> evaluateExperimentParameter(
        param: Map<String, Any>,
        fallback: T,
        getValue: (DynamicConfig, String) -> T
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
        return getValue(experiment, paramName)
    }
}
