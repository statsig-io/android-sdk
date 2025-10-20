package com.statsig.androidsdk

import com.statsig.androidsdk.evaluator.ConfigEvaluation

/**
 * A helper class for interfacing with Dynamic Configs defined in the Statsig console
 */
class DynamicConfig(
    private val name: String,
    private val details: EvaluationDetails,
    private val jsonValue: Map<String, Any> = mapOf(),
    private val rule: String = "",
    private val groupName: String? = null,
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val isUserInExperiment: Boolean = false,
    private val isExperimentActive: Boolean = false,
    private val isDeviceBased: Boolean = false,
    private val allocatedExperimentName: String? = null,
    private val rulePassed: Boolean? = null
) : BaseConfig(name, details) {
    internal constructor(
        configName: String,
        apiDynamicConfig: APIDynamicConfig,
        evalDetails: EvaluationDetails
    ) : this(
        configName,
        evalDetails,
        apiDynamicConfig.value,
        apiDynamicConfig.ruleID,
        apiDynamicConfig.groupName,
        apiDynamicConfig.secondaryExposures ?: arrayOf(),
        apiDynamicConfig.isUserInExperiment,
        apiDynamicConfig.isExperimentActive,
        apiDynamicConfig.isDeviceBased,
        apiDynamicConfig.allocatedExperimentName,
        apiDynamicConfig.rulePassed
    )

    internal constructor(
        configName: String,
        evaluation: ConfigEvaluation,
        details: EvaluationDetails
    ) : this(
        name = configName,
        details = details,
        jsonValue = evaluation.returnableValue?.mapValue ?: mapOf(),
        rule = evaluation.ruleID,
        groupName = evaluation.groupName,
        secondaryExposures = evaluation.secondaryExposures.toTypedArray(),
        isExperimentActive = evaluation.isActive,
        isUserInExperiment = evaluation.isExperimentGroup,
        isDeviceBased = false
    )

    internal companion object {
        fun getError(name: String): DynamicConfig =
            DynamicConfig(name, EvaluationDetails(EvaluationReason.Error, lcut = 0))
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? = when (this.jsonValue[key]) {
        is String -> this.jsonValue[key] as String
        else -> default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getBoolean(key: String, default: Boolean): Boolean = when (this.jsonValue[key]) {
        is Boolean -> this.jsonValue[key] as Boolean
        else -> default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDouble(key: String, default: Double): Double = when (val value = this.jsonValue[key]) {
        is Number -> value.toDouble()
        else -> default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getInt(key: String, default: Int): Int = when (val value = this.jsonValue[key]) {
        is Number -> value.toInt()
        else -> default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getLong(key: String, default: Long): Long = when (val value = this.jsonValue[key]) {
        is Number -> value.toLong()
        else -> default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? =
        when (val value = this.jsonValue[key]) {
            is Array<*> -> value
            is ArrayList<*> -> value.toTypedArray()
            else -> default
        }

    private inline fun <reified A, reified B> List<*>.asListOfPairs(): List<Pair<A, B>>? =
        this.mapNotNull {
            if (it is Pair<*, *>) it.asPairOf() else null
        }

    private inline fun <reified A, reified B> Pair<*, *>.asPairOf(): Pair<A, B>? {
        if (first !is A || second !is B) return null
        return first as A to second as B
    }

    private inline fun <reified K, reified V> Map<*, *>.asMapOf(
        default: Map<K, V>? = null
    ): Map<K, V>? {
        if (keys.isEmpty() || values.isEmpty()) return mapOf()
        if (keys.first() !is K || values.first() !is V) return default
        return toList().asListOfPairs<K, V>()?.associate { Pair(it.first, it.second) }
    }

    /**
     * Gets a dictionary from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? =
        when (val value = this.jsonValue[key]) {
            is Map<*, *> -> value.asMapOf()
            else -> default
        }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? = when (val value = this.jsonValue[key]) {
        is Map<*, *> ->
            when (val valueTyped = value.asMapOf<String, Any>()) {
                null -> null
                else -> DynamicConfig(
                    key,
                    this.details,
                    valueTyped,
                    this.rule,
                    this.groupName
                )
            }
        else -> null
    }

    /**
     * Returns a Map representing the JSON object backing this config
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getValue(): Map<String, Any> = this.jsonValue

    fun getIsUserInExperiment(): Boolean = this.isUserInExperiment

    fun getIsExperimentActive(): Boolean = this.isExperimentActive

    fun getRuleID(): String = this.rule

    fun getGroupName(): String? = this.groupName

    fun getRulePassed(): Boolean? = this.rulePassed

    internal fun getSecondaryExposures(): Array<Map<String, String>> = this.secondaryExposures

    internal fun getAllocatedExperimentName(): String? = this.allocatedExperimentName
}
