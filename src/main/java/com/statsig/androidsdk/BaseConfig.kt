package com.statsig.androidsdk

open class BaseConfig(
    private val name: String,
    private val details: EvaluationDetails,
) {
    open fun getName(): String {
        return this.name
    }

    open fun getEvaluationDetails(): EvaluationDetails {
        return this.details
    }
}
