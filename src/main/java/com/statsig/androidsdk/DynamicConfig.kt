package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class Config(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("group") val group: String
)

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
    fun getString(key: String, default: String): String {
        if (!config.value.containsKey(key)) {
            return default;
        }
        val ret = this.config.value[key] as? String
        if (ret == null) {
            return default
        }
        return ret
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
        val ret = this.config.value[key] as? Boolean
        if (ret == null) {
            return default
        }
        return ret
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
        val ret = this.config.value[key] as? Double
        if (ret == null) {
            return default
        }
        return ret
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
        val ret = this.config.value[key] as? Int
        if (ret == null) {
            return default
        }
        return ret
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<Any>): Array<Any> {
        if (!config.value.containsKey(key)) {
            return default;
        }
        val ret = this.config.value[key] as? Array<Any>
        if (ret == null) {
            return default
        }
        return ret
    }

    /**
     * Gets a dictionary from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>): Map<String, Any> {
        if (!config.value.containsKey(key)) {
            return default;
        }
        val innerConfig = config.value[key] as? Map<String, Any>
        if (innerConfig == null) {
            return default
        }
        return innerConfig
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
        val innerConfig = config.value[key] as? Map<String, Any>
        if (innerConfig == null) {
            return null
        }
        return DynamicConfig(Config(key, innerConfig, config.group))
    }

    override fun toString(): String {
        return config.value.toString()
    }

    fun getGroup(): String {
        return config.group
    }
}
