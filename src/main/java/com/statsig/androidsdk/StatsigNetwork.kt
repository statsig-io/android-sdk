package com.statsig.androidsdk

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private val RETRY_CODES: IntArray = intArrayOf(
    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
    HttpURLConnection.HTTP_INTERNAL_ERROR,
    HttpURLConnection.HTTP_BAD_GATEWAY,
    HttpURLConnection.HTTP_UNAVAILABLE,
    HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
    522,
    524,
    599,
)

// Constants
private val MAX_LOG_PERIOD = TimeUnit.DAYS.toMillis(3)
private const val POLLING_INTERVAL_MS: Long = 10000
private const val MAX_INITIALIZE_REQUESTS: Int = 10
private const val LOG_EVENT_RETRY: Int = 3

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

// HTTP
private const val POST = "POST"
private const val CONTENT_TYPE_HEADER_KEY = "Content-Type"
private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=UTF-8"
private const val STATSIG_API_HEADER_KEY = "STATSIG-API-KEY"
private const val STATSIG_CLIENT_TIME_HEADER_KEY = "STATSIG-CLIENT-TIME"
private const val STATSIG_SDK_TYPE_KEY = "STATSIG-SDK-TYPE"
private const val STATSIG_SDK_VERSION_KEY = "STATSIG-SDK-VERSION"
private const val STATSIG_EVENT_COUNT = "STATSIG-EVENT-COUNT"
private const val ACCEPT_HEADER_KEY = "Accept"
private const val ACCEPT_HEADER_VALUE = "application/json"

internal interface StatsigNetwork {

    suspend fun initialize(
        api: String,
        user: StatsigUser,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        coroutineScope: CoroutineScope,
        context: ContextType,
        diagnostics: Diagnostics? = null,
        hashUsed: HashAlgorithm,
        previousDerivedFields: Map<String, String>,
        fullChecksum: String?,
    ): InitializeResponse?

    fun pollForChanges(
        api: String,
        user: StatsigUser,
        metadata: StatsigMetadata,
        fallbackUrls: List<String>? = null,
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?>

    suspend fun apiPostLogs(api: String, bodyString: String, eventsCount: String? = null, fallbackUrls: List<String>? = null)

    suspend fun apiRetryFailedLogs(api: String, fallbackUrls: List<String>? = null)

    suspend fun addFailedLogRequest(requestBody: String)
}

internal fun StatsigNetwork(
    context: Context,
    sdkKey: String,
    errorBoundary: ErrorBoundary,
    sharedPrefs: SharedPreferences,
    options: StatsigOptions,
    networkFallbackResolver: NetworkFallbackResolver,
    coroutineScope: CoroutineScope,
    store: Store,
): StatsigNetwork = StatsigNetworkImpl(context, sdkKey, errorBoundary, sharedPrefs, options, networkFallbackResolver, coroutineScope, store)

internal class StatsigNetworkImpl(
    context: Context,
    private val sdkKey: String,
    private val errorBoundary: ErrorBoundary,
    private val sharedPrefs: SharedPreferences,
    private val options: StatsigOptions,
    private val networkResolver: NetworkFallbackResolver,
    private val coroutineScope: CoroutineScope,
    private val store: Store,
) : StatsigNetwork {

    private val gson = StatsigUtil.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private val connectivityListener = StatsigNetworkConnectivityListener(context)
    private val offlineLogsKeyV2 = "$OFFLINE_LOGS_KEY_V1:$sdkKey"
    private var initializeRequestsMap = Collections.synchronizedMap(mutableMapOf<String, HttpURLConnection>())
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
        fullChecksum: String?,
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
                fullChecksum = fullChecksum,
            )
        }
        return withTimeout(options.initTimeoutMs) {
            var response: InitializeResponse = InitializeResponse.FailedInitializeResponse(InitializeFailReason.InternalError, null, null)
            coroutineScope.launch {
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
                    fullChecksum = fullChecksum,
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
        fallbackUrls: List<String>? = null,
    ): InitializeResponse {
        var retry = 0
        var response: InitializeResponse
        var backoff = INITIALIZE_RETRY_BACKOFF
        do {
            response = initializeImpl(api, user, sinceTime, metadata, contextType, diagnostics, retry + 1, timeoutMs, hashUsed, previousDerivedFields, fullChecksum, fallbackUrls)
            if (response is InitializeResponse.SuccessfulInitializeResponse || retryLimit == 0) {
                return response
            }
            delay(backoff)
            ++retry
            backoff *= INITIALIZE_RETRY_BACKOFF_MULTIPLIER
        } while (retry <= retryLimit)
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
        fallbackUrls: List<String>? = null,
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
                FULL_CHECKSUM to fullChecksum,
            )
            var statusCode: Int? = null
            initializeRequestsMap[userCacheKey]?.disconnect()
            initializeRequestsMap.remove(userCacheKey)
            val response = postRequest<InitializeResponse.SuccessfulInitializeResponse>(
                UrlConfig(Endpoint.Initialize, api, fallbackUrls),
                gson.toJson(body),
                retries,
                contextType,
                diagnostics,
                timeoutMs,
                requestCacheKey = userCacheKey,
            ) { status: Int? -> statusCode = status }
            response ?: InitializeResponse.FailedInitializeResponse(
                InitializeFailReason.NetworkError,
                null,
                statusCode,
            )
        } catch (e: Exception) {
            if (connectivityListener.isNetworkAvailable()) {
                errorBoundary.logException(e)
            }

            this.endDiagnostics(
                diagnostics,
                contextType,
                KeyType.INITIALIZE,
                null,
                null,
                1,
                Marker.ErrorMessage(e.message.toString(), e.javaClass.name, e.javaClass.name),
                timeoutMs,
            )
            when (e) {
                is SocketTimeoutException, is ConnectException -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.NetworkTimeout,
                        e,
                    )
                }

                is TimeoutCancellationException -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.CoroutineTimeout,
                        e,
                    )
                }

                else -> {
                    return InitializeResponse.FailedInitializeResponse(
                        InitializeFailReason.InternalError,
                        e,
                    )
                }
            }
        }
    }

    override fun pollForChanges(
        api: String,
        user: StatsigUser,
        metadata: StatsigMetadata,
        fallbackUrls: List<String>?,
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?> {
        @Suppress("RemoveExplicitTypeArguments") // This is needed for tests
        return flow<InitializeResponse.SuccessfulInitializeResponse?> {
            val userCopy = user.getCopyForEvaluation()
            val userCacheKey = this@StatsigNetworkImpl.options.customCacheKey(this@StatsigNetworkImpl.sdkKey, userCopy)
            val metadataCopy = metadata.copy()
            val sinceTime = this@StatsigNetworkImpl.store.getLastUpdateTime(user)
            val previousDerivedFields = this@StatsigNetworkImpl.store.getPreviousDerivedFields(user)
            val fullChecksum = this@StatsigNetworkImpl.store.getFullChecksum(user)
            while (true) {
                delay(POLLING_INTERVAL_MS) // If coroutine is cancelled, this delay will exit the while loop
                val body = mapOf(
                    USER to userCopy,
                    STATSIG_METADATA to metadataCopy,
                    LAST_SYNC_TIME_FOR_USER to sinceTime,
                    SINCE_TIME to sinceTime,
                    HASH to HashAlgorithm.DJB2.value,
                    PREVIOUS_DERIVED_FIELDS to previousDerivedFields,
                    FULL_CHECKSUM to fullChecksum,
                )
                if (userCacheKey != null) {
                    initializeRequestsMap[userCacheKey]?.disconnect()
                    initializeRequestsMap.remove(userCacheKey)
                }
                try {
                    emit(
                        postRequest(
                            UrlConfig(Endpoint.Initialize, api, fallbackUrls),
                            gson.toJson(body),
                            0,
                            ContextType.CONFIG_SYNC,
                            requestCacheKey = this@StatsigNetworkImpl.options.customCacheKey(this@StatsigNetworkImpl.sdkKey, userCopy),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    override suspend fun apiPostLogs(api: String, bodyString: String, eventsCount: String?, fallbackUrls: List<String>?) {
        var currRetry = 1
        var backoff = 100L
        var statusCode: Int? = null
        try {
            while (currRetry <= LOG_EVENT_RETRY) {
                ++currRetry
                val response = postRequest<LogEventResponse>(
                    UrlConfig(Endpoint.Rgstr, api, fallbackUrls),
                    bodyString,
                    currRetry,
                    ContextType.EVENT_LOGGING,
                    eventsCount = eventsCount,
                ) {
                    statusCode = it
                }
                if (response?.success == true || statusCode?.let { it in 200..299 } == true) {
                    return
                }
                if (statusCode?.let { RETRY_CODES.contains(it) } == true) {
                    backoff *= 100L
                    delay(backoff)
                } else {
                    addFailedLogRequest(bodyString)
                    return
                }
            }
        } catch (_: Exception) {
            addFailedLogRequest(bodyString)
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
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY_V1)
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, offlineLogsKeyV2)

        val eventsCount = savedLogs.size.toString()
        savedLogs.map { apiPostLogs(api, it.requestBody, eventsCount, fallbackUrls) }
    }

    override suspend fun addFailedLogRequest(requestBody: String) {
        withContext(dispatcherProvider.io) {
            val savedLogs =
                getSavedLogs() + StatsigOfflineRequest(System.currentTimeMillis(), requestBody)
            try {
                // savedLogs wont be concurrently modified as it is read from storage and only used here
                StatsigUtil.saveStringToSharedPrefs(
                    sharedPrefs,
                    offlineLogsKeyV2,
                    gson.toJson(StatsigPendingRequests(savedLogs)),
                )
            } catch (_: Exception) {
                StatsigUtil.removeFromSharedPrefs(sharedPrefs, offlineLogsKeyV2)
            }
        }
    }

    private suspend fun getSavedLogs(): List<StatsigOfflineRequest> {
        return withContext(dispatcherProvider.io) {
            val json: String = StatsigUtil.getFromSharedPrefs(sharedPrefs, offlineLogsKeyV2)
                ?: StatsigUtil.getFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY_V1)
                ?: return@withContext arrayListOf()
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
        urlConfig: UrlConfig,
        bodyString: String,
        retries: Int, // for logging purpose
        contextType: ContextType,
        diagnostics: Diagnostics? = null,
        timeout: Int? = null,
        eventsCount: String? = null,
        requestCacheKey: String? = null,
        crossinline callback: ((statusCode: Int?) -> Unit) = { _: Int? -> },
    ): T? {
        return withContext(dispatcherProvider.io) { // Perform network calls in IO thread
            var connection: HttpURLConnection? = null
            var errorMessage: String? = null
            val start = System.nanoTime()
            var end = start
            try {
                urlConfig.fallbackUrl = networkResolver.getActiveFallbackUrlFromMemory(urlConfig)
                val url = URL(urlConfig.fallbackUrl ?: urlConfig.getUrl())
                connection = url.openConnection() as HttpURLConnection
                if (requestCacheKey != null && urlConfig.endpoint != Endpoint.Rgstr) {
                    if (initializeRequestsMap.size > MAX_INITIALIZE_REQUESTS) {
                        initializeRequestsMap.values.forEach { it.disconnect() }
                        initializeRequestsMap = Collections.synchronizedMap(mutableMapOf())
                    }
                    initializeRequestsMap[requestCacheKey] = connection
                }
                if (url.protocol == "http") {
                    connection.doOutput = true
                }
                connection.doOutput = true

                connection.requestMethod = POST
                if (timeout != null) {
                    connection.connectTimeout = timeout
                    connection.readTimeout = timeout
                }
                connection.setRequestProperty(
                    CONTENT_TYPE_HEADER_KEY,
                    CONTENT_TYPE_HEADER_VALUE,
                )
                connection.setRequestProperty(STATSIG_API_HEADER_KEY, sdkKey)
                connection.setRequestProperty(STATSIG_SDK_TYPE_KEY, "android-client")
                connection.setRequestProperty(STATSIG_SDK_VERSION_KEY, BuildConfig.VERSION_NAME)
                connection.setRequestProperty(
                    STATSIG_CLIENT_TIME_HEADER_KEY,
                    System.currentTimeMillis().toString(),
                )

                if (eventsCount != null) {
                    connection.setRequestProperty(STATSIG_EVENT_COUNT, eventsCount)
                }

                connection.setRequestProperty(ACCEPT_HEADER_KEY, ACCEPT_HEADER_VALUE)
                connection.setRequestProperty("Accept-Encoding", "gzip")

                diagnostics?.markStart(
                    KeyType.INITIALIZE,
                    StepType.NETWORK_REQUEST,
                    Marker(attempt = retries),
                    contextType,
                )
                val outputStream = if (shouldCompressLogEvent(urlConfig, url.path)) {
                    connection.setRequestProperty("Content-Encoding", "gzip") // Tell the server it's gzipped
                    GZIPOutputStream(connection.outputStream)
                } else {
                    connection.outputStream
                }

                outputStream.bufferedWriter(Charsets.UTF_8)
                    .use { it.write(bodyString) }
                val code = connection.responseCode
                val inputStream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val errorMarker = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) {
                    errorMessage =
                        inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
                    Marker.ErrorMessage(errorMessage, code.toString(), null)
                } else {
                    null
                }

                endDiagnostics(
                    diagnostics,
                    contextType,
                    KeyType.INITIALIZE,
                    code,
                    connection.headerFields["x-statsig-region"]?.get(0),
                    retries,
                    errorMarker,
                    timeout,
                )
                callback(code)
                when (code) {
                    in 200..299 -> {
                        networkResolver.tryBumpExpiryTime(sdkKey, urlConfig)
                        if (code == 204 && urlConfig.endpoint == Endpoint.Initialize) {
                            return@withContext gson.fromJson(
                                "{has_updates: false}",
                                T::class.java,
                            )
                        }
                        val encoding = connection.getHeaderField("Content-Encoding")

                        var stream = inputStream
                        if (encoding != null && encoding.equals("gzip")) {
                            stream = GZIPInputStream(stream)
                        }

                        return@withContext stream.bufferedReader(Charsets.UTF_8)
                            .use { gson.fromJson(it, T::class.java) }
                    }
                }
            } catch (e: Exception) {
                println(e)
                errorMessage = e.message
                throw e
            } finally {
                end = System.nanoTime()
                connection?.disconnect()
                coroutineScope.launch(dispatcherProvider.io) {
                    val timedOut = (end - start) / 1_000_000_000 > (timeout ?: 0)
                    val fallbackUpdated = networkResolver.tryFetchUpdatedFallbackInfo(
                        sdkKey,
                        urlConfig,
                        errorMessage,
                        timedOut,
                        connectivityListener.isNetworkAvailable(),
                    )
                    if (fallbackUpdated) {
                        urlConfig.fallbackUrl = networkResolver.getActiveFallbackUrlFromMemory(urlConfig)
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
        diagnosticsContext: ContextType,
        keyType: KeyType,
        statusCode: Int?,
        sdkRegion: String?,
        attempt: Int?,
        error: Marker.ErrorMessage? = null,
        timeoutMs: Int? = null,
    ) {
        if (diagnostics == null) {
            return
        }
        val marker =
            Marker(attempt = attempt, sdkRegion = sdkRegion, statusCode = statusCode, error = error, hasNetwork = connectivityListener.isNetworkAvailable(), timeoutMS = timeoutMs)
        val wasSuccessful = statusCode in 200..299

        diagnostics.markEnd(
            keyType,
            wasSuccessful,
            StepType.NETWORK_REQUEST,
            marker,
            overrideContext = diagnosticsContext,
        )
    }
}
