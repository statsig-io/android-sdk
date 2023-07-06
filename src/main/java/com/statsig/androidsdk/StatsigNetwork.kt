package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.TimeoutCancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException

private val RETRY_CODES: IntArray = intArrayOf(
    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
    HttpURLConnection.HTTP_INTERNAL_ERROR,
    HttpURLConnection.HTTP_BAD_GATEWAY,
    HttpURLConnection.HTTP_UNAVAILABLE,
    HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
    522,
    524,
    599
)

// Constants
private val MAX_LOG_PERIOD = TimeUnit.DAYS.toMillis(3)
private const val POLLING_INTERVAL_MS: Long = 10000

// JSON keys
private const val USER = "user"
private const val STATSIG_METADATA = "statsigMetadata"
private const val LAST_SYNC_TIME_FOR_USER = "lastSyncTimeForUser"
private const val SINCE_TIME = "sinceTime"

// SharedPref keys
private const val OFFLINE_LOGS_KEY: String = "StatsigNetwork.OFFLINE_LOGS"

// Endpoints
private const val LOGGING_ENDPOINT: String = "log_event"
private const val INITIALIZE_ENDPOINT: String = "initialize"

// HTTP
private const val POST = "POST"
private const val CONTENT_TYPE_HEADER_KEY = "Content-Type"
private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=UTF-8"
private const val STATSIG_API_HEADER_KEY = "STATSIG-API-KEY"
private const val STATSIG_CLIENT_TIME_HEADER_KEY = "STATSIG-CLIENT-TIME"
private const val STATSIG_SDK_TYPE_KEY = "STATSIG-SDK-TYPE"
private const val STATSIG_SDK_VERSION_KEY = "STATSIG-SDK-VERSION"
private const val ACCEPT_HEADER_KEY = "Accept"
private const val ACCEPT_HEADER_VALUE = "application/json"

internal interface StatsigNetwork {

    suspend fun initialize(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        initTimeoutMs: Long,
        sharedPrefs: SharedPreferences
    ) : InitializeResponse?

    fun pollForChanges(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata
    ): Flow<InitializeResponse.SuccessfulInitializeResponse?>

    suspend fun apiPostLogs(api: String, sdkKey: String, bodyString: String)

    suspend fun apiRetryFailedLogs(api: String, sdkKey: String)

    suspend fun addFailedLogRequest(body: String)
}

internal fun StatsigNetwork(): StatsigNetwork = StatsigNetworkImpl()

private class StatsigNetworkImpl : StatsigNetwork {

    private val gson =  GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private var lastSyncTimeForUser: Long = 0
    private var sharedPrefs: SharedPreferences? = null

    override suspend fun initialize(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        initTimeoutMs: Long,
        sharedPrefs: SharedPreferences
    ): InitializeResponse {
        this.sharedPrefs = sharedPrefs
        if (initTimeoutMs == 0L) {
            return initializeImpl(api, sdkKey, user, sinceTime, metadata)
        }
        return withTimeout(initTimeoutMs) {
            initializeImpl(api, sdkKey, user, sinceTime, metadata, initTimeoutMs.toInt())
        }
    }

    private suspend fun initializeImpl(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata,
        timeoutMs: Int? = null
    ): InitializeResponse {
        return try {
            val userCopy = user?.getCopyForEvaluation()
            val metadataCopy = metadata.copy()
            val body = mapOf(USER to userCopy, STATSIG_METADATA to metadataCopy, SINCE_TIME to sinceTime)
            var statusCode: Int? = null
            val response = postRequest<InitializeResponse.SuccessfulInitializeResponse>(api, INITIALIZE_ENDPOINT, sdkKey, gson.toJson(body), 0, timeoutMs) { status: Int? -> statusCode = status }
            lastSyncTimeForUser = response?.time ?: lastSyncTimeForUser
            response ?: InitializeResponse.FailedInitializeResponse(InitializeFailReason.NetworkError, null, statusCode)
        } catch (e : Exception) {
            Statsig.errorBoundary.logException(e)
            when(e) {
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

    override fun pollForChanges(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        sinceTime: Long?,
        metadata: StatsigMetadata
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
                    SINCE_TIME to sinceTime
                )
                try {
                    emit(postRequest(api, INITIALIZE_ENDPOINT, sdkKey, gson.toJson(body), 0))
                } catch (_: Exception) {}
            }
        }
    }

    override suspend fun apiPostLogs(api: String, sdkKey: String, bodyString: String) {
        try {
            postRequest<LogEventResponse>(api, LOGGING_ENDPOINT, sdkKey, bodyString, 3)
        } catch (_: Exception) {}
    }

    override suspend fun apiRetryFailedLogs(api: String, sdkKey: String) {
        val savedLogs = getSavedLogs()
        if (savedLogs.isEmpty()) {
            return
        }
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY)
        savedLogs.map { apiPostLogs(api, sdkKey, it.requestBody) }
    }

    override suspend fun addFailedLogRequest(requestBody: String) {
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
            sdkKey: String,
            bodyString: String,
            retries: Int,
            timeout: Int? = null,
            crossinline callback: ((statusCode: Int?) -> Unit) = { _: Int? -> }): T? {
        return withContext(dispatcherProvider.io) { // Perform network calls in IO thread
            var retryAttempt = 0
            while (isActive) {
                val url = if (api.endsWith("/")) "$api$endpoint" else "$api/$endpoint"
                val connection: HttpURLConnection = URL(url).openConnection() as HttpURLConnection

                connection.requestMethod = POST
                if (timeout != null) {
                    connection.connectTimeout = timeout
                    connection.readTimeout = timeout
                }
                connection.setRequestProperty(CONTENT_TYPE_HEADER_KEY, CONTENT_TYPE_HEADER_VALUE)
                connection.setRequestProperty(STATSIG_API_HEADER_KEY, sdkKey)
                connection.setRequestProperty(STATSIG_SDK_TYPE_KEY, "android-client")
                connection.setRequestProperty(STATSIG_SDK_VERSION_KEY, BuildConfig.VERSION_NAME)
                connection.setRequestProperty(STATSIG_CLIENT_TIME_HEADER_KEY, System.currentTimeMillis().toString())
                connection.setRequestProperty(ACCEPT_HEADER_KEY, ACCEPT_HEADER_VALUE)

                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8)
                        .use { it.write(bodyString) }
                    val inputStream = if (connection.responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val response = inputStream.bufferedReader(Charsets.UTF_8)
                        .use { gson.fromJson(it, T::class.java) }

                    when (connection.responseCode) {
                        in 200..299 -> return@withContext response
                        in RETRY_CODES -> {
                            if (retries > 0 && retryAttempt++ < retries) {
                                // Don't return, just allow the loop to happen
                                delay(100.0.pow(retryAttempt + 1).toLong())
                            } else if (endpoint == LOGGING_ENDPOINT) {
                                addFailedLogRequest(bodyString)
                                callback(connection.responseCode)
                                return@withContext null
                            } else {
                                callback(connection.responseCode)
                                return@withContext null
                            }
                        }
                        else -> {
                            callback(connection.responseCode)
                            return@withContext null
                        }
                    }
                } catch (e: Exception) {
                    if (endpoint == LOGGING_ENDPOINT) {
                        addFailedLogRequest(bodyString)
                    }
                    throw e
                } finally {
                    connection.disconnect()
                }
            }
            return@withContext null
        }
    }
}
