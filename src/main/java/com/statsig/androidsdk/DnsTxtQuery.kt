package com.statsig.androidsdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

val FEATURE_ASSETS_DNS_QUERY = byteArrayOf(
    0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0d,
    0x66, 0x65, 0x61, 0x74, 0x75, 0x72, 0x65, 0x61, 0x73, 0x73, 0x65, 0x74, 0x73,
    0x03, 0x6f, 0x72, 0x67, 0x00, 0x00, 0x10, 0x00, 0x01,
)

const val DNS_QUERY_ENDPOINT = "https://cloudflare-dns.com/dns-query"

val DOMAIN_CHARS = listOf('i', 'e', 'd') // valid domain characters: 'i', 'e', 'd'
const val MAX_START_LOOKUP = 200

suspend fun fetchTxtRecords(): List<String> = withContext(Dispatchers.IO) {
    val connection = createHttpConnection(DNS_QUERY_ENDPOINT)

    try {
        connection.outputStream.use { outputStream ->
            val byteArray = ByteArrayOutputStream().apply {
                write(FEATURE_ASSETS_DNS_QUERY)
            }.toByteArray()
            outputStream.write(byteArray)
        }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw DnsTxtFetchError("Failed to fetch TXT records from DNS")
        }

        val inputStream = connection.inputStream

        val bytes = inputStream.readBytes()
        return@withContext parseDnsResponse(bytes)
    } catch (e: Exception) {
        throw DnsTxtFetchError("Request timed out while fetching TXT records")
    } finally {
        connection.disconnect()
    }
}

private fun createHttpConnection(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/dns-message")
        setRequestProperty("Accept", "application/dns-message")
        doOutput = true
//        connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
//        readTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
    }
    return connection
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
    return result.split(",")
}

class DnsTxtFetchError(message: String) : Exception(message)
class DnsTxtParseError(message: String) : Exception(message)
