package com.statsig.androidsdk

import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow
import kotlin.reflect.KClass

private val completableJob = Job()
private val coroutineScope = CoroutineScope(Dispatchers.IO + completableJob)

private val RETRY_CODES : IntArray = intArrayOf(
    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
    HttpURLConnection.HTTP_INTERNAL_ERROR,
    HttpURLConnection.HTTP_BAD_GATEWAY,
    HttpURLConnection.HTTP_UNAVAILABLE,
    HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
    522,
    524,
    599
)

fun apiPost(api: String, endpoint: String, sdkKey: String, bodyString: String, callback: (InitializeResponse?) -> Unit) {
    coroutineScope.launch(Dispatchers.IO) {
        var response = withTimeoutOrNull(3000) {
            postRequestAsync(api, endpoint, sdkKey, bodyString, InitializeResponse::class, 0).await()
        }

        if (response == null) {
            callback(null)
            response = postRequestAsync(api, endpoint, sdkKey, bodyString, InitializeResponse::class, 3).await()
        }

        if (response == null) {
            callback(null)
        } else {
            callback(response)
        }
    }
}

fun apiPostLogs(api: String, endpoint: String, sdkKey: String, bodyString: String) {
    coroutineScope.launch(Dispatchers.IO) {
        postRequestAsync(api, endpoint, sdkKey, bodyString, LogEventResponse::class, 10).await()
    }
}

suspend fun <T: Any> postRequestAsync(api: String, endpoint: String, sdkKey: String, bodyString: String, responseType: KClass<T>, retries: Int, retryAttempt: Int = 0): Deferred<T?> = GlobalScope.async {
    val connection : HttpURLConnection = URL("$api/$endpoint").openConnection() as HttpURLConnection

    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
    connection.setRequestProperty("STATSIG-API-KEY", sdkKey)
    connection.setRequestProperty("Accept", "application/json");

    try {
        connection.outputStream.use { os ->
            val input: ByteArray = bodyString.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }
        connection.outputStream.flush()
        connection.outputStream.close()

        var response: T = Gson().fromJson(
            InputStreamReader(connection.inputStream, Charsets.UTF_8),
            responseType.java
        )
        connection.inputStream.close()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            response
        } else if (
            retries > 0 && retryAttempt < retries && RETRY_CODES.contains(connection.responseCode)
        ) {
            Thread.sleep((100.0).pow(retryAttempt + 1).toLong())
            postRequestAsync(
                api,
                endpoint,
                sdkKey,
                bodyString,
                responseType,
                retries,
                retryAttempt + 1
            ).await()
        } else {
            null
        }
    } catch (e : Exception) {
        null
    }
}