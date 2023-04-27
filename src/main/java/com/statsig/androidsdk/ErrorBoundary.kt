package com.statsig.androidsdk

import com.google.gson.Gson
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class ErrorBoundary() {
  internal var urlString = "https://statsigapi.net/v1/sdk_exception"

  private var apiKey: String? = null
  private var statsigMetadata: StatsigMetadata? = null
  private var seen = HashSet<String>()

  fun setKey(apiKey: String) {
    this.apiKey = apiKey
  }

  fun setMetadata(statsigMetadata: StatsigMetadata) {
    this.statsigMetadata = statsigMetadata
  }

  fun capture(task: () -> Unit, recover: (() -> Unit)? = null) {
    try {
      task()
    } catch (e: Exception) {
      println("[Statsig]: An unexpected exception occurred.")
      println(e)

      logException(e)
      recover?.let { it() }
    }
  }

  suspend fun <T> captureAsync(task: suspend () -> T, recover: (suspend () -> Unit)? = null): T? {
    return try {
      task()
    } catch (e: Exception) {
      println("[Statsig]: An unexpected exception occurred.")
      println(e)

      logException(e)
      recover?.let { it() }
      null
    }
  }

  internal fun logException(exception: Exception) {
    try {
      if (apiKey == null) {
        return
      }

      val name = exception.javaClass.canonicalName ?: exception.javaClass.name
      if (seen.contains(name)) {
        return
      }

      seen.add(name)

      val metadata = statsigMetadata ?: StatsigMetadata("")
      val url = URL(urlString)
      val body = mapOf(
        "exception" to name,
        "info" to exception.stackTraceToString(),
        "statsigMetadata" to metadata
      )
      val postData = Gson().toJson(body)

      val conn = url.openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Content-Length", postData.length.toString())
      conn.setRequestProperty("STATSIG-API-KEY", apiKey)
      conn.useCaches = false

      DataOutputStream(conn.outputStream).use { it.writeBytes(postData) }
      conn.responseCode // triggers request
    } catch (e: Exception) {
    }
  }
}