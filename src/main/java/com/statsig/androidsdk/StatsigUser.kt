package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

/**
 * An object of properties relating to the current user
 * Provide as many as possible to take advantage of advanced conditions in the Statsig console
 * A dictionary of additional fields can be provided under the "custom" field
 * @property userID a unique identifier for the user
 * @property email an email associated with the current user
 * @property ip the ip address of the requests for the user
 * @property userAgent the user agent of the requests for this user
 * @property country the country location of the user
 * @property locale the locale for the user
 * @property clientVersion the current version of the app
 * @property custom any additional custom user attributes for custom conditions in the console
 */
data class StatsigUser(@SerializedName("userID") var userID: String? = null) {
    @SerializedName("email") var email: String? = null
    @SerializedName("ip") var ip: String? = null
    @SerializedName("userAgent") var userAgent: String? = null
    @SerializedName("country") var country: String? = null
    @SerializedName("locale") var locale: String? = null
    @SerializedName("clientVersion") var clientVersion: String? = null
    @SerializedName("custom") var custom: Map<String, Any>? = null
}