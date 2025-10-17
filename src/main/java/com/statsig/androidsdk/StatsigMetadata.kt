package com.statsig.androidsdk

import android.os.Build
import com.google.gson.annotations.SerializedName
import java.util.*

internal data class StatsigMetadata(
    @SerializedName("stableID") var stableID: String? = null,
    @SerializedName("sdkType") var sdkType: String? = "android-client",
    @SerializedName("sdkVersion") var sdkVersion: String? = BuildConfig.VERSION_NAME,
    @SerializedName("sessionID") var sessionID: String = UUID.randomUUID().toString(),
    @SerializedName("appIdentifier") var appIdentifier: String? = null,
    @SerializedName("appVersion") var appVersion: String? = null,
    @SerializedName("deviceModel") var deviceModel: String? = null,
    @SerializedName("deviceOS") var deviceOS: String? = null,
    @SerializedName("locale") var locale: String? = null,
    @SerializedName("language") var language: String? = null,
    @SerializedName("systemVersion") var systemVersion: String? = null,
    @SerializedName("systemName") var systemName: String? = null
) {
    internal fun overrideStableID(overrideStableID: String?) {
        if (overrideStableID != null && overrideStableID != stableID) {
            stableID = overrideStableID
        }
    }
}

internal fun createStatsigMetadata(): StatsigMetadata = StatsigMetadata(
    stableID = null,
    sdkType = "android-client",
    sdkVersion = BuildConfig.VERSION_NAME,
    sessionID = UUID.randomUUID().toString(),
    appIdentifier = null,
    appVersion = null,
    deviceModel = Build.MODEL,
    deviceOS = "Android",
    locale = Locale.getDefault().toString(),
    language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // API 21+
        Locale.getDefault().toLanguageTag()
    } else {
        Locale.getDefault().let { locale ->
            "${locale.language}-${locale.country}"
        }
    },
    systemVersion = Build.VERSION.SDK_INT.toString(),
    systemName = "Android"
)

internal fun createCoreStatsigMetadata(): StatsigMetadata = StatsigMetadata(
    stableID = null,
    sdkType = "android-client",
    sdkVersion = BuildConfig.VERSION_NAME,
    sessionID = UUID.randomUUID().toString(),
    appIdentifier = null,
    appVersion = null,
    deviceModel = null,
    deviceOS = null,
    locale = null,
    language = null,
    systemVersion = null,
    systemName = null
)
