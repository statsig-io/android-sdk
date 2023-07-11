package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

enum class HashAlgorithm(val value: String) {
    @SerializedName("sha256")
    SHA256("sha256"),

    @SerializedName("djb2")
    DJB2("djb2"),

    @SerializedName("none")
    NONE("none"),
}

internal object Hashing {
    fun getHashedString(input: String, algorithm: HashAlgorithm?): String {
        return when (algorithm) {
            HashAlgorithm.DJB2 -> getDJB2HashString(input)
            HashAlgorithm.SHA256 -> getSHA256HashString(input)
            HashAlgorithm.NONE -> input
            else -> getSHA256HashString(input)
        }
    }

    private fun getSHA256HashString(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun getDJB2HashString(input: String): String {
        var hash = 0
        for (c in input.toCharArray()) {
            hash = (hash shl 5) - hash + c.code
            hash = hash and hash
        }

        return (hash.toUInt()).toString()
    }
}
