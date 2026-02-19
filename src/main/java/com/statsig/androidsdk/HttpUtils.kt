package com.statsig.androidsdk

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.Companion.PACKAGE_PRIVATE
import androidx.annotation.VisibleForTesting.Companion.PRIVATE
import com.statsig.androidsdk.HttpUtils.Companion.CONTENT_ENCODING_HEADER_KEY
import com.statsig.androidsdk.HttpUtils.Companion.CONTENT_TYPE_HEADER_KEY
import com.statsig.androidsdk.HttpUtils.Companion.CONTENT_TYPE_HEADER_VALUE
import com.statsig.androidsdk.HttpUtils.Companion.ENCODING_GZIP
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_API_HEADER_KEY
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_CLIENT_TIME_HEADER_KEY
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_SDK_TYPE_KEY
import com.statsig.androidsdk.HttpUtils.Companion.STATSIG_SDK_VERSION_KEY
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.BufferedSink
import okio.buffer
import okio.gzip

@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
class HttpUtils {
    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    companion object {
        internal const val TAG = "statsig::Http"

        @JvmSynthetic // hide from Java
        internal val RETRY_CODES: IntArray = intArrayOf(
            HttpURLConnection.HTTP_CLIENT_TIMEOUT,
            HttpURLConnection.HTTP_INTERNAL_ERROR,
            HttpURLConnection.HTTP_BAD_GATEWAY,
            HttpURLConnection.HTTP_UNAVAILABLE,
            HttpURLConnection.HTTP_GATEWAY_TIMEOUT,
            522,
            524,
            599
        )

        // HTTP constants

        @JvmSynthetic
        internal const val CONTENT_TYPE_HEADER_KEY = "Content-Type"

        @JvmSynthetic
        internal const val CONTENT_TYPE_HEADER_VALUE = "application/json; charset=UTF-8"

        @JvmSynthetic
        internal const val CONTENT_ENCODING_HEADER_KEY = "Content-Encoding"

        @JvmSynthetic
        internal const val ENCODING_GZIP = "gzip"

        @JvmSynthetic
        internal const val STATSIG_API_HEADER_KEY = "STATSIG-API-KEY"

        @JvmSynthetic
        internal const val STATSIG_CLIENT_TIME_HEADER_KEY = "STATSIG-CLIENT-TIME"

        @JvmSynthetic
        internal const val STATSIG_SDK_TYPE_KEY = "STATSIG-SDK-TYPE"

        @JvmSynthetic
        internal const val STATSIG_SDK_VERSION_KEY = "STATSIG-SDK-VERSION"

        @JvmSynthetic
        internal const val STATSIG_EVENT_COUNT = "STATSIG-EVENT-COUNT"

        @JvmSynthetic
        internal const val STATSIG_STABLE_ID_HEADER_KEY = "STATSIG-STABLE-ID"

        @JvmSynthetic
        internal const val CONNECTION_HEADER_KEY = "CONNECTION"

        @JvmSynthetic
        internal const val CONNECTION_HEADER_CLOSE = "CLOSE"

        @JvmSynthetic
        internal val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

        @JvmSynthetic
        @VisibleForTesting(otherwise = PRIVATE)
        var okHttpClient: OkHttpClient? = null

        @JvmSynthetic
        internal fun getHttpClient(): OkHttpClient {
            if (okHttpClient != null) {
                return okHttpClient!!
            }
            // This should only ever happen if the client was never initialized
            // In these cases, create a safety-valve client that doesn't have a DNS cache.
            okHttpClient = buildHttpClient(app = null)
            return okHttpClient!!
        }

        @JvmSynthetic
        @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
        fun maybeInitializeHttpClient(app: Application?) {
            if (okHttpClient == null) {
                okHttpClient = buildHttpClient(app)
            }
        }

        @JvmSynthetic
        @VisibleForTesting
        var dohEndpointOverride: String? = null

        private fun buildHttpClient(app: Application? = null): OkHttpClient {
            var appCache: Cache? = null
            if (app != null) {
                // Including process name in the file path to make the cache multi-process safe.
                val process = app.applicationInfo.processName

                // OkHttp's Cache won't cache POST requests - this is for caching DNS/DoH queries
                appCache = Cache(
                    directory = File(app.cacheDir, "statsig_http_cache_$process"),
                    maxSize = 50L * 1024L * 1024L // 50 MB
                )
            }

            // Attempt DNS over HTTPS to avoid system-level DNS configurations from causing issues
            // DoH attempt times out after 500ms to engage the System DNS fallback
            val bootStrapClient = OkHttpClient.Builder().cache(appCache)
                .connectTimeout(500.milliseconds.toJavaDuration())
                .retryOnConnectionFailure(false)
                .build()

            val dohEndpoint = dohEndpointOverride ?: DNS_QUERY_ENDPOINT

            val dohDns = DnsOverHttps.Builder().client(bootStrapClient)
                .url(dohEndpoint.toHttpUrl())
                .build()

            // The actual client uses the default OkHttp connection timeout
            return bootStrapClient.newBuilder()
                .dns(DohDnsWithSystemFallback(dohDns))
                .connectTimeout(10.seconds.toJavaDuration())
                .build()
        }

        internal fun addInterceptors(interceptors: List<Interceptor>) {
            if (interceptors.isEmpty()) {
                // No interceptors provided - ignore
                return
            }
            okHttpClient?.let {
                val builder = okHttpClient!!.newBuilder()
                interceptors.forEach { builder.addInterceptor(it) }
                okHttpClient = builder.build()
            }
        }
    }
}

internal class DohDnsWithSystemFallback(val doh: DnsOverHttps, val systemDns: Dns = Dns.SYSTEM) :
    Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        try {
            return doh.lookup(hostname)
        } catch (e: IOException) {
            // IOException covers the majority of network exceptions OkHttp would expose here.
            Log.w(HttpUtils.TAG, "DoH lookup failed, attempting fallback to system DNS.", e)
            Log.w(HttpUtils.TAG, "${e.cause}")
        }
        return systemDns.lookup(hostname)
    }
}

@JvmSynthetic
internal fun Request.Builder.addStatsigHeaders(apiKey: String): Request.Builder {
    this.addHeader(CONTENT_TYPE_HEADER_KEY, CONTENT_TYPE_HEADER_VALUE)
        .addHeader(STATSIG_API_HEADER_KEY, apiKey)
        .addHeader(STATSIG_SDK_TYPE_KEY, "android-client")
        .addHeader(STATSIG_SDK_VERSION_KEY, BuildConfig.VERSION_NAME)
        .addHeader(
            STATSIG_CLIENT_TIME_HEADER_KEY,
            System.currentTimeMillis().toString()
        ).addHeader(HttpUtils.CONNECTION_HEADER_KEY, HttpUtils.CONNECTION_HEADER_CLOSE)
    // The "Connection: Close" header is a workaround for some failures seen in Kong testing.
    //  The server might not be responding with chunked encoding or content-length headers.
    //  Could also be the no-retry configuration playing poorly with stale open connections.
    return this
}

@JvmSynthetic
internal fun String.toJsonRequestBody(): RequestBody = this.toRequestBody(HttpUtils.JSON_MEDIA_TYPE)

internal class GZipRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.body == null ||
            originalRequest.headers.names().contains(CONTENT_ENCODING_HEADER_KEY)
        ) {
            return chain.proceed(originalRequest)
        }

        val compressedRequest =
            originalRequest.newBuilder()
                .addHeader(CONTENT_ENCODING_HEADER_KEY, ENCODING_GZIP)
                .method(originalRequest.method, gzip(originalRequest.body!!))
                .build()
        return chain.proceed(compressedRequest)
    }

    private fun gzip(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentLength(): Long = -1

        override fun contentType(): MediaType? = body.contentType()

        override fun writeTo(sink: BufferedSink) {
            val gzipSink = sink.gzip().buffer()
            body.writeTo(gzipSink)
            gzipSink.close()
        }
    }
}
