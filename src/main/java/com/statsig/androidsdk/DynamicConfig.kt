package com.statsig.androidsdk

/**
 * A helper class for interfacing with Dynamic Configs defined in the Statsig console
 */
class DynamicConfig(
    private val name: String,
    private val jsonValue: Map<String, Any> = mapOf(),
    private val rule: String = "",
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val isUserInExperiment: Boolean = false,
    private val isExperimentActive: Boolean = false,
    private val isDeviceBased: Boolean = false,
    private val allocatedExperimentName: String = "") {

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        return when (this.jsonValue[key]) {
            null -> default
            is String -> this.jsonValue[key] as String
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getBoolean(key: String, default: Boolean): Boolean {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        return when (this.jsonValue[key]) {
            null -> default
            is Boolean -> this.jsonValue[key] as Boolean
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDouble(key: String, default: Double): Double {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        return when (this.jsonValue[key]) {
            null -> default
            is Double -> this.jsonValue[key] as Double
            is Int -> this.jsonValue[key] as Double
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getInt(key: String, default: Int): Int {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        if (this.jsonValue[key] == null) {
            return default
        }
        if (
            this.jsonValue[key] is Double &&
            this.jsonValue[key] as Double == Math.floor(this.jsonValue[key] as Double)
        ) {
            return this.jsonValue[key] as Int
        }
        if (this.jsonValue[key] is Int) {
            return this.jsonValue[key] as Int
        }
        return default
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        return when (this.jsonValue[key]) {
            null -> default
            is Array<*> -> this.jsonValue[key] as Array<*>
            is IntArray -> (this.jsonValue[key] as IntArray).toTypedArray()
            is DoubleArray -> (this.jsonValue[key] as DoubleArray).toTypedArray()
            is BooleanArray -> (this.jsonValue[key] as BooleanArray).toTypedArray()
            else -> default
        }
    }

    /**
     * Gets a dictionary from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? {
        if (!this.jsonValue.containsKey(key)) {
            return default;
        }
        return when (this.jsonValue[key]) {
            null -> default
            is Map<*, *> -> this.jsonValue[key] as Map<String, Any>
            else -> default
        }
    }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? {
        if (!this.jsonValue.containsKey(key)) {
            return null;
        }
        return when (this.jsonValue[key]) {
            null -> null
            is Map<*, *> -> DynamicConfig(
                key,
                this.jsonValue[key] as Map<String, Any>,
                this.rule
            )
            else -> null
        }
    }
    /**
     * Returns a Map representing the JSON object backing this config
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getValue(): Map<String, Any> {
        return this.jsonValue
    }

    fun getName(): String {
        return this.name
    }

    fun getIsUserInExperiment(): Boolean {
        return this.isUserInExperiment
    }

    fun getIsExperimentActive(): Boolean {
        return this.isExperimentActive
    }

    internal fun getRuleID(): String {
        return this.rule
    }

    internal fun getSecondaryExposures(): Array<Map<String, String>> {
        return this.secondaryExposures
    }

    internal fun getAllocatedExperimentName(): String? {
        return this.allocatedExperimentName
    }
}
