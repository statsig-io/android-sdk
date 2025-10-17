package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

enum class HashAlgorithm(val value: String) {
    @SerializedName("sha256")
    SHA256("sha256"),

    @SerializedName("djb2")
    DJB2("djb2"),

    @SerializedName("none")
    NONE("none")
}

internal object Hashing {
    private val sha256Cache = BoundedMemo<String, String>()
    private val djb2Cache = BoundedMemo<String, String>()

    fun getHashedString(input: String, algorithm: HashAlgorithm?): String = when (algorithm) {
        HashAlgorithm.DJB2 -> djb2Cache.computeIfAbsent(input) { getDJB2HashString(it) }
        HashAlgorithm.SHA256 -> sha256Cache.computeIfAbsent(input) { getSHA256HashString(it) }
        HashAlgorithm.NONE -> input
        else -> sha256Cache.computeIfAbsent(input) { getSHA256HashString(it) }
    }

    private fun getSHA256HashString(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun getDJB2HashString(input: String): String {
        var hash = 0
        for (c in input.toCharArray()) {
            hash = (hash shl 5) - hash + c.code
            hash = hash and hash
        }

        return (hash.toUInt()).toString()
    }
}
