package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class LogEventResponse(
    @SerializedName("success") val success: Boolean?
)