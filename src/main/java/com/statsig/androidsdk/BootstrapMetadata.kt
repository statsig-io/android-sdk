package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

internal data class GeneratorSDKInfo(
    @SerializedName("sdkType") val sdkType: String? = null,
    @SerializedName("sdkVersion") val sdkVersion: String? = null
)

internal data class BootstrapMetadata(
    @SerializedName("generatorSDKInfo") val generatorSDKInfo: GeneratorSDKInfo? = null,
    @SerializedName("lcut") val lcut: Long? = null,
    @SerializedName("user") val user: StatsigUser? = null
) {
    fun isEmpty(): Boolean = generatorSDKInfo == null && lcut == null && user == null

    companion object {
        fun fromInitializeValues(
            initializeValues: Map<String, Any>,
            gson: com.google.gson.Gson
        ): BootstrapMetadata {
            val generatorSDKInfo = initializeValues["sdkInfo"]?.let {
                gson.fromJson(gson.toJson(it), GeneratorSDKInfo::class.java)
            }
            val user = initializeValues["user"]?.let {
                gson.fromJson(gson.toJson(it), StatsigUser::class.java).getCopyForLogging()
            }
            val lcut = when (val value = initializeValues["time"]) {
                is Number -> value.toLong()
                else -> null
            }

            return BootstrapMetadata(
                generatorSDKInfo = generatorSDKInfo,
                lcut = lcut,
                user = user
            )
        }
    }
}
