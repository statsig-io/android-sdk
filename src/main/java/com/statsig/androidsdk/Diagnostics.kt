package com.statsig.androidsdk

const val NANO_IN_MS = 1_000_000.0
internal class Diagnostics(private var isDisabled: Boolean) {
    companion object {
        fun formatFailedResponse(failResponse: InitializeResponse.FailedInitializeResponse): Marker.ErrorMessage {
            val name = failResponse.exception?.javaClass?.toString() ?: "unknown"
            val message = "${failResponse.reason} : ${failResponse.exception?.message}"
            return Marker.ErrorMessage(message = message, name = name)
        }
    }
    var diagnosticsContext: ContextType = ContextType.INITIALIZE
    private var defaultMaxMarkers: Int = 30

    private var maxMarkers: MutableMap<ContextType, Int> = mutableMapOf(ContextType.INITIALIZE to this.defaultMaxMarkers, ContextType.API_CALL to this.defaultMaxMarkers, ContextType.EVENT_LOGGING to 0, ContextType.CONFIG_SYNC to 0)

    private var markers: DiagnosticsMarkers = mutableMapOf()

    fun getIsDisabled(): Boolean {
        return this.isDisabled
    }
    fun getMarkers(context: ContextType? = null): List<Marker> {
        return this.markers[context ?: this.diagnosticsContext] ?: listOf()
    }

    fun setMaxMarkers(context: ContextType, maxMarkers: Int) {
        this.maxMarkers[context] = maxMarkers
    }

    fun clearContext(context: ContextType? = null) {
        this.markers[context ?: this.diagnosticsContext] = mutableListOf()
    }

    fun clearAllContext() {
        this.markers = mutableMapOf()
    }

    fun markStart(key: KeyType, step: StepType? = null, additionalMarker: Marker? = null, overrideContext: ContextType? = null): Boolean {
        if (this.isDisabled) {
            return false
        }
        val context = overrideContext ?: this.diagnosticsContext
        if (this.getMaxMarkers(context) < (this.markers[context]?.size ?: 0)
        ) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.START, timestamp = System.nanoTime() / NANO_IN_MS, step = step)
        when (context) {
            ContextType.INITIALIZE -> {
                if (key == KeyType.INITIALIZE && step == StepType.NETWORK_REQUEST) {
                    marker.attempt = additionalMarker?.attempt
                }
            }
            ContextType.API_CALL -> {
                marker.markerID = additionalMarker?.markerID
            }

            else -> return false
        }
        return this.addMarker(marker, overrideContext)
    }

    fun markEnd(key: KeyType, success: Boolean, step: StepType? = null, additionalMarker: Marker? = null, overrideContext: ContextType? = null): Boolean {
        if (this.isDisabled) {
            return false
        }
        val context = overrideContext ?: this.diagnosticsContext
        if (this.getMaxMarkers(context) < (this.markers[context]?.size ?: 0)) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.END, timestamp = System.nanoTime() / NANO_IN_MS, success = success, step = step)
        when (context) {
            ContextType.INITIALIZE -> {
                marker.evaluationDetails = additionalMarker?.evaluationDetails
                marker.attempt = additionalMarker?.attempt
                marker.sdkRegion = additionalMarker?.sdkRegion
                marker.statusCode = additionalMarker?.statusCode
                marker.error = additionalMarker?.error
            }

            ContextType.API_CALL -> {
                marker.markerID = additionalMarker?.markerID
                marker.configName = additionalMarker?.configName
            }

            else -> return false
        }
        return this.addMarker(marker, overrideContext)
    }

    private fun getMaxMarkers(context: ContextType): Int {
        return this.maxMarkers[context] ?: this.defaultMaxMarkers
    }

    private fun addMarker(marker: Marker, overrideContext: ContextType? = null): Boolean {
        val context = overrideContext ?: this.diagnosticsContext
        if (this.getMaxMarkers(context) <= (this.markers[context]?.size ?: 0)) {
            return false
        }
        if (this.markers[context] == null) {
            this.markers[context] = mutableListOf()
        }
        this.markers[context]?.add(marker)
        this.markers.values
        return true
    }
}
