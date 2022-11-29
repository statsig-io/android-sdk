package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    suspend fun initialize(api: String, sdkKey: String, user: StatsigUser?, metadata: StatsigMetadata, initTimeoutMs: Long, sharedPrefs: SharedPreferences) : InitializeResponse?

    fun pollForChanges(api: String, sdkKey: String, user: StatsigUser?, metadata: StatsigMetadata): Flow<InitializeResponse?>

    suspend fun apiPostLogs(api: String, sdkKey: String, bodyString: String)

    suspend fun apiRetryFailedLogs(api: String, sdkKey: String)
}

internal fun StatsigNetwork(): StatsigNetwork = StatsigNetworkImpl()

private class StatsigNetworkImpl : StatsigNetwork {

    private val gson =  GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val dispatcherProvider = CoroutineDispatcherProvider()
    private var lastSyncTimeForUser: Long = 0
    private lateinit var sharedPrefs: SharedPreferences

    override suspend fun initialize(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        metadata: StatsigMetadata,
        initTimeoutMs: Long,
        sharedPrefs: SharedPreferences
    ): InitializeResponse? {
        this.sharedPrefs = sharedPrefs
        if (initTimeoutMs == 0L) {
            return initializeImpl(api, sdkKey, user, metadata)
        }
        return withTimeoutOrNull(initTimeoutMs) {
            initializeImpl(api, sdkKey, user, metadata)
        }
    }

    private suspend fun initializeImpl(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        metadata: StatsigMetadata,
    ): InitializeResponse? {
        return try {
            val body = mapOf(USER to user, STATSIG_METADATA to metadata)
            val response = postRequest<InitializeResponse>(api, INITIALIZE_ENDPOINT, sdkKey, gson.toJson(body), 0)
            lastSyncTimeForUser = response?.time ?: lastSyncTimeForUser
            response
        } catch (_ : Exception) {
            null
        }
    }

    override fun pollForChanges(
        api: String,
        sdkKey: String,
        user: StatsigUser?,
        metadata: StatsigMetadata
    ): Flow<InitializeResponse?> {
        @Suppress("RemoveExplicitTypeArguments") // This is needed for tests
        return flow<InitializeResponse?> {
            while (true) {
                delay(POLLING_INTERVAL_MS) // If coroutine is cancelled, this delay will exit the while loop
                val body = mapOf(
                    USER to user,
                    STATSIG_METADATA to metadata,
                    LAST_SYNC_TIME_FOR_USER to lastSyncTimeForUser
                )
                try {
                    emit(postRequest(api, INITIALIZE_ENDPOINT, sdkKey, gson.toJson(body), 0))
                } catch (_: Exception) {}
            }
        }
    }

    override suspend fun apiPostLogs(api: String, sdkKey: String, bodyString: String) {
        postRequest<LogEventResponse>(api, LOGGING_ENDPOINT, sdkKey, bodyString, 3)
    }

    override suspend fun apiRetryFailedLogs(api: String, sdkKey: String) {
        val savedLogs = getSavedLogs()
        if (savedLogs.isEmpty()) {
            return
        }
        StatsigUtil.removeFromSharedPrefs(sharedPrefs, OFFLINE_LOGS_KEY)
        savedLogs.map { apiPostLogs(api, sdkKey, it.requestBody) }
    }

    private suspend fun addFailedLogRequest(requestBody: String) {
        withContext(dispatcherProvider.io) {
            val savedLogs = getSavedLogs() + StatsigOfflineRequest(System.currentTimeMillis(), requestBody)
            try {
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
    private suspend inline fun <reified T : Any> postRequest(api: String, endpoint: String, sdkKey: String, bodyString: String, retries: Int): T? {
        return withContext(dispatcherProvider.io) { // Perform network calls in IO thread
            var retryAttempt = 0
            while (isActive) {
                val url = if (api.endsWith("/")) "$api$endpoint" else "$api/$endpoint"
                val connection: HttpURLConnection = URL(url).openConnection() as HttpURLConnection

                connection.requestMethod = POST
                connection.setRequestProperty(CONTENT_TYPE_HEADER_KEY, CONTENT_TYPE_HEADER_VALUE)
                connection.setRequestProperty(STATSIG_API_HEADER_KEY, sdkKey)
                connection.setRequestProperty(STATSIG_SDK_TYPE_KEY, "android-client")
                connection.setRequestProperty(STATSIG_SDK_VERSION_KEY, BuildConfig.VERSION_NAME)
                connection.setRequestProperty(STATSIG_CLIENT_TIME_HEADER_KEY, System.currentTimeMillis().toString())
                connection.setRequestProperty(ACCEPT_HEADER_KEY, ACCEPT_HEADER_VALUE)

                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8)
                        .use { it.write(bodyString) }
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { gson.fromJson(it, T::class.java) }

                    when (connection.responseCode) {
                        in 200..299 -> return@withContext response
                        in RETRY_CODES -> {
                            if (retries > 0 && retryAttempt++ < retries) {
                                // Don't return, just allow the loop to happen
                                delay(100.0.pow(retryAttempt + 1).toLong())
                            } else if (endpoint == LOGGING_ENDPOINT) {
                                addFailedLogRequest(bodyString)
                                return@withContext null
                            } else {
                                return@withContext null
                            }
                        }
                        else -> return@withContext null
                    }
                } catch (e: Exception) {
                    if (endpoint == LOGGING_ENDPOINT) {
                        addFailedLogRequest(bodyString)
                    }
                    return@withContext null
                } finally {
                    connection.disconnect()
                }
            }
            return@withContext null
        }
    }
}
