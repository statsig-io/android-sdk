package com.statsig.androidsdk

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.DataOutputStream
import java.lang.RuntimeException
import java.net.HttpURLConnection
import java.net.URL

internal class ErrorBoundary() {
    internal var urlString = "https://statsigapi.net/v1/sdk_exception"

    private var apiKey: String? = null
    private var statsigMetadata: StatsigMetadata? = null
    private var seen = HashSet<String>()

    fun setKey(apiKey: String) {
        this.apiKey = apiKey
    }

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    private fun handleException(exception: Throwable) {
        println("[Statsig]: An unexpected exception occurred.")
        println(exception)
        this.logException(exception)
    }

    fun getExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, exception ->
            this.handleException(exception)
        }
    }

    fun capture(task: () -> Unit, recover: (() -> Unit)? = null) {
        try {
            task()
        } catch (e: Exception) {
            handleException(e)
            recover?.let { it() }
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
            if (apiKey == null) {
                return
            }

            val name = exception.javaClass.canonicalName ?: exception.javaClass.name
            if (seen.contains(name)) {
                return
            }

            seen.add(name)

            val metadata = statsigMetadata ?: StatsigMetadata("")
            val url = URL(urlString)
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
            conn.setRequestProperty("Content-Length", postData.length.toString())
            conn.setRequestProperty("STATSIG-API-KEY", apiKey)
            conn.useCaches = false

            DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
            conn.responseCode // triggers request
        } catch (e: Exception) {
        }
    }
}
