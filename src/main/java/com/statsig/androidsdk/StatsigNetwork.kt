package com.statsig.androidsdk

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.statsig.androidsdk.HttpUtils.Companion.RETRY_CODES
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_EVENT_COUNT
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_STABLE_ID_HEADER_KEY
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Request

// Constants
private val MAX_LOG_PERIOD = TimeUnit.DAYS.toMillis(3)
private const val MIN_POLLING_INTERVAL_MS: Long = 60000 // 1 minute in milliseconds
private const val MAX_INITIALIZE_REQUESTS: Int = 10
private const val LOG_EVENT_RETRY: Int = 2
private const val MAX_LOG_REQUESTS_TO_CACHE: Int = 10
private const val MAX_LOG_RETRIES: Int = 3

private const val INITIALIZE_RETRY_BACKOFF = 100L
private const val INITIALIZE_RETRY_BACKOFF_MULTIPLIER = 5

// JSON keys
private const val USER = "user"
private const val STATSIG_METADATA = "statsigMetadata"
private const val LAST_SYNC_TIME_FOR_USER = "lastSyncTimeForUser"
private const val SINCE_TIME = "sinceTime"
private const val HASH = "hash"
private const val PREVIOUS_DERIVED_FIELDS = "previousDerivedFields"
private const val FULL_CHECKSUM = "full_checksum"

// SharedPref keys
private const val OFFLINE_LOGS_KEY_V1: String = "StatsigNetwork.OFFLINE_LOGS"
private const val OFFLINE_LOGS_STORE_NAME: String = "offlinelogs"

internal interface StatsigNetwork {

    suspend fun initialize(
        api: String,
        user: StatsigUser,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        coroutineScope: CoroutineScope,
        contextType: ContextType,
        diagnostics: Diagnostics? = null,
        hashUsed: HashAlgorithm,
        previousDerivedFields: Map<String, String>,
        fullChecksum: String?
    ): InitializeResponse?

    fun pollForChanges(
        api: String,
        user: StatsigUser,
        metadata: StatsigMetadata,
        updateIntervalMs: Long = MIN_POLLING_INTERVAL_MS,
        fallbackUrls: List<String>? = null
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?>

    suspend fun apiPostLogs(
        api: String,
        bodyString: String,
        eventsCount: String? = null,
        fallbackUrls: List<String>? = null
    )

    suspend fun apiRetryFailedLogs(api: String, fallbackUrls: List<String>? = null)

    suspend fun addFailedLogRequest(request: StatsigOfflineRequest)

    suspend fun getSavedLogs(): List<StatsigOfflineRequest>

    fun filterValidLogs(
        all: List<StatsigOfflineRequest>,
        currentTime: Long
    ): List<StatsigOfflineRequest>
}

internal fun StatsigNetwork(
    context: Context,
    sdkKey: String,
    keyValueStorage: KeyValueStorage<String>,
    options: StatsigOptions,
    networkFallbackResolver: NetworkFallbackResolver,
    coroutineScope: CoroutineScope,
    store: Store,
    gson: Gson
): StatsigNetwork = StatsigNetworkImpl(
    context,
    sdkKey,
    keyValueStorage,
    options,
    networkFallbackResolver,
    coroutineScope,
    store,
    gson
)

internal class StatsigNetworkImpl(
    context: Context,
    private val sdkKey: String,
    private val keyValueStorage: KeyValueStorage<String>,
    private val options: StatsigOptions,
    private val networkResolver: NetworkFallbackResolver,
    private val coroutineScope: CoroutineScope,
    private val store: Store,
    private val gson: Gson
) : StatsigNetwork {

    private companion object {
        private const val TAG = "statsig::StatsigNetwork"
    }

    private val dispatcherProvider = CoroutineDispatcherProvider()
    private val connectivityListener = StatsigNetworkConnectivityListener(context)
    private val offlineLogsKeyV2 = "$OFFLINE_LOGS_KEY_V1:$sdkKey"
    private var initializeRequestsMap = Collections.synchronizedMap(
        mutableMapOf<String, Call>()
    )

    private val gzipInterceptor = GZipRequestInterceptor()

    override suspend fun initialize(
        api: String,
        user: StatsigUser,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        coroutineScope: CoroutineScope,
        contextType: ContextType,
        diagnostics: Diagnostics?,
        hashUsed: HashAlgorithm,
        previousDerivedFields: Map<String, String>,
        fullChecksum: String?
    ): InitializeResponse {
        val retry = options.initRetryLimit
        networkResolver.initializeFallbackInfo()
        if (options.initTimeoutMs == 0L) {
            return initializeImplWithRetry(
                api,
                user,
                sinceTime,
                metadata,
                contextType,
                diagnostics,
                retryLimit = retry,
                hashUsed = hashUsed,
                previousDerivedFields = previousDerivedFields,
                fallbackUrls = options.initializeFallbackUrls,
                fullChecksum = fullChecksum
            )
        }
        return withTimeout(options.initTimeoutMs) {
            var response: InitializeResponse = InitializeResponse.FailedInitializeResponse(
                InitializeFailReason.InternalError,
                null,
                null
            )
            coroutineScope.launch(dispatcherProvider.io) {
                response = initializeImplWithRetry(
                    api,
                    user,
                    sinceTime,
                    metadata,
                    contextType,
                    diagnostics,
                    options.initTimeoutMs.toInt(),
                    retryLimit = retry,
                    hashUsed = hashUsed,
                    previousDerivedFields = previousDerivedFields,
                    fallbackUrls = options.initializeFallbackUrls,
                    fullChecksum = fullChecksum
                )
            }.join()

            return@withTimeout response
        }
    }

    private suspend fun initializeImplWithRetry(
        api: String,
        user: StatsigUser,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        contextType: ContextType,
        diagnostics: Diagnostics?,
        timeoutMs: Int? = null,
        retryLimit: Int = 0,
        hashUsed: HashAlgorithm,
        previousDerivedFields: Map<String, String>,
        fullChecksum: String?,
        fallbackUrls: List<String>? = null
    ): InitializeResponse {
        var attempt = 0
        var response: InitializeResponse
        var backoff = INITIALIZE_RETRY_BACKOFF

        do {
            response = initializeImpl(
                api,
                user,
                sinceTime,
                metadata,
                contextType,
                diagnostics,
                attempt + 1,
                timeoutMs,
                hashUsed,
                previousDerivedFields,
                fullChecksum,
                fallbackUrls
            )

            val code = (response as? InitializeResponse.FailedInitializeResponse)?.statusCode ?: 0
            val shouldRetry = code == 0 || RETRY_CODES.contains(code)

            if (response is InitializeResponse.SuccessfulInitializeResponse || !shouldRetry) {
                return response
            }

            attempt++
            delay(backoff)
            backoff *= INITIALIZE_RETRY_BACKOFF_MULTIPLIER
        } while (attempt <= retryLimit)

        return response
    }

    internal suspend fun initializeImpl(
        api: String,
        user: StatsigUser,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        contextType: ContextType,
        diagnostics: Diagnostics?,
        retries: Int,
        timeoutMs: Int? = null,
        hashUsed: HashAlgorithm,
        previousDerivedFields: Map<String, String>,
        fullChecksum: String?,
        fallbackUrls: List<String>? = null
    ): InitializeResponse {
        return try {
            val userCopy = user.getCopyForEvaluation()
            val userCacheKey = this.options.customCacheKey(sdkKey, userCopy)
            val metadataCopy = metadata.copy()
            val body = mapOf(
                USER to userCopy,
                STATSIG_METADATA to metadataCopy,
                SINCE_TIME to sinceTime,
                HASH to hashUsed,
                PREVIOUS_DERIVED_FIELDS to previousDerivedFields,
                FULL_CHECKSUM to fullChecksum
            )
            var statusCode: Int? = null
            initializeRequestsMap[userCacheKey]?.cancel()
            initializeRequestsMap.remove(userCacheKey)
            val response = postRequest<InitializeResponse.SuccessfulInitializeResponse>(
                UrlConfig(Endpoint.Initialize, api, fallbackUrls),
                gson.toJson(body),
                retries,
                contextType,
                diagnostics,
                timeoutMs,
                requestCacheKey = userCacheKey,
                stableID = metadataCopy.stableID
            ) { status: Int? -> statusCode = status }
            response ?: InitializeResponse.FailedInitializeResponse(
                InitializeFailReason.NetworkError,
                null,
                statusCode
            )
        } catch (e: Exception) {
            this.endDiagnostics(
                diagnostics,
                contextType,
                KeyType.INITIALIZE,
                null,
                null,
                1,
                Marker.ErrorMessage(e.message.toString(), e.javaClass.name, e.javaClass.name),
                timeoutMs
            )
            when (e) {
                is SocketTimeoutException, is ConnectException -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.NetworkTimeout,
                        e
                    )
                }

                is TimeoutCancellationException -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.CoroutineTimeout,
                        e
                    )
                }

                is IOException -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.NetworkError,
                        e
                    )
                }

                else -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.InternalError,
                        e
                    )
                }
            }
        }
    }

    override fun pollForChanges(
        api: String,
        user: StatsigUser,
        metadata: StatsigMetadata,
        updateIntervalMs: Long,
        fallbackUrls: List<String>?
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?> {
        @Suppress("RemoveExplicitTypeArguments") // This is needed for tests
        return flow<InitializeResponse.SuccessfulInitializeResponse?> {
            val userCopy = user.getCopyForEvaluation()
            val userCacheKey = this@StatsigNetworkImpl.options.customCacheKey(
                this@StatsigNetworkImpl.sdkKey,
                userCopy
            )
            val metadataCopy = metadata.copy()
            val sinceTime = this@StatsigNetworkImpl.store.getLastUpdateTime(user)
            val previousDerivedFields = this@StatsigNetworkImpl.store.getPreviousDerivedFields(user)
            val fullChecksum = this@StatsigNetworkImpl.store.getFullChecksum(user)
            val boundedUpdateIntervalMs = Math.max(updateIntervalMs, MIN_POLLING_INTERVAL_MS)
            while (true) {
                // If coroutine is cancelled, this delay will exit the while loop
                delay(boundedUpdateIntervalMs)
                val body = mapOf(
                    USER to userCopy,
                    STATSIG_METADATA to metadataCopy,
                    LAST_SYNC_TIME_FOR_USER to sinceTime,
                    SINCE_TIME to sinceTime,
                    HASH to HashAlgorithm.DJB2.value,
                    PREVIOUS_DERIVED_FIELDS to previousDerivedFields,
                    FULL_CHECKSUM to fullChecksum
                )
                initializeRequestsMap[userCacheKey]?.cancel()
                initializeRequestsMap.remove(userCacheKey)
                try {
                    emit(
                        postRequest(
                            UrlConfig(Endpoint.Initialize, api, fallbackUrls),
                            gson.toJson(body),
                            0,
                            null,
                            requestCacheKey = this@StatsigNetworkImpl.options.customCacheKey(
                                this@StatsigNetworkImpl.sdkKey,
                                userCopy
                            ),
                            stableID = metadataCopy.stableID
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Init Polling Error", e)
                }
            }
        }
    }

    override suspend fun apiPostLogs(
        api: String,
        bodyString: String,
        eventsCount: String?,
        fallbackUrls: List<String>?
    ) {
        val timestamp = System.currentTimeMillis()
        retryApiPostLogs(
            api,
            StatsigOfflineRequest(timestamp, bodyString, retryCount = 0),
            eventsCount,
            fallbackUrls
        )
    }

    private suspend fun retryApiPostLogs(
        api: String,
        request: StatsigOfflineRequest,
        eventsCount: String?,
        fallbackUrls: List<String>?
    ) {
        var currRetry = 1
        var backoff = 100L
        var statusCode: Int? = null

        try {
            while (currRetry <= LOG_EVENT_RETRY) {
                val response = postRequest<LogEventResponse>(
                    UrlConfig(Endpoint.Rgstr, api, fallbackUrls),
                    request.requestBody,
                    currRetry,
                    null,
                    eventsCount = eventsCount
                ) {
                    statusCode = it
                }
                currRetry++

                if (response?.success == true || statusCode?.let { it in 200..299 } == true) {
                    return
                }

                if (statusCode?.let { RETRY_CODES.contains(it) } == true) {
                    delay(backoff)
                    backoff *= 5L
                } else {
                    if (request.retryCount < MAX_LOG_RETRIES) {
                        addFailedLogRequest(
                            request.copy(retryCount = request.retryCount + 1)
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            if (request.retryCount < MAX_LOG_RETRIES) {
                Log.e(TAG, "Error posting logs, saving for retry...", e)
                addFailedLogRequest(
                    request.copy(retryCount = request.retryCount + 1)
                )
            }
        }
    }

    override suspend fun apiRetryFailedLogs(api: String, fallbackUrls: List<String>?) {
        if (this.options.disableLogEventRetries) {
            return
        }
        val savedLogs = getSavedLogs()
        if (savedLogs.isEmpty()) {
            return
        }
        keyValueStorage.removeValue(OFFLINE_LOGS_STORE_NAME, OFFLINE_LOGS_KEY_V1)
        keyValueStorage.removeValue(OFFLINE_LOGS_STORE_NAME, offlineLogsKeyV2)

        val eventsCount = savedLogs.size.toString()
        savedLogs.map { retryApiPostLogs(api, it, eventsCount, fallbackUrls) }
    }

    override suspend fun addFailedLogRequest(request: StatsigOfflineRequest) {
        if (request.retryCount >= MAX_LOG_RETRIES) {
            return
        }

        withContext(dispatcherProvider.io) {
            val savedLogs = getSavedLogs().toMutableList()
            savedLogs.add(request)

            val limitedLogs = filterValidLogs(savedLogs)

            try {
                keyValueStorage.writeValue(
                    OFFLINE_LOGS_STORE_NAME,
                    offlineLogsKeyV2,
                    gson.toJson(StatsigPendingRequests(limitedLogs))
                )
            } catch (_: Exception) {
                keyValueStorage.removeValue(OFFLINE_LOGS_STORE_NAME, offlineLogsKeyV2)
            }
        }
    }

    override suspend fun getSavedLogs(): List<StatsigOfflineRequest> {
        return withContext(dispatcherProvider.io) {
            val json: String = keyValueStorage.readValue(OFFLINE_LOGS_STORE_NAME, offlineLogsKeyV2)
                ?: keyValueStorage.readValue(OFFLINE_LOGS_STORE_NAME, OFFLINE_LOGS_KEY_V1)
                ?: return@withContext arrayListOf()
            return@withContext try {
                val pendingRequests = gson.fromJson(json, StatsigPendingRequests::class.java)
                if (pendingRequests?.requests == null) {
                    emptyList()
                } else {
                    filterValidLogs(pendingRequests.requests)
                }
            } catch (_: Exception) {
                return@withContext arrayListOf()
            }
        }
    }

    override fun filterValidLogs(
        all: List<StatsigOfflineRequest>,
        currentTime: Long
    ): List<StatsigOfflineRequest> {
        return all
            .filter { it.timestamp > currentTime - MAX_LOG_PERIOD } // remove old logs
            .filter { it.retryCount < MAX_LOG_RETRIES } // remove over-retried logs
            .sortedBy { it.timestamp } // ensure logs are sorted by time
            .takeLast(MAX_LOG_REQUESTS_TO_CACHE) // keep most recent
    }

    fun filterValidLogs(all: List<StatsigOfflineRequest>): List<StatsigOfflineRequest> =
        filterValidLogs(all, System.currentTimeMillis())

    // Bug with Kotlin where any function that throws an IOException still triggers this lint warning
    // https://youtrack.jetbrains.com/issue/KTIJ-838
    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend inline fun <reified T : Any> postRequest(
        urlConfig: UrlConfig,
        bodyString: String,
        retries: Int, // for logging purpose
        contextType: ContextType? = null,
        diagnostics: Diagnostics? = null,
        timeout: Int? = null,
        eventsCount: String? = null,
        requestCacheKey: String? = null,
        stableID: String? = null,
        crossinline callback: ((statusCode: Int?) -> Unit) = { _: Int? -> }
    ): T? {
        return withContext(dispatcherProvider.io) {
            // Perform network calls in IO thread
            var errorMessage: String? = null
            val start = System.nanoTime()
            var end: Long
            var call: Call? = null
            try {
                urlConfig.fallbackUrl = networkResolver.getActiveFallbackUrlFromMemory(urlConfig)
                val url = URL(urlConfig.fallbackUrl ?: urlConfig.getUrl())

                val clientBuilder = HttpUtils.getHttpClient().newBuilder()

                if (timeout != null) {
                    clientBuilder.callTimeout(timeout.milliseconds.toJavaDuration())
                }

                if (shouldCompressLogEvent(urlConfig, url.toString())) {
                    clientBuilder.addInterceptor(gzipInterceptor)
                }

                val client = clientBuilder.build()

                val body = bodyString.toJsonRequestBody()
                val requestBuilder = Request.Builder().url(url).post(body).addStatsigHeaders(sdkKey)

                if (eventsCount != null) {
                    requestBuilder.addHeader(STATSIG_EVENT_COUNT, eventsCount)
                }

                if (stableID != null) {
                    requestBuilder.addHeader(STATSIG_STABLE_ID_HEADER_KEY, stableID)
                }

                call = client.newCall(requestBuilder.build())

                if (requestCacheKey != null && urlConfig.endpoint != Endpoint.Rgstr) {
                    if (initializeRequestsMap.size > MAX_INITIALIZE_REQUESTS) {
                        initializeRequestsMap.values.forEach { it.cancel() }
                        initializeRequestsMap = Collections.synchronizedMap(mutableMapOf())
                    }
                    initializeRequestsMap[requestCacheKey] = call
                }

                if (contextType != null) {
                    diagnostics?.markStart(
                        KeyType.INITIALIZE,
                        StepType.NETWORK_REQUEST,
                        Marker(attempt = retries),
                        contextType
                    )
                }

                // TODO: Should likely be call.executeAsync() after updating to OkHttp 5
                //  Alternatively, could convert to enqueue() w/ a callback
                val response = call.execute()
                val code = response.code

                val errorMarker = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    errorMessage = response.body?.string()
                    Marker.ErrorMessage(errorMessage, code.toString(), null)
                } else {
                    null
                }

                val region = response.header("x-statsig-region")

                endDiagnostics(
                    diagnostics,
                    contextType,
                    KeyType.INITIALIZE,
                    code,
                    region,
                    retries,
                    errorMarker,
                    timeout
                )
                callback(code)
                when (code) {
                    in 200..299 -> {
                        networkResolver.tryBumpExpiryTime(urlConfig)
                        if (code == 204 && urlConfig.endpoint == Endpoint.Initialize) {
                            return@withContext gson.fromJson(
                                "{has_updates: false}",
                                T::class.java
                            )
                        }
                        val stream = response.body!!.byteStream()
                        return@withContext stream.bufferedReader(Charsets.UTF_8)
                            .use { gson.fromJson(it, T::class.java) }
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message
                throw e
            } finally {
                // Ensure call is either closed or cancelled.
                if (call != null && call.isExecuted()) {
                    call.cancel()
                }
                end = System.nanoTime()
                coroutineScope.launch(dispatcherProvider.io) {
                    val timedOut = (end - start) / 1_000_000_000 > (timeout ?: 0)
                    val fallbackUpdated = networkResolver.tryFetchUpdatedFallbackInfo(
                        urlConfig,
                        errorMessage,
                        timedOut,
                        connectivityListener.isNetworkAvailable()
                    )
                    if (fallbackUpdated) {
                        Log.i(TAG, "Updated fallback URL,")
                        urlConfig.fallbackUrl =
                            networkResolver.getActiveFallbackUrlFromMemory(urlConfig)
                    }
                }
            }
            return@withContext null
        }
    }

    internal fun shouldCompressLogEvent(config: UrlConfig, url: String): Boolean {
        if (config.endpoint !== Endpoint.Rgstr) {
            return false
        }
        if (options.disableLoggingCompression) {
            return false
        }
        if (url.startsWith(DEFAULT_EVENT_API)) {
            return true
        }
        if (url == config.customUrl || config.userFallbackUrls?.contains(url) == true) {
            return store.getSDKFlags()?.get("enable_log_event_compression") == true
        }
        return false
    }

    private fun endDiagnostics(
        diagnostics: Diagnostics?,
        diagnosticsContext: ContextType? = null,
        keyType: KeyType,
        statusCode: Int?,
        sdkRegion: String?,
        attempt: Int?,
        error: Marker.ErrorMessage? = null,
        timeoutMs: Int? = null
    ) {
        if (diagnostics == null || diagnosticsContext == null) {
            return
        }
        val marker =
            Marker(
                attempt = attempt,
                sdkRegion = sdkRegion,
                statusCode = statusCode,
                error = error,
                hasNetwork = connectivityListener.isNetworkAvailable(),
                timeoutMS = timeoutMs
            )
        val wasSuccessful = statusCode in 200..299

        diagnostics.markEnd(
            keyType,
            wasSuccessful,
            StepType.NETWORK_REQUEST,
            marker,
            overrideContext = diagnosticsContext
        )
    }
}
