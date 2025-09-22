package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

const val DEFAULT_LOGGING_ENABLED: Boolean = true

/**
 * A subset of SDK options that can be defined at initialization and updated at runtime.
 * Call [Statsig.updateRuntimeOptions] or [StatsigClient.updateRuntimeOptions] to update values.
 */
open class StatsigRuntimeMutableOptions(

    /**
     * Controls whether logged events will be sent over the network.
     * [loggingEnabled] defaults to true if a value is not provided.
     */
    @SerializedName("loggingEnabled")
    var loggingEnabled: Boolean = DEFAULT_LOGGING_ENABLED,
)
