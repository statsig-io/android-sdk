package com.statsig.androidsdk

/**
 * A helper class for interfacing with Initialize Response currently being used in the Statsig console
 */
class ExternalInitializeResponse(
    private val values: String?,
    private val evaluationDetails: EvaluationDetails
) {
    internal companion object {
        fun getUninitialized(): ExternalInitializeResponse = ExternalInitializeResponse(
            null,
            EvaluationDetails(EvaluationReason.Uninitialized, lcut = 0)
        )
    }
    fun getInitializeResponseJSON(): String? = values

    fun getEvaluationDetails(): EvaluationDetails = evaluationDetails.copy()
}
