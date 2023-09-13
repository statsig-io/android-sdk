package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit


// Constants
private val MAX_LOG_PERIOD = TimeUnit.DAYS.toMillis(3)
private const val POLLING_INTERVAL_MS: Long = 10000

// JSON keys
private const val USER = "user"
private const val STATSIG_METADATA = "statsigMetadata"
private const val LAST_SYNC_TIME_FOR_USER = "lastSyncTimeForUser"
private const val SINCE_TIME = "sinceTime"
private const val HASH = "hash"

// SharedPref keys
private const val OFFLINE_LOGS_KEY: String = "StatsigNetwork.OFFLINE_LOGS"

// Endpoints
internal const val LOGGING_ENDPOINT: String = "log_event"
internal const val INITIALIZE_ENDPOINT: String = "initialize"

internal data class InitializeRequestBody(
    @SerializedName("user") val user: StatsigUser?,
    @SerializedName("statsigMetadata") val statsigMetadata: StatsigMetadata,
    @SerializedName("sinceTime") val sinceTime: Long?,
    @SerializedName("hash") val hash: HashAlgorithm,
)

internal class StatsigNetwork(
    sdkKey: String,
) {
    private val gson = StatsigUtil.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private var sharedPrefs: SharedPreferences? = null
    private val httpClient: OkHttpClient

    init {
        val clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(RequestHeaderInterceptor(sdkKey))
        clientBuilder.addInterceptor(ResponseInterceptor())

        httpClient = clientBuilder.build()
    }

    suspend fun initialize(
        api: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        initTimeoutMs: Long,
        sharedPrefs: SharedPreferences,
        diagnostics: Diagnostics? = null,
        hashUsed: HashAlgorithm,
    ): InitializeResponse {
        this.sharedPrefs = sharedPrefs
        if (initTimeoutMs == 0L) {
            return initializeImpl(api, user, sinceTime, metadata, diagnostics, hashUsed = hashUsed)
        }
        return withTimeout(initTimeoutMs) {
            initializeImpl(api, user, sinceTime, metadata, diagnostics, initTimeoutMs, hashUsed = hashUsed)
        }
    }

    private suspend fun initializeImpl(
        api: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        diagnostics: Diagnostics?,
        timeoutMs: Long? = null,
        hashUsed: HashAlgorithm,
    ): InitializeResponse {
        return try {
            val userCopy = user?.getCopyForEvaluation()
            val metadataCopy = metadata.copy()
            val body = InitializeRequestBody(userCopy, metadataCopy, sinceTime, hashUsed)
            var statusCode: Int? = null
            val response = postRequest<InitializeResponse.SuccessfulInitializeResponse>(api, INITIALIZE_ENDPOINT, gson.toJson(body), ContextType.INITIALIZE, diagnostics, timeoutMs) { status: Int? -> statusCode = status }
            response ?: InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkError, null, statusCode)
        } catch (e: Exception) {
            Statsig.errorBoundary.logException(e)
            diagnostics?.markEnd(KeyType.INITIALIZE, false, StepType.NETWORK_REQUEST, Marker(attempt = 1, sdkRegion = null, statusCode = null))
            when (e) {
                is SocketTimeoutException, is ConnectException -> {
                    return InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkTimeout, e)
                }
                is TimeoutCancellationException -> {
                    return InitializeResponse.FailedInitializeResponse(InitializeFailReason.CoroutineTimeout, e)
                }
                else -> {
                    return InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, e)
                }
            }
        }
    }

    fun pollForChanges(
        api: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?> {
        @Suppress("RemoveExplicitTypeArguments") // This is needed for tests
        return flow<InitializeResponse.SuccessfulInitializeResponse?> {
            val userCopy = user?.getCopyForEvaluation()
            val metadataCopy = metadata.copy()
            while (true) {
                delay(POLLING_INTERVAL_MS) // If coroutine is cancelled, this delay will exit the while loop
                val body = mapOf(
                    USER to userCopy,
                    STATSIG_METADATA to metadataCopy,
                    LAST_SYNC_TIME_FOR_USER to sinceTime,
                    SINCE_TIME to sinceTime,
                    HASH to HashAlgorithm.DJB2.value,
                )
                try {
                    emit(postRequest(api, INITIALIZE_ENDPOINT, gson.toJson(body), ContextType.CONFIG_SYNC))
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun apiPostLogs(api: String, bodyString: String) {
        try {
            postRequest<LogEventResponse>(api, LOGGING_ENDPOINT, bodyString, ContextType.EVENT_LOGGING)
        } catch (_: Exception) {}
    }

    suspend fun apiRetryFailedLogs(api: String) {
        val savedLogs = getSavedLogs()
        if (savedLogs.isEmpty()) {
            return
        }
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY)
        savedLogs.map { apiPostLogs(api, it.requestBody) }
    }

    suspend fun addFailedLogRequest(requestBody: String) {
        withContext(dispatcherProvider.io) {
            val savedLogs = getSavedLogs() + StatsigOfflineRequest(System.currentTimeMillis(), requestBody)
            try {
                // savedLogs wont be concurrently modified as it is read from storage and only used here
                StatsigUtil.saveStringToSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY, gson.toJson(StatsigPendingRequests(savedLogs)))
            } catch (_: Exception) {
                StatsigUtil.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY)
            }
        }
    }

    private suspend fun getSavedLogs(): List<StatsigOfflineRequest> {
        return withContext(dispatcherProvider.io) {
            val json: String = StatsigUtil.getFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY) ?: return@withContext arrayListOf()

            return@withContext try {
                val pendingRequests = gson.fromJson(json, StatsigPendingRequests::class.java)
                if (pendingRequests?.requests == null) {
                    return@withContext arrayListOf()
                }
                val currentTime = System.currentTimeMillis()
                pendingRequests.requests.filter {
                    it.timestamp > currentTime - MAX_LOG_PERIOD
                }
            } catch (_: Exception) {
                return@withContext arrayListOf()
            }
        }
    }

    // Bug with Kotlin where any function that throws an IOException still triggers this lint warning
    // https://youtrack.jetbrains.com/issue/KTIJ-838
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend inline fun <reified T : Any> postRequest(
        api: String,
        endpoint: String,
        bodyString: String,
        contextType: ContextType,
        diagnostics: Diagnostics? = null,
        timeout: Long? = null,
        crossinline callback: ((statusCode: Int?) -> Unit) = { _: Int? -> },
    ): T? {
        return withContext(dispatcherProvider.io) { // Perform network calls in IO thread
            val url = if (api.endsWith("/")) "$api$endpoint" else "$api/$endpoint"
            diagnostics?.markStart(KeyType.INITIALIZE, StepType.NETWORK_REQUEST, Marker(attempt = 1), contextType)
            try {
                var client = httpClient
                val requestBody: RequestBody = bodyString.toRequestBody(JSON)
                val request: Request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                if (timeout != null) {
                    client = httpClient.newBuilder()
                        .callTimeout(timeout, TimeUnit.MILLISECONDS)
                        .build()
                }
                var response = client.newCall(request).execute()
                var code = response.code
                endDiagnostics(diagnostics, contextType, code,
                    response.headers["x-statsig-region"], response.headers["attempt"]?.toInt())
                when (code) {
                    in 200..299 -> {
                        if (code == 204 && endpoint == INITIALIZE_ENDPOINT) {
                            return@withContext gson.fromJson("{has_updates: false}", T::class.java)
                        }
                        return@withContext gson.fromJson(response.body?.string(), T::class.java)
                    }
                    else -> {
                        if (endpoint == LOGGING_ENDPOINT) {
                            addFailedLogRequest(bodyString)
                        }
                        callback(code)
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                if (endpoint == LOGGING_ENDPOINT) {
                    addFailedLogRequest(bodyString)
                }
                throw e
            }
        }
    }

    private fun endDiagnostics(diagnostics: Diagnostics?, diagnosticsContext: ContextType, statusCode: Int, sdkRegion: String?, attempt: Int?) {
        if (diagnostics == null) {
            return
        }

        val marker = Marker(attempt = attempt, sdkRegion = sdkRegion, statusCode = statusCode)
        val wasSuccessful = statusCode in 200..299
        diagnostics.markEnd(KeyType.INITIALIZE, wasSuccessful, StepType.NETWORK_REQUEST, marker, overrideContext = diagnosticsContext)
    }
}
