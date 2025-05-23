package com.statsig.androidsdk

import com.statsig.androidsdk.evaluator.ConfigEvaluation

/** A helper class for interfacing with Feature Gate defined in the Statsig console */
class FeatureGate(
    private val name: String,
    private val details: EvaluationDetails,
    private val value: Boolean,
    private val rule: String = "",
    private val groupName: String? = null,
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val idType: String? = null,
) : BaseConfig(name, details) {
    internal constructor(
        gateName: String,
        apiFeatureGate: APIFeatureGate,
        evalDetails: EvaluationDetails,
    ) : this(
        gateName,
        evalDetails,
        apiFeatureGate.value,
        apiFeatureGate.ruleID,
        apiFeatureGate.groupName,
        apiFeatureGate.secondaryExposures ?: arrayOf(),
        apiFeatureGate.idType,
    )

    internal constructor(
        gateName: String,
        evaluation: ConfigEvaluation,
        details: EvaluationDetails,
    ) : this(
        gateName,
        details,
        evaluation.booleanValue,
        evaluation.ruleID,
        evaluation.groupName,
        evaluation.secondaryExposures.toTypedArray(),
    )

    internal companion object {
        fun getError(name: String): FeatureGate {
            return FeatureGate(name, EvaluationDetails(EvaluationReason.Error, lcut = 0), false, "")
        }
    }

    fun getValue(): Boolean {
        return this.value
    }

    fun getRuleID(): String {
        return this.rule
    }

    fun getGroupName(): String? {
        return this.groupName
    }

    fun getSecondaryExposures(): Array<Map<String, String>> {
        return this.secondaryExposures
    }

    fun getIDType(): String? {
        return this.idType
    }
}
