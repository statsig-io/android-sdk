package com.statsig.androidsdk

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URL
import java.util.Date

data class FallbackInfoEntry(
    var url: String? = null,
    var previous: MutableList<String> = mutableListOf(),
    var expiryTime: Long,
)

const val DEFAULT_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
const val COOLDOWN_TIME_MS = 4 * 60 * 60 * 1000L // 4 hours

internal class NetworkFallbackResolver(
    private val errorBoundary: ErrorBoundary,
    private val sharedPreferences: SharedPreferences,
    private val statsigScope: CoroutineScope,
) {
    private var fallbackInfo: MutableMap<Endpoint, FallbackInfoEntry>? = null
    private val dnsQueryCooldowns: MutableMap<Endpoint, Long> = mutableMapOf()
    private val gson = StatsigUtil.getGson()
    private val dispatcherProvider = CoroutineDispatcherProvider()

    suspend fun tryBumpExpiryTime(sdkKey: String, urlConfig: UrlConfig) {
        val info = fallbackInfo?.get(urlConfig.endpoint) ?: return
        info.expiryTime = Date().time + DEFAULT_TTL_MS
        val updatedFallbackInfo = fallbackInfo?.toMutableMap()?.apply {
            this[urlConfig.endpoint] = info
        }
        tryWriteFallbackInfoToCache(updatedFallbackInfo)
    }

    fun initializeFallbackInfo() {
        fallbackInfo = readFallbackInfoFromCache()
    }

    fun getActiveFallbackUrlFromMemory(urlConfig: UrlConfig): String? {
        if (!(urlConfig.customUrl == null && urlConfig.userFallbackUrls == null)) return null
        val entry = fallbackInfo?.get(urlConfig.endpoint)
        if (entry == null || Date().time > (entry.expiryTime)) {
            fallbackInfo?.remove(urlConfig.endpoint)
            statsigScope.launch(dispatcherProvider.io) {
                tryWriteFallbackInfoToCache(fallbackInfo)
            }
            return null
        }

        return entry.url
    }

    suspend fun tryFetchUpdatedFallbackInfo(
        sdkKey: String,
        urlConfig: UrlConfig,
        errorMessage: String?,
        timedOut: Boolean,
        hasNetwork: Boolean,
    ): Boolean {
        return try {
            if (!isDomainFailure(errorMessage, timedOut, hasNetwork)) return false

            val canUseNetworkFallbacks = urlConfig.customUrl == null && urlConfig.userFallbackUrls == null

            val urls = if (canUseNetworkFallbacks) {
                tryFetchFallbackUrlsFromNetwork(urlConfig)
            } else {
                urlConfig.userFallbackUrls
            }

            val newUrl = pickNewFallbackUrl(fallbackInfo?.get(urlConfig.endpoint), urls) ?: return false

            updateFallbackInfoWithNewUrl(sdkKey, urlConfig.endpoint, newUrl)
            true
        } catch (error: Exception) {
            false
        }
    }

    private suspend fun updateFallbackInfoWithNewUrl(sdkKey: String, endpoint: Endpoint, newUrl: String) {
        val newFallbackInfo = FallbackInfoEntry(url = newUrl, expiryTime = Date().time + DEFAULT_TTL_MS)

        val previousInfo = fallbackInfo?.get(endpoint)
        previousInfo?.let { newFallbackInfo.previous.addAll(it.previous) }

        if (newFallbackInfo.previous.size > 10) newFallbackInfo.previous.clear()

        val previousUrl = fallbackInfo?.get(endpoint)?.url
        previousUrl?.let { newFallbackInfo.previous.add(it) }

        fallbackInfo = (fallbackInfo ?: mutableMapOf()).apply { put(endpoint, newFallbackInfo) }
        tryWriteFallbackInfoToCache(fallbackInfo)
    }

    private suspend fun tryFetchFallbackUrlsFromNetwork(urlConfig: UrlConfig): List<String>? {
        val cooldown = dnsQueryCooldowns[urlConfig.endpoint]
        if (cooldown != null && Date().time < cooldown) return null

        dnsQueryCooldowns[urlConfig.endpoint] = Date().time + COOLDOWN_TIME_MS

        val result = mutableListOf<String>()
        val records = fetchTxtRecords()
        val path = extractPathFromUrl(urlConfig.defaultUrl)

        for (record in records) {
            if (!record.startsWith("${urlConfig.endpointDnsKey}=")) continue

            val parts = record.split("=")
            if (parts.size > 1) {
                val baseUrl = parts[1].removeSuffix("/")
                result.add("https://$baseUrl$path")
            }
        }
        return result
    }

    suspend fun tryWriteFallbackInfoToCache(info: MutableMap<Endpoint, FallbackInfoEntry>?) {
        val hashKey = getFallbackInfoStorageKey()
        if (info.isNullOrEmpty()) {
            StatsigUtil.removeFromSharedPrefs(sharedPreferences, hashKey)
        } else {
            StatsigUtil.saveStringToSharedPrefs(sharedPreferences, hashKey, gson.toJson(info))
        }
    }

    fun readFallbackInfoFromCache(): MutableMap<Endpoint, FallbackInfoEntry>? {
        val hashKey = getFallbackInfoStorageKey()
        val data = StatsigUtil.syncGetFromSharedPrefs(sharedPreferences, hashKey) ?: return null
        return try {
            val mapType = object : TypeToken<MutableMap<Endpoint, FallbackInfoEntry>>() {}.type
            gson.fromJson(data, mapType)
        } catch (e: Exception) {
            null
        }
    }

    private fun pickNewFallbackUrl(
        currentFallbackInfo: FallbackInfoEntry?,
        urls: List<String>?,
    ): String? {
        if (urls == null) return null

        val previouslyUsed = currentFallbackInfo?.previous?.toSet() ?: emptySet()
        val currentFallbackUrl = currentFallbackInfo?.url

        for (loopUrl in urls) {
            val url = loopUrl.removeSuffix("/")
            if (url !in previouslyUsed && url != currentFallbackUrl) {
                return url
            }
        }
        return null
    }
}
fun getFallbackInfoStorageKey(): String {
    return "statsig.network_fallback"
}

fun isDomainFailure(errorMsg: String?, timedOut: Boolean, hasNetwork: Boolean): Boolean {
    if (!hasNetwork) return false
    return timedOut || errorMsg != null
}

fun extractPathFromUrl(urlString: String): String? {
    return try {
        URL(urlString).path
    } catch (e: Exception) {
        null
    }
}
