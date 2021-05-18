package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class Config(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val rule: String?
)

fun DynamicConfig() = DynamicConfig(Config("", mapOf(), null))

/**
 * A helper class for interfacing with Dynamic Configs defined in the Statsig console
 */
class DynamicConfig(private val config: Config) {

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? {
        if (!config.value.containsKey(key)) {
            return default;
        }
        return when (this.config.value[key]) {
            null -> default
            is String -> this.config.value[key] as String
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
        if (!config.value.containsKey(key)) {
            return default;
        }
        return when (this.config.value[key]) {
            null -> default
            is Boolean -> this.config.value[key] as Boolean
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
        if (!config.value.containsKey(key)) {
            return default;
        }
        return when (this.config.value[key]) {
            null -> default
            is Double -> this.config.value[key] as Double
            is Int -> this.config.value[key] as Double
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
        if (!config.value.containsKey(key)) {
            return default;
        }
        if (this.config.value[key] == null) {
            return default
        }
        if (
            this.config.value[key] is Double &&
            this.config.value[key] as Double == Math.floor(this.config.value[key] as Double)
        ) {
            return this.config.value[key] as Int
        }
        if (this.config.value[key] is Int) {
            return this.config.value[key] as Int
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
        if (!config.value.containsKey(key)) {
            return default;
        }
        return when (this.config.value[key]) {
            null -> default
            is Array<*> -> this.config.value[key] as Array<*>
            is IntArray -> (this.config.value[key] as IntArray).toTypedArray()
            is DoubleArray -> (this.config.value[key] as DoubleArray).toTypedArray()
            is BooleanArray -> (this.config.value[key] as BooleanArray).toTypedArray()
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
        if (!config.value.containsKey(key)) {
            return default;
        }
        return when (this.config.value[key]) {
            null -> default
            is Map<*, *> -> this.config.value[key] as Map<String, Any>
            else -> default
        }
    }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? {
        if (!config.value.containsKey(key)) {
            return null;
        }
        return when (this.config.value[key]) {
            null -> null
            is Map<*, *> -> DynamicConfig(
                Config(
                    key,
                    this.config.value[key] as Map<String, Any>,
                    this.config.rule
                )
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
        return config.value
    }

    fun getRuleID(): String? {
        return config.rule
    }
}
