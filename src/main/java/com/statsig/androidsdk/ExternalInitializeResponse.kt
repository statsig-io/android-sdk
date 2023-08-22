package com.statsig.androidsdk

/**
 * A helper class for interfacing with Initialize Response currently being used in the Statsig console
 */
class ExternalInitializeResponse(
    private val values: String?,
    private val evaluationDetails: EvaluationDetails,
) {
    internal companion object {
        fun getUninitialized(): ExternalInitializeResponse {
            return ExternalInitializeResponse(null, EvaluationDetails(EvaluationReason.Uninitialized))
        }
    }
    fun getInitializeResponseJSON(): String? {
        return values
    }

    fun getEvaluationDetails(): EvaluationDetails {
        return evaluationDetails.copy()
    }
}
