package com.statsig.androidsdk

import kotlinx.coroutines.*
import kotlinx.coroutines.CoroutineDispatcher
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import android.content.SharedPreferences
import kotlin.math.pow
import kotlin.reflect.KClass
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.ArrayList

private val completableJob = Job()
private val coroutineScope = CoroutineScope(Dispatchers.IO + completableJob)

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

private val OFFLINE_LOGS_KEY: String = "StatsigNetwork.OFFLINE_LOGS"
private val LOGGING_ENDPOINT: String = "log_event"

private val INITIALIZE_ENDPOINT: String = "initialize"
private val POLLING_INTERVAL_MS: Long = 10000

open class StatsigNetwork {
    companion object {

        private var lastSyncTimeForUser: Long = 0

        suspend fun initialize(
            api: String,
            sdkKey: String,
            user: StatsigUser?,
            metadata: StatsigMetadata,
            initTimeoutMs: Long,
        ): InitializeResponse? {
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
            try {
                var body = mapOf("user" to user, "statsigMetadata" to metadata)
                val response =
                    postRequestAsync(
                        api,
                        INITIALIZE_ENDPOINT,
                        sdkKey,
                        Gson().toJson(body),
                        InitializeResponse::class,
                        0,
                        null,
                    )
                val instance = this
                return runBlocking {
                    instance.lastSyncTimeForUser = response?.time ?: instance.lastSyncTimeForUser
                    response
                }
            } catch (_e : Exception) {
                return null
            }
        }

        fun pollForChanges(
            api: String,
            sdkKey: String,
            user: StatsigUser?,
            metadata: StatsigMetadata,
            callback: (InitializeResponse?) -> Unit,
        ): Job {
            val network = this
            return CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(POLLING_INTERVAL_MS)
                    var body = mapOf(
                        "user" to user,
                        "statsigMetadata" to metadata,
                        "lastSyncTimeForUser" to network.lastSyncTimeForUser
                    )
                    val response = postRequestAsync(
                        api,
                        INITIALIZE_ENDPOINT,
                        sdkKey,
                        Gson().toJson(body),
                        InitializeResponse::class,
                        0,
                        null,
                    )
                    runBlocking {
                        callback(response)
                    }
                }
            }
        }

        fun apiPostLogs(
            api: String,
            sdkKey: String,
            bodyString: String,
            sharedPrefs: SharedPreferences?,
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                postRequestAsync(
                    api,
                    LOGGING_ENDPOINT,
                    sdkKey,
                    bodyString,
                    LogEventResponse::class,
                    3,
                    sharedPrefs,
                )
            }
        }

        @Synchronized
        fun apiRetryFailedLogs(
            api: String,
            sdkKey: String,
            sharedPrefs: SharedPreferences,
        ) {
            val savedLogs = getSavedLogs(sharedPrefs)
            if (savedLogs == null || savedLogs.isEmpty()) {
                return
            }
            sharedPrefs.edit().remove(OFFLINE_LOGS_KEY).apply()
            savedLogs.map {
                apiPostLogs(api, sdkKey, it.requestBody, sharedPrefs)
            }
        }

        private fun addFailedLogRequest(sharedPrefs: SharedPreferences, requestBody: String) {
            var savedLogs = getSavedLogs(sharedPrefs)
            if (savedLogs == null) {
                savedLogs = ArrayList<StatsigOfflineRequest>()
            }
            savedLogs.add(StatsigOfflineRequest(java.lang.System.currentTimeMillis(), requestBody))
            saveFailedRequests(sharedPrefs, StatsigPendingRequests(ArrayList(savedLogs)))
        }

        @Synchronized
        private fun saveFailedRequests(
            sharedPrefs: SharedPreferences,
            pending: StatsigPendingRequests
        ) {
            sharedPrefs.edit().putString(OFFLINE_LOGS_KEY, Gson().toJson(pending)).apply()
        }

        fun getSavedLogs(sharedPrefs: SharedPreferences): MutableList<StatsigOfflineRequest>? {
            val json: String? = sharedPrefs.getString(OFFLINE_LOGS_KEY, null)
            if (json == null) {
                return null
            }
            val pendingRequests = Gson().fromJson(json, StatsigPendingRequests::class.java)
            if (pendingRequests == null || pendingRequests.requests == null) {
                return null
            }
            val currentTime = java.lang.System.currentTimeMillis()
            return (pendingRequests.requests.filter {
                it.timestamp > currentTime - TimeUnit.DAYS.toMillis(
                    3
                )
            }).toMutableList()
        }

        suspend fun <T : Any> postRequestAsync(
            api: String,
            endpoint: String,
            sdkKey: String,
            bodyString: String,
            responseType: KClass<T>,
            retries: Int,
            sharedPrefs: SharedPreferences?,
            retryAttempt: Int = 0,
        ): T? {
            val url = if (api.endsWith("/")) "$api$endpoint" else "$api/$endpoint"
            val connection: HttpURLConnection =
                URL(url).openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("STATSIG-API-KEY", sdkKey)
            connection.setRequestProperty("STATSIG-CLIENT-TIME", java.lang.System.currentTimeMillis().toString())
            connection.setRequestProperty("Accept", "application/json");

            try {
                connection.outputStream.use { os ->
                    val input: ByteArray = bodyString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }
                connection.outputStream.flush()
                connection.outputStream.close()

                return runBlocking {
                    var response: T? = Gson().fromJson(
                        InputStreamReader(connection.inputStream, Charsets.UTF_8),
                        responseType.java
                    )
                    connection.inputStream.close()

                    if (connection.responseCode >= 200 && connection.responseCode < 300) {
                        response
                    } else if (RETRY_CODES.contains(connection.responseCode)) {
                        if (retries > 0 && retryAttempt < retries) {
                            Thread.sleep((100.0).pow(retryAttempt + 1).toLong())
                            postRequestAsync(
                                api,
                                endpoint,
                                sdkKey,
                                bodyString,
                                responseType,
                                retries,
                                sharedPrefs,
                                retryAttempt + 1
                            )
                        } else if (sharedPrefs != null && endpoint.equals(LOGGING_ENDPOINT)) {
                            addFailedLogRequest(sharedPrefs, bodyString)
                            null
                        }
                        null
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                if (sharedPrefs != null && endpoint.equals(LOGGING_ENDPOINT)) {
                    addFailedLogRequest(sharedPrefs, bodyString)
                }
                null
            }
            return null
        }
    }
}
