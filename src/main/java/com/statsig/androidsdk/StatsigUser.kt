package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

private const val STATSIG_NULL_USER: String = "Statsig.NULL_USER"

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
 * @property privateAttributes any user attributes that should be used in evaluation only and removed in any logs.
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

    @SerializedName("privateAttributes")
    var privateAttributes: Map<String, Any>? = null

    @SerializedName("customIDs")
    var customIDs: Map<String, String>? = null

    @SerializedName("statsigEnvironment")
    internal var statsigEnvironment: Map<String, String>? = null

    internal fun getCopyForEvaluation(): StatsigUser {
        val userCopy = StatsigUser(userID)
        userCopy.email = email
        userCopy.ip = ip
        userCopy.userAgent = userAgent
        userCopy.country = country
        userCopy.locale = locale
        userCopy.appVersion = appVersion
        userCopy.custom = custom?.toMap()
        userCopy.statsigEnvironment = statsigEnvironment?.toMap()
        userCopy.customIDs = customIDs?.toMap()
        userCopy.privateAttributes = privateAttributes?.toMap()
        return userCopy
    }

    internal fun getCopyForLogging(): StatsigUser {
        val userCopy = StatsigUser(userID)
        userCopy.email = email
        userCopy.ip = ip
        userCopy.userAgent = userAgent
        userCopy.country = country
        userCopy.locale = locale
        userCopy.appVersion = appVersion
        userCopy.custom = custom
        userCopy.statsigEnvironment = statsigEnvironment
        userCopy.customIDs = customIDs
        // DO NOT copy privateAttributes to the logging copy!
        userCopy.privateAttributes = null

        return userCopy
    }

    internal fun getCacheKey(): String {
        var id = userID ?: STATSIG_NULL_USER
        val customIds = customIDs ?: return id

        for ((k, v) in customIds) {
            id = "$id$k:$v"
        }

        return id
    }

    internal fun toHashString(): String {
        return Hashing.getHashedString(StatsigUtil.getGson().toJson(this), HashAlgorithm.DJB2)
    }
}
