package com.statsig.androidsdk

enum class EvaluationReason {
    Network,
    Cache,
    Sticky,
    LocalOverride,
    Unrecognized,
    Uninitialized,
    Bootstrap,
    OnDeviceEvalAdapterBootstrapRecognized,
    OnDeviceEvalAdapterBootstrapUnrecognized,
    InvalidBootstrap,
    NetworkNotModified,
    Error ;

    override fun toString(): String = when (this) {
        OnDeviceEvalAdapterBootstrapRecognized -> "[OnDevice]Bootstrap:Recognized"
        OnDeviceEvalAdapterBootstrapUnrecognized -> "[OnDevice]Bootstrap:Unrecognized"
        else -> this.name
    }
}

data class EvaluationDetails(
    var reason: EvaluationReason,
    val time: Long = System.currentTimeMillis(),
    @Transient val lcut: Long
)
