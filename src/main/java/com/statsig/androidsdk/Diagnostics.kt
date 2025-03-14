package com.statsig.androidsdk

import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

    private var maxMarkers: MutableMap<ContextType, Int> = mutableMapOf(ContextType.INITIALIZE to this.defaultMaxMarkers, ContextType.API_CALL to this.defaultMaxMarkers, ContextType.EVENT_LOGGING to 0, ContextType.CONFIG_SYNC to 0, ContextType.UPDATE_USER to this.defaultMaxMarkers)

    private var markers = ConcurrentHashMap<ContextType, ConcurrentLinkedQueue<Marker>>()

    fun getMarkers(context: ContextType? = null): Queue<Marker> {
        return this.markers[context ?: this.diagnosticsContext] ?: ConcurrentLinkedQueue()
    }

    fun setMaxMarkers(context: ContextType, maxMarkers: Int) {
        this.maxMarkers[context] = maxMarkers
    }

    fun clearContext(context: ContextType? = null) {
        this.markers.put(context ?: this.diagnosticsContext, ConcurrentLinkedQueue())
    }

    fun markStart(key: KeyType, step: StepType? = null, additionalMarker: Marker? = null, overrideContext: ContextType? = null): Boolean {
        val context = overrideContext ?: this.diagnosticsContext
        if (this.isDisabled && context === ContextType.API_CALL) {
            return false
        }
        if (this.getMaxMarkers(context) < (this.markers[context]?.size ?: 0)
        ) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.START, timestamp = System.nanoTime() / NANO_IN_MS, step = step)
        when (context) {
            ContextType.INITIALIZE, ContextType.UPDATE_USER -> {
                if (key == KeyType.INITIALIZE && step == StepType.NETWORK_REQUEST) {
                    marker.attempt = additionalMarker?.attempt
                }
            }
            ContextType.API_CALL -> {
                marker.markerID = additionalMarker?.markerID
            }

            else -> return false
        }

        return this.addMarker(marker, context)
    }

    fun markEnd(key: KeyType, success: Boolean, step: StepType? = null, additionalMarker: Marker? = null, overrideContext: ContextType? = null): Boolean {
        val context = overrideContext ?: this.diagnosticsContext
        if (this.isDisabled && context === ContextType.API_CALL) {
            return false
        }
        if (this.getMaxMarkers(context) < (this.markers[context]?.size ?: 0)) {
            return false
        }
        val marker = Marker(key = key, action = ActionType.END, timestamp = System.nanoTime() / NANO_IN_MS, success = success, step = step)
        when (context) {
            ContextType.INITIALIZE, ContextType.UPDATE_USER -> {
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
        if (step == StepType.NETWORK_REQUEST) {
            marker.hasNetwork = additionalMarker?.hasNetwork
        }
        return this.addMarker(marker, context)
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
            this.markers[context] = ConcurrentLinkedQueue()
        }
        this.markers[context]?.add(marker)
        this.markers.values
        return true
    }
}
