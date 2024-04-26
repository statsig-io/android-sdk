package com.statsig.androidsdk

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.lang.RuntimeException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

const val MAX_DIAGNOSTICS_MARKERS = 30
const val SAMPLING_RATE = 10_000
internal class ExternalException(message: String? = null) : Exception(message)

internal class ErrorBoundary() {
    internal var urlString = "https://statsigapi.net/v1/sdk_exception"

    private var apiKey: String? = null
    private var statsigMetadata: StatsigMetadata? = null
    private var seen = HashSet<String>()
    private var diagnostics: Diagnostics? = null

    fun setKey(apiKey: String) {
        this.apiKey = apiKey
    }

    fun getUrl(): String {
        return urlString
    }

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
        val sampled = floor(Math.random() * SAMPLING_RATE) == 0.0
        if (sampled) {
            diagnostics.setMaxMarkers(
                ContextType.API_CALL,
                MAX_DIAGNOSTICS_MARKERS,
            )
        } else {
            diagnostics.setMaxMarkers(
                ContextType.API_CALL,
                0,
            )
        }
    }

    private fun handleException(exception: Throwable) {
        println("[Statsig]: An unexpected exception occurred.")
        println(exception)
        if (exception !is ExternalException) {
            this.logException(exception)
        }
    }

    fun getExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, exception ->
            this.handleException(exception)
        }
    }

    fun getNoopExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, _ ->
            // No-op
        }
    }

    fun capture(task: () -> Unit, tag: String? = null, recover: ((exception: Exception?) -> Unit)? = null, configName: String? = null) {
        var markerID = ""
        try {
            markerID = startMarker(tag, configName) ?: ""
            task()
            endMarker(tag, markerID, true, configName)
        } catch (e: Exception) {
            endMarker(tag, markerID, false, configName)
            handleException(e)
            recover?.let { it(e) }
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T): T? {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e)
            null
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T, recover: (suspend (e: Exception) -> T)): T {
        return try {
            task()
        } catch (e: Exception) {
            handleException(e)
            recover(e)
        }
    }

    internal fun logException(exception: Throwable) {
        try {
            CoroutineScope(this.getNoopExceptionHandler() + Dispatchers.IO).launch {
                if (apiKey == null) {
                    return@launch
                }

                val name = exception.javaClass.canonicalName ?: exception.javaClass.name
                if (seen.contains(name)) {
                    return@launch
                }

                seen.add(name)

                val metadata = statsigMetadata ?: StatsigMetadata("")
                val url = URL(getUrl())
                val body = mapOf(
                    "exception" to name,
                    "info" to RuntimeException(exception).stackTraceToString(),
                    "statsigMetadata" to metadata,
                )
                val postData = Gson().toJson(body)

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("STATSIG-API-KEY", apiKey)
                conn.useCaches = false
                DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
                conn.responseCode // triggers request
            }
        } catch (e: Exception) {
            // noop
        }
    }

    private fun startMarker(tag: String?, configName: String?): String? {
        val diagnostics = this.diagnostics
        val markerKey = KeyType.convertFromString(tag ?: "")
        if (tag == null || diagnostics == null || markerKey == null) {
            return null
        }
        val markerID = tag + "_" + diagnostics.getMarkers(ContextType.API_CALL).count()
        diagnostics.diagnosticsContext = ContextType.API_CALL
        diagnostics.markStart(markerKey, step = null, Marker(markerID = markerID, configName = configName))
        return markerID
    }

    private fun endMarker(tag: String?, markerID: String?, success: Boolean, configName: String?) {
        val diagnostics = this.diagnostics
        val markerKey = KeyType.convertFromString(tag ?: "")
        if (tag == null || diagnostics == null || markerKey == null) {
            return
        }
        diagnostics.markEnd(markerKey, success, step = null, Marker(markerID = markerID, configName = configName))
    }
}
