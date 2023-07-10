package com.statsig.androidsdk

import android.content.SharedPreferences
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal object StatsigUtil {
    private val dispatcherProvider = CoroutineDispatcherProvider()
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
                value.size == (value.filter { it is String }).size
            } else {
                value is String ||
                    value is Boolean ||
                    value is Double
            }
        }
    }

    internal fun syncGetFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String): String? {
        if (sharedPrefs == null) {
            return null
        }
        return try {
            sharedPrefs.getString(key, null)
        } catch (e: ClassCastException) {
            null
        }
    }

    internal suspend fun saveStringToSharedPrefs(sharedPrefs: SharedPreferences?, key: String, value: String) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            val editor = sharedPrefs.edit()
            editor.putString(key, value)
            editor.apply()
        }
    }

    internal suspend fun removeFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            val editor = sharedPrefs.edit()
            editor.remove(key)
            editor.apply()
        }
    }

    internal suspend fun getFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String): String? {
        if (sharedPrefs == null) {
            return null
        }
        return withContext(dispatcherProvider.io) {
            return@withContext try {
                sharedPrefs.getString(key, null)
            } catch (e: ClassCastException) {
                removeFromSharedPrefs(sharedPrefs, key)
                null
            }
        }
    }
}
