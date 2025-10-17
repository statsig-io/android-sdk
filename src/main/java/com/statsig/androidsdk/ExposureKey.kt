package com.statsig.androidsdk

sealed class ExposureKey {
    data class Gate(
        val name: String,
        val ruleID: String,
        val reason: EvaluationReason,
        val value: Boolean
    ) : ExposureKey()

    data class Config(val name: String, val ruleID: String, val reason: EvaluationReason) :
        ExposureKey()

    data class Layer(
        val configName: String,
        val ruleID: String,
        val allocatedExperiment: String,
        val parameterName: String,
        val isExplicitParameter: Boolean,
        val reason: EvaluationReason
    ) : ExposureKey()
}
