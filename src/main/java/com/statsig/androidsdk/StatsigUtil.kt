package com.statsig.androidsdk

import java.security.MessageDigest

object StatsigUtil {
    fun getHashedString(gateName: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = gateName.toByteArray()
        val bytes = md.digest(input)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}