package com.statsig.androidsdk

open class BaseConfig(private val name: String, private val details: EvalDetails) {
    open fun getName(): String = this.name

    open fun getEvalDetails(): EvalDetails = this.details
}
