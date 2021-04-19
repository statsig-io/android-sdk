package com.statsig.androidsdk

import java.security.MessageDigest

object StatsigUtil {
    fun getHashedString(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun normalizeUser(user: Map<String, Any>?): Map<String, Any>? {
        if (user == null) {
            return null
        }
        return user.filterValues { value ->
            if (value is Array<*>) {
                value.size == (value.filter {it is String}).size
            } else {
                value is String ||
                value is Boolean ||
                value is Int ||
                value is Double
            }
        }
    }
}