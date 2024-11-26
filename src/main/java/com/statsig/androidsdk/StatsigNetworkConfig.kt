package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

enum class Endpoint(val value: String) {
    @SerializedName("log_event")
    Rgstr("log_event"),

    @SerializedName("initialize")
    Initialize("initialize"), ;

    override fun toString(): String {
        return value
    }
}

typealias EndpointDnsKey = String // 'i' | 'e' | 'd'

val ENDPOINT_DNS_KEY_MAP: Map<Endpoint, EndpointDnsKey> = mapOf(
    Endpoint.Initialize to "i",
    Endpoint.Rgstr to "e",
)

val NetworkDefault: Map<Endpoint, String> = mapOf(
    Endpoint.Initialize to DEFAULT_INIT_API,
    Endpoint.Rgstr to DEFAULT_EVENT_API,
)

class UrlConfig(
    val endpoint: Endpoint,
    inputApi: String? = null,
    var userFallbackUrls: List<String>? = null,
) {
    val endpointDnsKey: EndpointDnsKey = ENDPOINT_DNS_KEY_MAP[endpoint] ?: ""
    var defaultUrl: String
    var customUrl: String? = null
    var statsigFallbackUrl: String? = null
    var fallbackUrl: String? = null

    init {
        val defaultApi = NetworkDefault[endpoint]
        defaultUrl = "$defaultApi${endpoint.value}"

        if (customUrl == null && inputApi != null) {
            val inputUrl = "${inputApi.trimEnd('/')}/${endpoint.value}"
            if (inputUrl != defaultUrl) {
                customUrl = inputUrl
            }
        }
    }

    fun getUrl(): String {
        return customUrl ?: defaultUrl
    }
}
