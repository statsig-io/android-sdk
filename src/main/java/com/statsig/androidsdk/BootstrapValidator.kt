package com.statsig.androidsdk

import android.util.Log
import java.lang.Exception
import java.util.concurrent.ConcurrentHashMap

object BootstrapValidator {
    fun isValid(initializeValues:Map<String, Any>, user: StatsigUser): Boolean {
        try {
            // If no evaluated key being passed in, return true
            val evaluatedKey = initializeValues["evaluated_keys"] as? Map<String, Any> ?: return true
            val userCopy = getUserIdentifier(user.customIDs as? Map<String, Any> ?: mapOf())
            userCopy["userID"] = user.userID
            val evaluatedKeyCopy = getUserIdentifier(evaluatedKey)
            // compare each key value pair in the map
            return userCopy == evaluatedKeyCopy;
        } catch (_e: Exception) {
            // Best effort, return true if we fail
            return true
        }
    }

    private fun getUserIdentifier(customIDs: Map<String,Any>): MutableMap<String, String?> {
        var result: MutableMap<String, String?> = ConcurrentHashMap()
        for(entry in customIDs.entries.iterator()) {
            if (entry.key == "stableID") {
                // ignore stableID
                continue
            }
            val value = entry.value
            if (value is String?) {
                result[entry.key] = value
            } else {
                // Nested Map for Custom IDs
                val nestedMap: Map<String,Any>? = value as? Map<String, Any>
                if (nestedMap != null) {
                    val flattenMap =  getUserIdentifier(nestedMap)
                    result.putAll(flattenMap);
                }
            }
        }
        return result
    }
}