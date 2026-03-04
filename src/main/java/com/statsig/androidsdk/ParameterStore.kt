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
    /**
     * Gets a boolean parameter, falling back to the provided value if the parameter is missing or has a different type.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @param fallback the default value to return if the parameter is missing or has a different type
     * @return the parameter value, or the provided fallback
     */
    fun getBoolean(paramName: String, fallback: Boolean): Boolean = getValueFromRef(
        paramName,
        ParamType.BOOLEAN,
        fallback,
        { param -> param["value"] as? Boolean },
        Layer::getBooleanWithOptionalDefault,
        DynamicConfig::getBooleanWithOptionalDefault
    ) ?: fallback

    /**
     * Gets a boolean parameter if it is present and typed as expected, otherwise returns null.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @return the parameter value, or null if the parameter is missing or has a different type
     */
    fun getBooleanIfPresent(paramName: String): Boolean? = getValueFromRef(
        topLevelParamName = paramName,
        expectedParamType = ParamType.BOOLEAN,
        getStaticValue = { param -> param["value"] as? Boolean },
        getLayerValue = Layer::getBooleanWithOptionalDefault,
        getDynamicConfigValue = DynamicConfig::getBooleanWithOptionalDefault
    )

    /**
     * Gets a string parameter, falling back to the provided value if the parameter is missing or has a different type.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @param fallback the default value to return if the parameter is missing or has a different type
     * @return the parameter value, or the provided fallback
     */
    fun getString(paramName: String, fallback: String?): String? = getValueFromRef(
        paramName,
        ParamType.STRING,
        fallback,
        { param -> param["value"] as? String },
        Layer::getStringWithOptionalDefault,
        DynamicConfig::getStringWithOptionalDefault
    )

    /**
     * Gets a string parameter if it is present and typed as expected, otherwise returns null.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @return the parameter value, or null if the parameter is missing or has a different type
     */
    fun getStringIfPresent(paramName: String): String? = getValueFromRef(
        topLevelParamName = paramName,
        expectedParamType = ParamType.STRING,
        getStaticValue = { param -> param["value"] as? String },
        getLayerValue = Layer::getStringWithOptionalDefault,
        getDynamicConfigValue = DynamicConfig::getStringWithOptionalDefault
    )

    /**
     * Gets a numeric parameter as a Double, falling back to the provided value if the parameter is missing or has a different type.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @param fallback the default value to return if the parameter is missing or has a different type
     * @return the parameter value, or the provided fallback
     */
    fun getDouble(paramName: String, fallback: Double): Double = getValueFromRef(
        paramName,
        ParamType.NUMBER,
        fallback,
        { param -> (param["value"] as? Number)?.toDouble() },
        Layer::getDoubleWithOptionalDefault,
        DynamicConfig::getDoubleWithOptionalDefault
    ) ?: fallback

    /**
     * Gets a numeric parameter as a Double if it is present and typed as expected, otherwise returns null.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @return the parameter value, or null if the parameter is missing or has a different type
     */
    fun getDoubleIfPresent(paramName: String): Double? = getValueFromRef(
        topLevelParamName = paramName,
        expectedParamType = ParamType.NUMBER,
        getStaticValue = { param -> (param["value"] as? Number)?.toDouble() },
        getLayerValue = Layer::getDoubleWithOptionalDefault,
        getDynamicConfigValue = DynamicConfig::getDoubleWithOptionalDefault
    )

    /**
     * Gets a dictionary parameter, falling back to the provided value if the parameter is missing or has a different type.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @param fallback the default value to return if the parameter is missing or has a different type
     * @return the parameter value, or the provided fallback
     */
    fun getDictionary(paramName: String, fallback: Map<String, Any>?): Map<String, Any>? =
        getValueFromRef(
            paramName,
            ParamType.OBJECT,
            fallback,
            { param -> param["value"] as? Map<String, Any> },
            Layer::getDictionaryWithOptionalDefault,
            DynamicConfig::getDictionaryWithOptionalDefault
        )

    /**
     * Gets a dictionary parameter if it is present and typed as expected, otherwise returns null.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @return the parameter value, or null if the parameter is missing or has a different type
     */
    fun getDictionaryIfPresent(paramName: String): Map<String, Any>? = getValueFromRef(
        topLevelParamName = paramName,
        expectedParamType = ParamType.OBJECT,
        getStaticValue = { param -> param["value"] as? Map<String, Any> },
        getLayerValue = Layer::getDictionaryWithOptionalDefault,
        getDynamicConfigValue = DynamicConfig::getDictionaryWithOptionalDefault
    )

    /**
     * Gets an array parameter, falling back to the provided value if the parameter is missing or has a different type.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @param fallback the default value to return if the parameter is missing or has a different type
     * @return the parameter value, or the provided fallback
     */
    fun getArray(paramName: String, fallback: Array<*>?): Array<*>? = getValueFromRef(
        paramName,
        ParamType.ARRAY,
        fallback,
        { param ->
            when (val value = param["value"]) {
                is Array<*> -> value
                is ArrayList<*> -> value.toTypedArray()
                else -> null
            }
        },
        Layer::getArrayWithOptionalDefault,
        DynamicConfig::getArrayWithOptionalDefault
    )

    /**
     * Gets an array parameter if it is present and typed as expected, otherwise returns null.
     * @param paramName the parameter name to fetch from the ParameterStore
     * @return the parameter value, or null if the parameter is missing or has a different type
     */
    fun getArrayIfPresent(paramName: String): Array<*>? = getValueFromRef(
        topLevelParamName = paramName,
        expectedParamType = ParamType.ARRAY,
        getStaticValue = { param ->
            when (val value = param["value"]) {
                is Array<*> -> value
                is ArrayList<*> -> value.toTypedArray()
                else -> null
            }
        },
        getLayerValue = Layer::getArrayWithOptionalDefault,
        getDynamicConfigValue = DynamicConfig::getArrayWithOptionalDefault
    )

    /**
     * Gets all top-level parameter names available on this ParameterStore.
     * @return the list of parameter names in this store
     */
    fun getKeys(): List<String> = paramStore.keys.toList()

    // --------evaluation--------

    private inline fun <reified T> getValueFromRef(
        topLevelParamName: String,
        expectedParamType: ParamType,
        defaultValue: T? = null,
        getStaticValue: (Map<String, Any>) -> T?,
        getLayerValue: Layer.(String, T?) -> T?,
        getDynamicConfigValue: DynamicConfig.(String, T?) -> T?
    ): T? {
        val param = paramStore[topLevelParamName] ?: return defaultValue
        val referenceTypeString = param["ref_type"] as? String ?: return defaultValue
        val paramTypeString = param["param_type"] as? String ?: return defaultValue
        val refType = RefType.fromString(referenceTypeString)
        val paramType = ParamType.fromString(paramTypeString)

        if (paramType != expectedParamType) {
            return defaultValue
        }

        return when (refType) {
            RefType.GATE -> evaluateFeatureGate(paramType, param, defaultValue)
            RefType.STATIC -> getStaticValue(param)
            RefType.LAYER -> evaluateLayerParameter(param, defaultValue) { layer, paramName ->
                layer.getLayerValue(paramName, defaultValue)
            }
            RefType.DYNAMIC_CONFIG -> evaluateDynamicConfigParameter(param, defaultValue) {
                    config,
                    paramName
                ->
                config.getDynamicConfigValue(paramName, defaultValue)
            }
            RefType.EXPERIMENT -> evaluateExperimentParameter(param, defaultValue) {
                    experiment,
                    paramName
                ->
                experiment.getDynamicConfigValue(paramName, defaultValue)
            }
            else -> defaultValue
        }
    }

    private inline fun <reified T> evaluateFeatureGate(
        paramType: ParamType,
        param: Map<String, Any>,
        defaultValue: T? = null
    ): T? {
        val passValue = param["pass_value"]
        val failValue = param["fail_value"]
        val gateName = param["gate_name"] as? String
        if (passValue == null || failValue == null || gateName == null) {
            return defaultValue
        }
        val passes = if (options?.disableExposureLog == true) {
            statsigClient.checkGateWithExposureLoggingDisabled(gateName)
        } else {
            statsigClient.checkGate(gateName)
        }
        val retVal = if (passes) passValue else failValue
        if (paramType == ParamType.NUMBER) {
            return (retVal as? Number)?.toDouble() as? T ?: defaultValue
        } else if (paramType == ParamType.ARRAY) {
            return when (retVal) {
                is Array<*> -> retVal as? T ?: defaultValue
                is ArrayList<*> -> retVal.toTypedArray() as? T ?: defaultValue
                else -> defaultValue
            }
        }
        return retVal as? T ?: defaultValue
    }

    private inline fun <reified T> evaluateLayerParameter(
        param: Map<String, Any>,
        defaultValue: T? = null,
        getValue: (Layer, String) -> T?
    ): T? {
        val layerName = param["layer_name"] as? String
        val paramName = param["param_name"] as? String
        if (layerName == null || paramName == null) {
            return defaultValue
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
        defaultValue: T? = null,
        getValue: (DynamicConfig, String) -> T?
    ): T? {
        val configName = param["config_name"] as? String
        val paramName = param["param_name"] as? String
        if (configName == null || paramName == null) {
            return defaultValue
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
        defaultValue: T? = null,
        getValue: (DynamicConfig, String) -> T?
    ): T? {
        val experimentName = param["experiment_name"] as? String
        val paramName = param["param_name"] as? String
        if (experimentName == null || paramName == null) {
            return defaultValue
        }
        val experiment = if (options?.disableExposureLog == true) {
            statsigClient.getExperimentWithExposureLoggingDisabled(experimentName)
        } else {
            statsigClient.getExperiment(experimentName)
        }
        return getValue(experiment, paramName)
    }
}
