package com.statsig.androidsdk

import android.util.Log
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val FEATURE_ASSETS_DNS_QUERY = byteArrayOf(
    0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0d,
    0x66, 0x65, 0x61, 0x74, 0x75, 0x72, 0x65, 0x61, 0x73, 0x73, 0x65, 0x74, 0x73,
    0x03, 0x6f, 0x72, 0x67, 0x00, 0x00, 0x10, 0x00, 0x01
)

const val DNS_QUERY_ENDPOINT = "https://cloudflare-dns.com/dns-query"

val DOMAIN_CHARS = listOf('i', 'e', 'd') // valid domain characters: 'i', 'e', 'd'
const val MAX_START_LOOKUP = 200

private val coroutineDispatcherProvider by lazy {
    CoroutineDispatcherProvider()
}

private val TAG = "statsig::DnsTxtQuery"

suspend fun fetchTxtRecords(): List<String> = withContext(coroutineDispatcherProvider.io) {
    val request =
        Request.Builder().url(DNS_QUERY_ENDPOINT).post(FEATURE_ASSETS_DNS_QUERY.toRequestBody())
            .addHeader(HttpUtils.CONTENT_TYPE_HEADER_KEY, "application/dns-message")
            .addHeader("Accept", "application/dns-message")
            .addHeader(HttpUtils.CONNECTION_HEADER_KEY, HttpUtils.CONNECTION_HEADER_CLOSE)
            .build()
    val response = HttpUtils.getHttpClient().newCall(request).execute()
    try {
        return@withContext parseDnsResponse(response.body!!.bytes())
    } catch (e: Exception) {
        throw DnsTxtFetchError("Request timed out while fetching TXT records")
    } finally {
        response.close()
    }
}

fun parseDnsResponse(input: ByteArray): List<String> {
    val startIndex = input.withIndex().indexOfFirst { (index, byte) ->
        index < MAX_START_LOOKUP &&
            byte.toInt().toChar() == '=' &&
            index > 0 && DOMAIN_CHARS.contains(input[index - 1].toInt().toChar())
    }

    if (startIndex == -1) {
        throw DnsTxtParseError("Failed to parse TXT records from DNS")
    }

    val result = String(input.copyOfRange(startIndex - 1, input.size), StandardCharsets.UTF_8)
    Log.v(TAG, "Parsed response: $result")
    return result.split(",")
}

class DnsTxtFetchError(message: String) : Exception(message)
class DnsTxtParseError(message: String) : Exception(message)
