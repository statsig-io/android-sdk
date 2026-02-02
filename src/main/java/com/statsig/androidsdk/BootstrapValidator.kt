package com.statsig.androidsdk

object BootstrapValidator {
    fun isValid(initializeValues: Map<String, Any>, user: StatsigUser): Boolean {
        try {
            // If no evaluated key being passed in, return true
            val evaluatedKeys = initializeValues["evaluated_keys"] as? Map<*, *> ?: return true
            val userCopy = getUserIdentifier(user.customIDs)
            if (user.userID != null) {
                userCopy["userID"] = user.userID
            }
            val evaluatedKeyCopy = getUserIdentifier(evaluatedKeys)
            // compare each key value pair in the map
            return userCopy == evaluatedKeyCopy
        } catch (e: Exception) {
            // Best effort, return true if we fail
            return true
        }
    }

    private fun getUserIdentifier(customIDs: Map<*, *>?): MutableMap<String, String?> {
        val result: MutableMap<String, String?> = mutableMapOf()
        if (customIDs == null) {
            return result
        }

        for (entry in customIDs.entries.iterator()) {
            val key = entry.key
            if (key == "stableID" || key !is String) {
                // ignore stableID
                continue
            }

            val value = entry.value
            if (value is String?) {
                result[key] = value
                continue
            }

            if (value is Map<*, *>) {
                val flattenMap = getUserIdentifier(value)
                result.putAll(flattenMap)
            }
        }
        return result
    }
}
