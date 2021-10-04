package com.statsig.androidsdk

import android.os.Build
import androidx.core.content.edit
import com.google.gson.annotations.SerializedName
import java.util.*

private const val STABLE_ID_KEY: String = "STABLE_ID"

internal data class StatsigMetadata(
    @SerializedName("appIdentifier") var appIdentifier: String? = null,
    @SerializedName("appVersion") var appVersion: String? = null,
    @SerializedName("deviceModel") var deviceModel: String? = Build.MODEL,
    @SerializedName("deviceOS") var deviceOS: String = "Android",
    @SerializedName("language") var language: String? = Locale.getDefault().displayLanguage,
    @SerializedName("sdkType") var sdkType: String? = "android-client",
    @SerializedName("sdkVersion") var sdkVersion: String? = BuildConfig.VERSION_NAME,
    @SerializedName("sessionID") var sessionID: String = UUID.randomUUID().toString(),
    @SerializedName("stableID") var stableID: String = getStableID(),
    @SerializedName("systemVersion") var systemVersion: String = Build.VERSION.SDK_INT.toString(),
    @SerializedName("systemName") var systemName: String? = Build.VERSION.RELEASE,
)

private fun getStableID(): String {
    var stableID = Statsig.getSharedPrefs().getString(STABLE_ID_KEY, null)
    if (stableID == null) {
        stableID = UUID.randomUUID().toString()
        Statsig.saveStringToSharedPrefs(STABLE_ID_KEY, stableID)
    }
    return stableID
}