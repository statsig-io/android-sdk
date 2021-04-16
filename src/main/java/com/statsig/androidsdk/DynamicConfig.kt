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
    @JvmOverloads
    fun <T : Any> getValue(key: String, default: T? = null): T? {
        if (!config.value.containsKey(key)) {
            return default;
        }
        return this.config.value[key] as? T?
    }

    override fun toString(): String {
        return config.value.toString()
    }

    fun getGroup(): String {
        return config.group
    }
}
