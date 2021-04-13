package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class Config(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("group") val group: String
)

class DynamicConfig(private val config: Config) {

    @JvmOverloads
    fun <T: Any> getValue(key: String, default: T? = null): T? {
        if (!config.value.containsKey(key)) {
            return default;
        }
        return this.config.value[key] as T
    }

    override fun toString(): String {
        return config.value.toString()
    }

    fun getGroup(): String {
        return config.group
    }
}
