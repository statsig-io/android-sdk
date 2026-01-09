package com.statsig.androidsdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy

internal object StatsigUtil {
    private val gson by lazy {
        GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create()
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

    internal fun getOrBuildGson(): Gson = gson
}
