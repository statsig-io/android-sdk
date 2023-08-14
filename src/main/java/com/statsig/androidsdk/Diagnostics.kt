package com.statsig.androidsdk

const val NANO_IN_MS = 1_000_000.0
internal class Diagnostics(private var isDisabled: Boolean) {
    var diagnosticsContext: ContextType = ContextType.INITIALIZE
    private var defaultMaxMarkers: Int = 30
    private var maxMarkers: MutableMap<ContextType, Int> = mutableMapOf(ContextType.INITIALIZE to this.defaultMaxMarkers, ContextType.ERROR_BOUNDARY to this.defaultMaxMarkers, ContextType.EVENT_LOGGING to 0, ContextType.CONFIG_SYNC to 0)
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
        if (this.maxMarkers[context] ?: this.defaultMaxMarkers < this.markers[context]?.size ?: 0) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.START, timestamp = System.nanoTime() / NANO_IN_MS, step = step)
        when (context) {
            ContextType.INITIALIZE -> {
                if (key == KeyType.INITIALIZE && step == StepType.NETWORK_REQUEST) {
                    marker.attempt = additionalMarker?.attempt
                }
            }
            ContextType.ERROR_BOUNDARY -> {
                marker.markerID = additionalMarker?.markerID
            }
        }
        return this.addMarker(marker, overrideContext)
    }

    fun markEnd(key: KeyType, success: Boolean, step: StepType? = null, additionalMarker: Marker? = null, overrideContext: ContextType? = null): Boolean {
        if (this.isDisabled) {
            return false
        }
        val context = overrideContext ?: this.diagnosticsContext
        if (this.maxMarkers[context] ?: this.defaultMaxMarkers < this.markers[context]?.size ?: 0) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.END, timestamp = System.nanoTime() / NANO_IN_MS, success = success, step = step)
        when (context) {
            ContextType.INITIALIZE -> {
                if (key == KeyType.INITIALIZE && step == StepType.NETWORK_REQUEST) {
                    marker.attempt = additionalMarker?.attempt!!
                    marker.retryLimit = additionalMarker?.retryLimit!!
                    marker.sdkRegion = additionalMarker.sdkRegion
                    marker.statusCode = additionalMarker.statusCode
                }
            }
            ContextType.ERROR_BOUNDARY -> {
                marker.markerID = additionalMarker?.markerID!!
                marker.configName = additionalMarker?.configName!!
            }
        }
        return this.addMarker(marker, overrideContext)
    }

    private fun addMarker(marker: Marker, overrideContext: ContextType? = null): Boolean {
        val context = overrideContext ?: this.diagnosticsContext
        if (this.maxMarkers[context] ?: this.defaultMaxMarkers <= this.markers[context]?.size ?: 0) {
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
