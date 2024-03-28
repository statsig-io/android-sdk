package com.statsig.androidsdk

import android.os.Build
import com.google.gson.annotations.SerializedName
import java.util.*

internal data class StatsigMetadata(
    @SerializedName("stableID") var stableID: String? = null,
    @SerializedName("appIdentifier") var appIdentifier: String? = null,
    @SerializedName("appVersion") var appVersion: String? = null,
    @SerializedName("deviceModel") var deviceModel: String? = Build.MODEL,
    @SerializedName("deviceOS") var deviceOS: String = "Android",
    @SerializedName("locale") var locale: String? = Locale.getDefault().toString(),
    @SerializedName("language")
    var language: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // API 21+
        Locale.getDefault().toLanguageTag()
    } else {
        Locale.getDefault().let { locale ->
            "${locale.language}-${locale.country}"
        }
    },
    @SerializedName("sdkType") var sdkType: String? = "android-client",
    @SerializedName("sdkVersion") var sdkVersion: String? = BuildConfig.VERSION_NAME,
    @SerializedName("sessionID") var sessionID: String = UUID.randomUUID().toString(),
    @SerializedName("systemVersion") var systemVersion: String = Build.VERSION.SDK_INT.toString(),
    @SerializedName("systemName") var systemName: String = "Android",
) {
    internal fun overrideStableID(overrideStableID: String?) {
        if (overrideStableID != null && overrideStableID != stableID) {
            stableID = overrideStableID
        }
    }
}
