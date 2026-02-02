package com.statsig.androidsdk

import android.util.Log
import com.google.gson.Gson
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

internal class ExternalException(message: String? = null) : Exception(message)

internal class ErrorBoundary(
    private val coroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + CoroutineDispatcherProvider().io
    )
) {
    private companion object {
        private const val TAG: String = "statsig::ErrorBoundary"
    }
    internal var urlString = "https://prodregistryv2.org/v1/rgstr_e"

    private var apiKey: String? = null
    private var statsigMetadata: StatsigMetadata? = null
    private var seen = HashSet<String>()

    fun initialize(apiKey: String) {
        this.apiKey = apiKey
    }

    fun getUrl(): String = urlString

    fun setMetadata(statsigMetadata: StatsigMetadata) {
        this.statsigMetadata = statsigMetadata
    }

    private fun handleException(
        exception: Throwable,
        tag: String? = null,
        configName: String? = null
    ) {
        Log.e(TAG, "An unexpected exception occurred.", exception)
        if (exception !is ExternalException) {
            this.logException(exception, tag, configName)
        }
    }

    fun getExceptionHandler(): CoroutineExceptionHandler = CoroutineExceptionHandler {
            _,
            exception
        ->
        this.handleException(exception)
    }

    fun getNoopExceptionHandler(): CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ ->
        // No-op
    }

    fun capture(
        task: () -> Unit,
        tag: String? = null,
        recover: ((exception: Exception?) -> Unit)? = null,
        configName: String? = null
    ) {
        try {
            task()
        } catch (e: Exception) {
            handleException(e, tag, configName)
            recover?.let { it(e) }
        }
    }

    suspend fun <T> captureAsync(task: suspend () -> T): T? = try {
        task()
    } catch (e: Exception) {
        handleException(e)
        null
    }

    suspend fun <T> captureAsync(task: suspend () -> T, recover: (suspend (e: Exception) -> T)): T =
        try {
            task()
        } catch (e: Exception) {
            handleException(e)
            recover(e)
        }

    internal fun logException(
        exception: Throwable,
        tag: String? = null,
        configName: String? = null
    ) {
        try {
            this.coroutineScope.launch(this.getNoopExceptionHandler()) {
                if (apiKey == null) {
                    return@launch
                }
                val scopedApi = apiKey ?: ""

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
                    "configName" to configName
                )
                val postData = Gson().toJson(body)
                val httpClient = HttpUtils.getHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .post(postData.toJsonRequestBody())
                    .addStatsigHeaders(scopedApi)
                    .build()

                httpClient.newCall(request).enqueue(
                    responseCallback = object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            // no-op
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.close()
                        }
                    }
                )
            }
        } catch (e: Exception) {
            // noop
        }
    }
}
