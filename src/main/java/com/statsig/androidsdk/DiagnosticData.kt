package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

/*
Interface
* */
data class Marker(
    @SerializedName("key") val key: KeyType? = null,
    @SerializedName("action") val action: ActionType? = null,
    @SerializedName("timestamp") val timestamp: Double? = null,
    @SerializedName("step") var step: StepType? = null,
    @SerializedName("statusCode") var statusCode: Int? = null,
    @SerializedName("success") var success: Boolean? = null,
    @SerializedName("url") var url: String? = null,
    @SerializedName("idListCount") var idListCount: Int? = null,
    @SerializedName("reason") var reason: String? = null,
    @SerializedName("sdkRegion") var sdkRegion: String? = null,
    @SerializedName("markerID") var markerID: String? = null,
    @SerializedName("attempt") var attempt: Int? = null,
    @SerializedName("retryLimit") var retryLimit: Int? = null,
    @SerializedName("isRetry") var isRetry: Boolean? = null,
    @SerializedName("isDelta") var isDelta: Boolean? = null,
    @SerializedName("configName") var configName: String? = null,
)

enum class ContextType {
    @SerializedName("initialize")
    INITIALIZE,

    @SerializedName("api_call")
    API_CALL,

    @SerializedName("config_sync")
    CONFIG_SYNC,

    @SerializedName("event_logging")
    EVENT_LOGGING,
}

enum class KeyType {
    @SerializedName("initialize")
    INITIALIZE,

    @SerializedName("bootstrap")
    BOOTSTRAP,

    @SerializedName("overall")
    OVERALL,

    @SerializedName("check_gate")
    CHECK_GATE,

    @SerializedName("get_config")
    GET_CONFIG,

    @SerializedName("get_experiment")
    GET_EXPERIMENT,

    @SerializedName("get_layer")
    GET_LAYER,

    ;

    companion object {
        fun convertFromString(value: String): KeyType? {
            return when (value) {
                in "checkGate" ->
                    KeyType.CHECK_GATE
                in "getExperiment" ->
                    KeyType.GET_EXPERIMENT
                in "getConfig" ->
                    KeyType.GET_CONFIG
                in "getLayer" ->
                    KeyType.GET_LAYER
                else ->
                    null
            }
        }
    }
}

enum class StepType {
    @SerializedName("process")
    PROCESS,

    @SerializedName("network_request")
    NETWORK_REQUEST,
}

enum class ActionType {
    @SerializedName("start")
    START,

    @SerializedName("end")
    END,
}

typealias DiagnosticsMarkers = MutableMap<ContextType, MutableList<Marker>>