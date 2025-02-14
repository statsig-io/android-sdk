package com.statsig.androidsdk.evaluator

import android.util.Log
import com.statsig.androidsdk.Statsig
import com.statsig.androidsdk.StatsigUser
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date

internal class ConfigEvaluation(
    val booleanValue: Boolean = false,
    val jsonValue: Any? = null,
    val returnableValue: ReturnableValue? = null,
    val ruleID: String = "",
    val groupName: String? = null,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val explicitParameters: List<String>? = null,
    val configDelegate: String? = null,
    val isExperimentGroup: Boolean = false,
    val isActive: Boolean = false,
    val isUnrecognized: Boolean = false,
    var configVersion: Int? = null,
) {
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>> = secondaryExposures
}

internal enum class ConfigCondition {
    PUBLIC, FAIL_GATE, PASS_GATE, IP_BASED, UA_BASED, USER_FIELD, CURRENT_TIME, ENVIRONMENT_FIELD, USER_BUCKET, UNIT_ID,
}

internal class Evaluator(private val store: SpecStore) {
    private val calendarOne = Calendar.getInstance()
    private val calendarTwo = Calendar.getInstance()
    private var hashLookupTable: MutableMap<String, ULong> = HashMap()

    internal fun evaluateGate(name: String, user: StatsigUser): ConfigEvaluation {
        val spec = store.getGate(name) ?: return ConfigEvaluation(isUnrecognized = true)
        return evaluate(user, spec)
    }

    internal fun evaluateConfig(name: String, user: StatsigUser): ConfigEvaluation {
        val spec = store.getConfig(name) ?: return ConfigEvaluation(isUnrecognized = true)
        return evaluate(user, spec)
    }

    internal fun evaluateLayer(name: String, user: StatsigUser): ConfigEvaluation {
        val spec = store.getLayer(name) ?: return ConfigEvaluation(isUnrecognized = true)
        return evaluate(user, spec)
    }

    private fun evaluate(user: StatsigUser, spec: Spec): ConfigEvaluation {
        try {
            if (!spec.enabled) {
                return ConfigEvaluation(
                    booleanValue = false,
                    spec.defaultValue.getValue(),
                    spec.defaultValue,
                    "disabled",
                    configVersion = spec.version,
                )
            }

            val secondaryExposures = arrayListOf<Map<String, String>>()

            for (rule in spec.rules) {
                val result = this.evaluateRule(user, rule)
                secondaryExposures.addAll(result.secondaryExposures)

                if (result.booleanValue) {
                    val delegatedEval = this.evaluateDelegate(user, rule, secondaryExposures)

                    if (delegatedEval != null) {
                        delegatedEval.configVersion = spec.version
                        return delegatedEval
                    }

                    val pass = evaluatePassPercent(user, spec, rule)
                    return ConfigEvaluation(
                        pass,
                        if (pass) result.jsonValue else spec.defaultValue.getValue(),
                        if (pass) result.returnableValue else spec.defaultValue,
                        result.ruleID,
                        result.groupName,
                        secondaryExposures,
                        isExperimentGroup = rule.isExperimentGroup ?: false,
                        isActive = spec.isActive,
                        configVersion = spec.version,
                    )
                }
            }

            return ConfigEvaluation(
                booleanValue = false,
                spec.defaultValue.getValue(),
                spec.defaultValue,
                "default",
                null,
                secondaryExposures,
                configVersion = spec.version,
                isActive = spec.isActive,
            )
        } catch (e: UnsupportedEvaluationException) {
            // Return default value for unsupported evaluation
            Statsig.client.errorBoundary.logException(e, "evaluate")

            return ConfigEvaluation(
                booleanValue = false,
                jsonValue = spec.defaultValue,
                ruleID = "default",
                explicitParameters = spec.explicitParameters ?: listOf(),
                configVersion = spec.version,
                isActive = spec.isActive,
            )
        }
    }

    private fun evaluateRule(user: StatsigUser, rule: SpecRule): ConfigEvaluation {
        val secondaryExposures = arrayListOf<Map<String, String>>()
        var pass = true
        for (condition in rule.conditions) {
            val result = this.evaluateCondition(user, condition)
            if (!result.booleanValue) {
                pass = false
            }
            secondaryExposures.addAll(result.secondaryExposures)
        }

        return ConfigEvaluation(
            booleanValue = pass,
            rule.returnValue.getValue(),
            rule.returnValue,
            rule.id,
            rule.groupName,
            secondaryExposures,
            isExperimentGroup = rule.isExperimentGroup == true,
        )
    }

    private fun evaluateDelegate(
        user: StatsigUser,
        rule: SpecRule,
        secondaryExposures: ArrayList<Map<String, String>>,
    ): ConfigEvaluation? {
        val configDelegate = rule.configDelegate ?: return null
        val config = store.getConfig(configDelegate) ?: return null

        val delegatedResult = this.evaluate(user, config)
        val undelegatedSecondaryExposures = arrayListOf<Map<String, String>>()
        undelegatedSecondaryExposures.addAll(secondaryExposures)
        secondaryExposures.addAll(delegatedResult.secondaryExposures)

        val evaluation = ConfigEvaluation(
            booleanValue = delegatedResult.booleanValue,
            jsonValue = delegatedResult.jsonValue,
            returnableValue = delegatedResult.returnableValue,
            ruleID = delegatedResult.ruleID,
            groupName = delegatedResult.groupName,
            secondaryExposures = secondaryExposures,
            configDelegate = configDelegate,
            explicitParameters = config.explicitParameters,
            isExperimentGroup = delegatedResult.isExperimentGroup,
            isActive = delegatedResult.isActive,
        )

        evaluation.undelegatedSecondaryExposures = undelegatedSecondaryExposures
        return evaluation
    }

    private fun evaluateCondition(user: StatsigUser, condition: SpecCondition): ConfigEvaluation {
        try {
            var value: Any?
            val field: String = condition.field ?: ""
            val conditionEnum: ConfigCondition? = try {
                ConfigCondition.valueOf(condition.type.uppercase())
            } catch (e: java.lang.IllegalArgumentException) {
                throw UnsupportedEvaluationException("Unsupported condition: ${condition.type}")
            }

            when (conditionEnum) {
                ConfigCondition.PUBLIC -> return ConfigEvaluation(booleanValue = true)

                ConfigCondition.FAIL_GATE, ConfigCondition.PASS_GATE -> {
                    val name = condition.targetValue?.toString() ?: ""
                    val result = this.evaluateGate(name, user)

                    val secondaryExposures = arrayListOf<Map<String, String>>()
                    secondaryExposures.addAll(result.secondaryExposures)

                    if (!name.startsWith("segment:")) {
                        val newExposure = mapOf(
                            "gate" to name,
                            "gateValue" to result.booleanValue.toString(),
                            "ruleID" to result.ruleID,
                        )
                        secondaryExposures.add(newExposure)
                    }

                    return ConfigEvaluation(
                        if (conditionEnum == ConfigCondition.PASS_GATE) {
                            result.booleanValue
                        } else {
                            !result.booleanValue
                        },
                        result.jsonValue,
                        result.returnableValue,
                        "",
                        "",
                        secondaryExposures,
                    )
                }

                ConfigCondition.USER_FIELD, ConfigCondition.IP_BASED, ConfigCondition.UA_BASED -> {
                    value = EvaluatorUtils.getFromUser(user, field)
                }

                ConfigCondition.CURRENT_TIME -> {
                    value = System.currentTimeMillis().toString()
                }

                ConfigCondition.ENVIRONMENT_FIELD -> {
                    value = EvaluatorUtils.getFromEnvironment(user, field)
                }

                ConfigCondition.USER_BUCKET -> {
                    val salt =
                        EvaluatorUtils.getValueAsString(condition.additionalValues?.let { it["salt"] })
                    val unitID = EvaluatorUtils.getUnitID(user, condition.idType) ?: ""
                    value = computeUserHash("$salt.$unitID").mod(1000UL)
                }

                ConfigCondition.UNIT_ID -> {
                    value = EvaluatorUtils.getUnitID(user, condition.idType)
                }

                else -> {
                    Log.d("STATSIG", "Unsupported evaluation condition: $conditionEnum")
                    throw UnsupportedEvaluationException("Unsupported evaluation condition: $conditionEnum")
                }
            }

            when (condition.operator) {
                "gt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(
                        doubleValue > doubleTargetValue,
                    )
                }

                "gte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(
                        doubleValue >= doubleTargetValue,
                    )
                }

                "lt" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(

                        doubleValue < doubleTargetValue,
                    )
                }

                "lte" -> {
                    val doubleValue = EvaluatorUtils.getValueAsDouble(value)
                    val doubleTargetValue = EvaluatorUtils.getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(booleanValue = false)
                    }
                    return ConfigEvaluation(

                        doubleValue <= doubleTargetValue,
                    )
                }

                "version_gt" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) > 0
                        },
                    )
                }

                "version_gte" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) >= 0
                        },
                    )
                }

                "version_lt" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) < 0
                        },
                    )
                }

                "version_lte" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) <= 0
                        },
                    )
                }

                "version_eq" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) == 0
                        },
                    )
                }

                "version_neq" -> {
                    return ConfigEvaluation(
                        versionCompareHelper(
                            value,
                            condition.targetValue,
                        ) { v1: String, v2: String ->
                            EvaluatorUtils.versionCompare(v1, v2) != 0
                        },
                    )
                }

                "any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "none" -> {
                    return ConfigEvaluation(
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }

                "any_case_sensitive" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "none_case_sensitive" -> {
                    return ConfigEvaluation(
                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }

                "str_starts_with_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.startsWith(b, true)
                        },
                    )
                }

                "str_ends_with_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.endsWith(b, true)
                        },
                    )
                }

                "str_contains_any" -> {
                    return ConfigEvaluation(
                        EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_contains_none" -> {
                    return ConfigEvaluation(

                        !EvaluatorUtils.matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }

                "str_matches" -> {
                    val targetValue = EvaluatorUtils.getValueAsString(condition.targetValue)
                        ?: return ConfigEvaluation(
                            booleanValue = false,
                        )

                    val strValue =
                        EvaluatorUtils.getValueAsString(value) ?: return ConfigEvaluation(
                            booleanValue = false,
                        )

                    return ConfigEvaluation(
                        booleanValue = Regex(targetValue).containsMatchIn(strValue),
                    )
                }

                "eq" -> {
                    return ConfigEvaluation(value == condition.targetValue)
                }

                "neq" -> {
                    return ConfigEvaluation(value != condition.targetValue)
                }

                "before" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.before(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                "after" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.after(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                "on" -> {
                    return EvaluatorUtils.compareDates(
                        { a: Date, b: Date ->
                            calendarOne.time = a
                            calendarTwo.time = b
                            return@compareDates calendarOne[Calendar.YEAR] == calendarTwo[Calendar.YEAR] && calendarOne[Calendar.DAY_OF_YEAR] == calendarTwo[Calendar.DAY_OF_YEAR]
                        },
                        value,
                        condition.targetValue,
                    )
                }

                else -> {
                    throw UnsupportedEvaluationException("Unsupported evaluation conditon operator: ${condition.operator}")
                }
            }
        } catch (e: IllegalArgumentException) {
            throw UnsupportedEvaluationException("IllegalArgumentException when evaluate conditions")
        }
    }

    private fun versionCompareHelper(
        version1: Any?,
        version2: Any?,
        compare: (v1: String, v2: String) -> Boolean,
    ): Boolean {
        var version1Str = EvaluatorUtils.getValueAsString(version1)
        var version2Str = EvaluatorUtils.getValueAsString(version2)

        if (version1Str == null || version2Str == null) {
            return false
        }

        val dashIndex1 = version1Str.indexOf('-')
        if (dashIndex1 > 0) {
            version1Str = version1Str.substring(0, dashIndex1)
        }

        val dashIndex2 = version2Str.indexOf('-')
        if (dashIndex2 > 0) {
            version2Str = version2Str.substring(0, dashIndex2)
        }

        return try {
            compare(version1Str, version2Str)
        } catch (e: NumberFormatException) {
            false
        } catch (e: Exception) {
            Statsig.client.errorBoundary.logException(e, "versionCompareHelper")
            false
        }
    }

    private fun evaluatePassPercent(user: StatsigUser, spec: Spec, rule: SpecRule): Boolean {
        return computeUserHash(
            spec.salt +
                '.' +
                (rule.salt ?: rule.id) +
                '.' +
                (EvaluatorUtils.getUnitID(user, rule.idType) ?: ""),
        ).mod(10000UL) < (rule.passPercentage.times(100.0)).toULong()
    }

    private fun computeUserHash(input: String): ULong {
        hashLookupTable[input]?.let {
            return it
        }

        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        val hash = ByteBuffer.wrap(bytes).long.toULong()

        if (hashLookupTable.size > 1000) {
            hashLookupTable.clear()
        }

        hashLookupTable[input] = hash
        return hash
    }
}

class UnsupportedEvaluationException(message: String) : Exception(message)
