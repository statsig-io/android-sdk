package com.statsig.androidsdk

import com.statsig.androidsdk.evaluator.ConfigEvaluation

/**
 * A helper class for interfacing with Layers defined in the Statsig console
 */
class Layer internal constructor(
    private val client: StatsigClient?,
    private val name: String,
    private val details: EvaluationDetails,
    private val jsonValue: Map<String, Any> = mapOf(),
    private val rule: String = "",
    private val groupName: String? = null,
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val undelegatedSecondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val isUserInExperiment: Boolean = false,
    private val isExperimentActive: Boolean = false,
    private val isDeviceBased: Boolean = false,
    private val allocatedExperimentName: String? = null,
    private val explicitParameters: Set<String>? = null,
    private val parameterRuleIDs: Map<String, String>? = null
) : BaseConfig(name, details) {
    internal constructor(
        client: StatsigClient?,
        layerName: String,
        apiDynamicConfig: APIDynamicConfig,
        evalDetails: EvaluationDetails
    ) : this(
        client,
        layerName,
        evalDetails,
        apiDynamicConfig.value,
        apiDynamicConfig.ruleID,
        apiDynamicConfig.groupName,
        apiDynamicConfig.secondaryExposures ?: arrayOf(),
        apiDynamicConfig.undelegatedSecondaryExposures ?: arrayOf(),
        apiDynamicConfig.isUserInExperiment,
        apiDynamicConfig.isExperimentActive,
        apiDynamicConfig.isDeviceBased,
        apiDynamicConfig.allocatedExperimentName,
        apiDynamicConfig.explicitParameters?.toSet(),
        apiDynamicConfig.parameterRuleIDs
    )

    internal constructor(
        client: StatsigClient?,
        layerName: String,
        evaluation: ConfigEvaluation,
        details: EvaluationDetails
    ) : this(
        client = client,
        name = layerName,
        details = details,
        jsonValue = evaluation.returnableValue?.mapValue ?: mapOf(),
        rule = evaluation.ruleID,
        groupName = evaluation.groupName,
        secondaryExposures = evaluation.secondaryExposures.toTypedArray(),
        undelegatedSecondaryExposures = evaluation.undelegatedSecondaryExposures.toTypedArray(),
        isExperimentActive = evaluation.isActive,
        isUserInExperiment = evaluation.isExperimentGroup,
        isDeviceBased = false,
        allocatedExperimentName = evaluation.configDelegate,
        explicitParameters = evaluation.explicitParameters?.toSet()
    )

    companion object {
        fun getError(name: String): Layer =
            Layer(null, name, EvaluationDetails(EvaluationReason.Error, lcut = 0))
    }

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? = get(key, default, jsonValue)

    /**
     * Gets a string value from the Layer if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getStringIfPresent(key: String): String? = getStringWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getStringWithOptionalDefault(key: String, default: String? = null): String? =
        get(key, default, jsonValue)

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getBoolean(key: String, default: Boolean): Boolean = get(key, default, jsonValue)

    /**
     * Gets a boolean value from the Layer if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getBooleanIfPresent(key: String): Boolean? = getBooleanWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getBooleanWithOptionalDefault(key: String, default: Boolean? = null): Boolean? =
        get(key, default, jsonValue)

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getDouble(key: String, default: Double): Double =
        get<Number>(key, default, jsonValue).toDouble()

    /**
     * Gets a numeric value from the Layer as a Double if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getDoubleIfPresent(key: String): Double? = getDoubleWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getDoubleWithOptionalDefault(key: String, default: Double? = null): Double? =
        get<Number?>(key, default, jsonValue)?.toDouble()

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getInt(key: String, default: Int): Int = get<Number>(key, default, jsonValue).toInt()

    /**
     * Gets a numeric value from the Layer as an Int if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getIntIfPresent(key: String): Int? = getIntWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getIntWithOptionalDefault(key: String, default: Int? = null): Int? =
        get<Number?>(key, default, jsonValue)?.toInt()

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getLong(key: String, default: Long): Long = get<Number>(key, default, jsonValue).toLong()

    /**
     * Gets a numeric value from the Layer as a Long if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getLongIfPresent(key: String): Long? = getLongWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getLongWithOptionalDefault(key: String, default: Long? = null): Long? =
        get<Number?>(key, default, jsonValue)?.toLong()

    /**
     * Gets a value from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? {
        var value = jsonValue[key] as? Array<*>
        if (value == null) {
            value = (jsonValue[key] as? ArrayList<*>)?.toTypedArray()
        }

        if (value != null) {
            logParameterExposure(key)
        }

        return value ?: default
    }

    /**
     * Gets an array value from the Layer if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getArrayIfPresent(key: String): Array<*>? = getArrayWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getArrayWithOptionalDefault(key: String, default: Array<*>? = null): Array<*>? =
        getArray(key, default)

    /**
     * Gets a dictionary from the Layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the Layer
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? =
        get(key, default, jsonValue)

    /**
     * Gets a dictionary value from the Layer if it is present and typed as expected, otherwise returns null.
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key, or null if not found
     */
    fun getDictionaryIfPresent(key: String): Map<String, Any>? =
        getDictionaryWithOptionalDefault(key)

    @JvmSynthetic
    internal fun getDictionaryWithOptionalDefault(
        key: String,
        default: Map<String, Any>? = null
    ): Map<String, Any>? = get(key, default, jsonValue)

    /**
     * Gets a value from the Layer as a DynamicConfig, or null if not found
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? =
        when (val value = get(key, null as Map<String, Any>?, jsonValue)) {
            is Map<String, Any> -> DynamicConfig(
                key,
                this.details,
                value,
                this.rule,
                this.groupName
            )

            else -> null
        }

    fun getIsUserInExperiment(): Boolean = this.isUserInExperiment

    fun getIsExperimentActive(): Boolean = this.isExperimentActive

    fun getRuleID(): String = this.rule

    fun getRuleIDForParameter(key: String): String {
        val paramRuleID = this.parameterRuleIDs?.get(key)
        return paramRuleID ?: this.rule
    }

    fun getGroupName(): String? = this.groupName

    fun getAllocatedExperimentName(): String? = this.allocatedExperimentName

    internal fun getSecondaryExposures(): Array<Map<String, String>> = this.secondaryExposures

    internal fun getUndelegatedSecondaryExposures(): Array<Map<String, String>> =
        this.undelegatedSecondaryExposures

    internal fun getExplicitParameters(): Set<String>? = this.explicitParameters

    private fun logParameterExposure(key: String) {
        this.client?.logLayerParameterExposure(this, key)
    }

    /**
     * We should not just expose this function as inline reified is copied to every place it is used.
     */
    private inline fun <reified T> get(key: String, default: T, jsonValue: Map<String, Any>): T {
        val value = jsonValue[key] as? T
        if (value != null) {
            logParameterExposure(key)
        }
        return value ?: default
    }
}
