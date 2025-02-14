package com.statsig.androidsdk.evaluator

import com.statsig.androidsdk.Statsig
import com.statsig.androidsdk.StatsigUser
import java.lang.Long.parseLong
import java.text.SimpleDateFormat
import java.util.Date

internal object EvaluatorUtils {

    fun getValueAsString(input: Any?): String? {
        if (input == null) {
            return null
        }
        if (input is String) {
            return input
        }
        return input.toString()
    }

    fun getValueAsDouble(input: Any?): Double? {
        if (input == null) {
            return null
        }

        if (input is String) {
            return input.toDoubleOrNull()
        }

        if (input is ULong) {
            return input.toDouble()
        }

        if (input is Double) {
            return input
        }

        if (input is Number) {
            return input.toDouble()
        }

        return null
    }

    fun contains(targets: Any?, value: Any?, ignoreCase: Boolean): Boolean {
        if (targets == null || value == null) {
            return false
        }
        val iterable: Iterable<*> =
            when (targets) {
                is Iterable<*> -> {
                    targets
                }

                is Array<*> -> {
                    targets.asIterable()
                }

                else -> {
                    return false
                }
            }
        for (option in iterable) {
            if ((option is String) && (value is String) && option.equals(value, ignoreCase)) {
                return true
            }
            if (option == value) {
                return true
            }
        }
        return false
    }

    fun getUserValueForField(user: StatsigUser, field: String): Any? {
        return when (field) {
            "userid", "user_id" -> user.userID
            "email" -> user.email
            "ip", "ipaddress", "ip_address" -> user.ip
            "useragent", "user_agent" -> user.userAgent
            "country" -> user.country
            "locale" -> user.locale
            "appversion", "app_version" -> user.appVersion
            else -> null
        }
    }

    fun getFromUser(user: StatsigUser, field: String): Any? {
        var value: Any? =
            getUserValueForField(user, field) ?: getUserValueForField(user, field.lowercase())

        if ((value == null || value == "") && user.custom != null) {
            value = user.custom?.get(field) ?: user.custom?.get(field.lowercase())
        }
        if ((value == null || value == "") && user.privateAttributes != null) {
            value =
                user.privateAttributes?.get(field)
                    ?: user.privateAttributes?.get(field.lowercase())
        }

        return value
    }

    fun getFromEnvironment(user: StatsigUser, field: String): String? {
        return user.statsigEnvironment?.get(field)
            ?: user.statsigEnvironment?.get(field.lowercase())
    }

    fun getUnitID(user: StatsigUser, idType: String?): String? {
        val lowerIdType = idType?.lowercase()
        if (lowerIdType != "userid" && lowerIdType?.isEmpty() == false) {
            return user.customIDs?.get(idType) ?: user.customIDs?.get(lowerIdType)
        }
        return user.userID
    }

    fun matchStringInArray(
        value: Any?,
        target: Any?,
        compare: (value: String, target: String) -> Boolean,
    ): Boolean {
        val strValue = getValueAsString(value) ?: return false
        val iterable =
            when (target) {
                is Iterable<*> -> {
                    target
                }

                is Array<*> -> {
                    target.asIterable()
                }

                else -> {
                    return false
                }
            }

        for (match in iterable) {
            val strMatch = this.getValueAsString(match) ?: continue
            if (compare(strValue, strMatch)) {
                return true
            }
        }
        return false
    }

    fun compareDates(
        compare: ((a: Date, b: Date) -> Boolean),
        a: Any?,
        b: Any?,
    ): ConfigEvaluation {
        if (a == null || b == null) {
            return ConfigEvaluation(booleanValue = false)
        }

        val firstEpoch = getDate(a)
        val secondEpoch = getDate(b)

        if (firstEpoch == null || secondEpoch == null) {
            return ConfigEvaluation(
                booleanValue = false,
            )
        }
        return ConfigEvaluation(
            booleanValue = compare(firstEpoch, secondEpoch),
        )
    }

    private fun getEpoch(input: Any?): Long? {
        var epoch =
            when (input) {
                is String -> parseLong(input)
                is Number -> input.toLong()
                else -> return null
            }

        if (epoch.toString().length < 11) {
            // epoch in seconds (milliseconds would be before 1970)
            epoch *= 1000
        }

        return epoch
    }

    private fun parseISOTimestamp(input: Any?): Date? {
        if (input is String) {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                return format.parse(input)
            } catch (e: Exception) {
                Statsig.client.errorBoundary.logException(e, "parseISOTimestamp")
                null
            }
        }
        return null
    }

    private fun getDate(input: Any?): Date? {
        if (input == null) {
            return null
        }
        return try {
            val epoch: Long = getEpoch(input) ?: return parseISOTimestamp(input)
            Date(epoch)
        } catch (e: Exception) {
            parseISOTimestamp(input)
        }
    }

    fun versionCompare(v1: String, v2: String): Int {
        val parts1 = v1.split(".")
        val parts2 = v2.split(".")

        var i = 0
        while (i < parts1.size.coerceAtLeast(parts2.size)) {
            var c1 = 0
            var c2 = 0
            if (i < parts1.size) {
                c1 = parts1[i].toInt()
            }
            if (i < parts2.size) {
                c2 = parts2[i].toInt()
            }
            if (c1 < c2) {
                return -1
            } else if (c1 > c2) {
                return 1
            }
            i++
        }
        return 0
    }
}
