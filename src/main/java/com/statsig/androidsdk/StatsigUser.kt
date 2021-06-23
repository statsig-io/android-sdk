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
 * @property appVersion the current version of the app
 * @property custom any additional custom user attributes for custom conditions in the console
 *                  NOTE: values other than String, Double, Boolean, Array<String>
 *                  will be dropped from the map
 */
data class StatsigUser(
    @SerializedName("userID")
    var userID: String? = null,
) {
    @SerializedName("email")
    var email: String? = null

    @SerializedName("ip")
    var ip: String? = null

    @SerializedName("userAgent")
    var userAgent: String? = null

    @SerializedName("country")
    var country: String? = null

    @SerializedName("locale")
    var locale: String? = null

    @SerializedName("appVersion")
    var appVersion: String? = null

    @SerializedName("custom")
    var custom: Map<String, Any>? = null

    @SerializedName("statsigEnvironment")
    internal var statsigEnvironment: Map<String, String>? = null
}
