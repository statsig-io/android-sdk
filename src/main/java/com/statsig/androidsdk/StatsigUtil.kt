package com.statsig.androidsdk

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.withContext

internal object StatsigUtil {
    private val dispatcherProvider = CoroutineDispatcherProvider()

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

    internal suspend fun saveStringToSharedPrefs(
        sharedPrefs: SharedPreferences?,
        key: String,
        value: String
    ) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            sharedPrefs.edit {
                putString(key, value)
            }
        }
    }

    internal suspend fun removeFromSharedPrefs(sharedPrefs: SharedPreferences?, key: String) {
        if (sharedPrefs == null) {
            return
        }
        withContext(dispatcherProvider.io) {
            sharedPrefs.edit {
                remove(key)
            }
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

    internal fun getGson(): Gson = GsonBuilder()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()
}
