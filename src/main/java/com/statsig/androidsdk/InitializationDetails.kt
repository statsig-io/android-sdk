package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

/**
 * Details relating to the initialize process
 * Passed in initCallback and returned in static initialize call
 * @property duration the time in milliseconds it took for initialize to complete
 * @property success boolean indicating whether initialize was successful or not
 * @property failureDetails additional details on failure
 */
data class InitializationDetails(
    @SerializedName("duration")
    var duration: Long,
    @SerializedName("success")
    var success: Boolean,
    @SerializedName("failureDetails")
    var failureDetails: InitializeResponse.FailedInitializeResponse? = null,
)
