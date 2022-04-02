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
        return when (this.jsonValue[key]) {
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
        return when (this.jsonValue[key]) {
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
        return when (val value = this.jsonValue[key]) {
            is Number -> value.toDouble()
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
        return when (val value = this.jsonValue[key]) {
            is Number -> value.toInt()
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getLong(key: String, default: Long): Long {
        return when (val value = this.jsonValue[key]) {
            is Number -> value.toLong()
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? {
        return when (val value = this.jsonValue[key]) {
            is Array<*> -> value
            is ArrayList<*> -> value.toTypedArray()
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
        return when (val value = this.jsonValue[key]) {
            is Map<*, *> -> value as Map<String, Any>
            else -> default
        }
    }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? {
        return when (val value = this.jsonValue[key]) {
            is Map<*, *> -> DynamicConfig(
                key,
                value as Map<String, Any>,
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
