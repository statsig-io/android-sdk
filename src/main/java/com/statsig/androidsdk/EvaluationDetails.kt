package com.statsig.androidsdk

enum class EvaluationReason {
    Network,
    Cache,
    Sticky,
    LocalOverride,
    Unrecognized,
    Uninitialized,
    Bootstrap,
    InvalidBootstrap,
    NetworkNotModified,
    Error,
}

data class EvaluationDetails(
    var reason: EvaluationReason,
    val time: Long = System.currentTimeMillis(),
)
