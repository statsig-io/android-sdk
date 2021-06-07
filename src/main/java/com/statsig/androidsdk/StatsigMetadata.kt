package com.statsig.androidsdk

import android.os.Build
import com.google.gson.annotations.SerializedName
import java.util.Locale

data class StatsigMetadata(
    @SerializedName("appIdentifier") var appIdentifier: String? = null,
    @SerializedName("appVersion") var appVersion: String? = null,
    @SerializedName("deviceModel") var deviceModel: String? = Build.MODEL,
    @SerializedName("deviceOS") var deviceOS: String = "Android",
    @SerializedName("language") var language: String? = Locale.getDefault().displayLanguage,
    @SerializedName("sdkType") var sdkType: String? = "android-client",
    @SerializedName("sdkVersion") var sdkVersion: String? = com.statsig.androidsdk.BuildConfig.VERSION_NAME,
    @SerializedName("sessionID") var sessionID: String? = StatsigId.getSessionID(),
    @SerializedName("stableID") var stableID: String? = null,
    @SerializedName("systemVersion") var systemVersion: String = Build.VERSION.SDK_INT.toString(),
    @SerializedName("systemName") var systemName: String? = Build.VERSION.RELEASE,
)