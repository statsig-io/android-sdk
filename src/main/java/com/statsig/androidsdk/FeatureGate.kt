package com.statsig.androidsdk

import com.statsig.androidsdk.evaluator.ConfigEvaluation

/** A helper class for interfacing with Feature Gate defined in the Statsig console */
class FeatureGate(
    private val name: String,
    private val details: EvalDetails,
    private val value: Boolean,
    private val rule: String = "",
    private val groupName: String? = null,
    private val secondaryExposures: Array<Map<String, String>> = arrayOf(),
    private val idType: String? = null
) : BaseConfig(name, details) {
    internal constructor(
        gateName: String,
        apiFeatureGate: APIFeatureGate,
        evalDetails: EvalDetails
    ) : this(
        gateName,
        evalDetails,
        apiFeatureGate.value,
        apiFeatureGate.ruleID,
        apiFeatureGate.groupName,
        apiFeatureGate.secondaryExposures ?: arrayOf(),
        apiFeatureGate.idType
    )

    internal constructor(
        gateName: String,
        evaluation: ConfigEvaluation,
        details: EvalDetails
    ) : this(
        gateName,
        details,
        evaluation.booleanValue,
        evaluation.ruleID,
        evaluation.groupName,
        evaluation.secondaryExposures.toTypedArray()
    )

    internal companion object {
        fun getError(name: String): FeatureGate = FeatureGate(
            name,
            EvalDetails(EvalSource.Error, EvalReason.Unrecognized, lcut = 0),
            false,
            ""
        )
    }

    fun getValue(): Boolean = this.value

    fun getRuleID(): String = this.rule

    fun getGroupName(): String? = this.groupName

    fun getSecondaryExposures(): Array<Map<String, String>> = this.secondaryExposures

    fun getIDType(): String? = this.idType
}
