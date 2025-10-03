package com.statsig.androidsdk

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.lang.RuntimeException
import java.net.HttpURLConnection
import java.net.URL

internal class ExternalException(message: String? = null) : Exception(message)

internal class ErrorBoundary(private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    companion object {
        private const val TAG: String = "statsig::ErrorBoundary"
    }
    internal var urlString = "https://prodregistryv2.org/v1/rgstr_e"

    private var apiKey: String? = null
    private var statsigMetadata: StatsigMetadata? = null
    private var seen = HashSet<String>()

    private lateinit var urlConnectionProvider: UrlConnectionProvider

    fun initialize(apiKey: String, urlConnectionProvider: UrlConnectionProvider = defaultProvider) {
        this.apiKey = apiKey
        this.urlConnectionProvider = urlConnectionProvider
    }

    fun getUrl(): String {
        return urlString
    }

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    private fun handleException(exception: Throwable) {
        Log.e(TAG, "An unexpected exception occurred.", exception)
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
        try {
            task()
        } catch (e: Exception) {
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

    internal fun logException(exception: Throwable, tag: String? = null) {
        try {
            this.coroutineScope.launch(this.getNoopExceptionHandler()) {
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
                    "tag" to (tag ?: "unknown"),
                )
                val postData = Gson().toJson(body)

                val conn = urlConnectionProvider.open(url) as HttpURLConnection
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
}
