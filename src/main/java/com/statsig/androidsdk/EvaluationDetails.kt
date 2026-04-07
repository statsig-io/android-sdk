package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import kotlin.Long

enum class EvalReason {
    Recognized,
    Unrecognized,
    Sticky,
    LocalOverride,
    StableIdMismatch
}

enum class EvalSource {
    Error,
    Uninitialized,
    Loading,
    NoValues,
    Network,
    NetworkNotModified,
    Cache,
    Bootstrap,
    InvalidBootstrap,
    OnDeviceEvalAdapterBootstrap;

    override fun toString(): String = when (this) {
        OnDeviceEvalAdapterBootstrap -> "[OnDevice]Bootstrap"
        else -> this.name
    }
}

/** Evaluation result details with explicit source/reason modeling. */
data class EvalDetails(
    var source: EvalSource,
    var reason: EvalReason?,
    val lcut: Long? = null,
    val receivedAt: Long? = null
) {
    fun getDetailedReasonString(): String {
        var ret = source.toString()
        if (source == EvalSource.NoValues || source == EvalSource.Uninitialized) {
            return ret
        }
        reason?.let { ret += ":$reason" }
        return ret
    }

    fun toLoggingEvaluationDetails(): OverWireEvalDetails {
        if (source == EvalSource.NoValues || source == EvalSource.Uninitialized) {
            return OverWireEvalDetails(getDetailedReasonString())
        }
        return OverWireEvalDetails(getDetailedReasonString(), lcut = lcut, receivedAt = receivedAt)
    }
}

data class OverWireEvalDetails(
    @SerializedName("reason")
    val reason: String,
    @SerializedName("receivedAt")
    val receivedAt: Long? = null,
    @SerializedName("lcut")
    val lcut: Long? = null
)
