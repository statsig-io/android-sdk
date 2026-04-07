package com.statsig.androidsdk

/**
 * A helper class for interfacing with Initialize Response currently being used in the Statsig console
 */
class ExternalInitializeResponse(
    private val values: String?,
    private val evaluationDetails: EvalDetails
) {

    internal companion object {
        fun getUninitialized(): ExternalInitializeResponse = ExternalInitializeResponse(
            null,
            EvalDetails(EvalSource.Uninitialized, EvalReason.Unrecognized)
        )
    }
    fun getInitializeResponseJSON(): String? = values

    fun getEvalDetails(): EvalDetails = evaluationDetails.copy()
}
