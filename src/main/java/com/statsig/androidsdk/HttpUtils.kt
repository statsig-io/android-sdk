package com.statsig.androidsdk

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.HttpURLConnection


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
private const val CONTENT_TYPE_HEADER_KEY = "Content-Type"
private const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=UTF-8"
private const val STATSIG_API_HEADER_KEY = "STATSIG-API-KEY"
private const val STATSIG_CLIENT_TIME_HEADER_KEY = "STATSIG-CLIENT-TIME"
private const val STATSIG_SDK_TYPE_KEY = "STATSIG-SDK-TYPE"
private const val STATSIG_SDK_VERSION_KEY = "STATSIG-SDK-VERSION"
private const val ACCEPT_HEADER_KEY = "Accept"
private const val ACCEPT_HEADER_VALUE = "application/json"
internal val JSON: MediaType = "application/json; charset=utf-8".toMediaType();

class RequestHeaderInterceptor(private val sdkKey: String) : Interceptor {
    @Throws(Exception::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = original.newBuilder()
            .addHeader(CONTENT_TYPE_HEADER_KEY, CONTENT_TYPE_HEADER_VALUE)
            .addHeader(STATSIG_API_HEADER_KEY, sdkKey)
            .addHeader(STATSIG_SDK_TYPE_KEY, "android-client")
            .addHeader(STATSIG_SDK_VERSION_KEY, BuildConfig.VERSION_NAME)
            .addHeader(STATSIG_CLIENT_TIME_HEADER_KEY, System.currentTimeMillis().toString())
            .addHeader(ACCEPT_HEADER_KEY, ACCEPT_HEADER_VALUE)
            .method(original.method, original.body)
            .build()
        return chain.proceed(request)
    }
}

class ResponseInterceptor : Interceptor {
    @Throws(Exception::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        var attempt = 1
        var retries = 0
        if (LOGGING_ENDPOINT in request.url.pathSegments) {
            retries = 3
        }
        while (!response.isSuccessful && attempt <= retries && response.code in RETRY_CODES) {
            attempt++

            response.close()
            response = chain.proceed(request)
        }

        val bodyString = response.body?.string()

        return response.newBuilder()
            .body(bodyString?.toResponseBody(response.body?.contentType()))
            .addHeader("attempt", attempt.toString())
            .build()
    }
}