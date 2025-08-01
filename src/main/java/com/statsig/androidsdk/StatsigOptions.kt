package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

enum class Tier {
    PRODUCTION,
    STAGING,
    DEVELOPMENT,
}

private const val TIER_KEY: String = "tier"
const val DEFAULT_INIT_API = "https://featureassets.org/v1/"
const val DEFAULT_EVENT_API = "https://prodregistryv2.org/v1/"

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 * @property disableCurrentActivityLogging prevents the SDK from auto logging the current, top-level
 * activity for event logs
 * @property initTimeoutMs the amount of time to wait for an initialize() response from the server
 * NOTE: gates/configs will still be fetched in the background if this time is exceeded, but the
 * callback to initialize will fire after, at most, the time specified
 */
class StatsigOptions(
    /**
     The endpoint to use for initialize statsig SDK. You should not need to override this
     unless you implement a proxy
     */
    @SerializedName("api") var api: String = DEFAULT_INIT_API,

    /**
     The endpoint to use for logging events. Default is "https://api.statsig.com/v1".
     The SDK will hit different endpoints for initialize
     to evaluate gates and for logEvent to log event data. The api option controls the evaluation
     endpoint, and eventLoggingApi controls the event logging endpoint.
     */
    @SerializedName("eventLoggingAPI") var eventLoggingAPI: String = DEFAULT_EVENT_API,
    /**
     * By default, any custom event your application logs with Statsig.logEvent() includes the current
     * root View Controller. This is so we can generate user journey funnels for your users. You can
     * set this parameter to true to disable this behavior.
     */
    @SerializedName("disableCurrentActivityLogging") var disableCurrentActivityLogging: Boolean = false,
    /**
     * Prevent the SDK from sending useful debug information to Statsig
     */
    @SerializedName("disableDiagnosticsLogging") var disableDiagnosticsLogging: Boolean = false,
    /**
     * Default off, SDK compress outgoing loge_event payload, if there is no proxying.
     * Turn this on to disable compressing log_event payload
     */
    @SerializedName("disableLoggingCompression") var disableLoggingCompression: Boolean = false,
    /**
     * Used to decide how long the Statsig client waits for the initial network request to respond
     * before calling the completion block. The Statsig client will return either cached values
     * (if any) or default values if checkGate/getConfig/getExperiment is called before the initial
     * network request completes
     * if you always want to wait for the latest values fetched from Statsig server, you should set this to 0 so we do not timeout the network request.
     */
    @SerializedName("initTimeoutMs") var initTimeoutMs: Long = 5000L,
    /**
     * Used to decide how many retries sdk attempt before successfully initialized.
     * Default is 0, sdk does not retry, if set initRetryLimit = 2, SDK will hit initialization
     * endpoint 3 times = 1 + 2 retries. Retry is applied with exponential backoff
     * Timeout should be independent initTimeoutMs will control network step including retries.
     */
    @SerializedName("initRetryLimit") var initRetryLimit: Int = 0,
    /**
     * By default, feature values for a user are fetched once during Statsig.start and don't change
     * throughout the session. Setting this value to true will make Statsig periodically fetch updated
     * values for the current user.
     */
    @SerializedName("enableAutoValueUpdate") var enableAutoValueUpdate: Boolean = false,
    /**
     * Only applies if enableAutoValueUpdate is true. Controls how frequently calls to refresh the current
     * users values are made. Time is in minutes and defaults to 1 minute. Minimum value is 1 minute.
     */
    @SerializedName("autoValueUpdateIntervalMinutes") var autoValueUpdateIntervalMinutes: Double = 1.0,
    /**
     * overrides the stableID in the SDK that is set for the user
     */
    @SerializedName("overrideStableID") var overrideStableID: String? = null,
    /**
     * Whether or not the SDK should block on loading saved values from disk. By default, we block on
     * loading from disk, so we guarantee cache will be loaded when sdk is being used.
     */
    @SerializedName("loadCacheAsync") var loadCacheAsync: Boolean = false,
    /**
     * Provide a Dictionary representing the "initiailize response" required  to synchronously initialize the SDK.
     * This value can be obtained from a Statsig server SDK.
     */
    @SerializedName("initializeValues") var initializeValues: Map<String, Any>? = null,
    /**
     By default, initialize send Network request and use network response for evaluation.
     * Setting this value to true will initialize without sending network request, and initialize
     * SDK from cache values for user.
     * */
    @SerializedName("initializeOffline") var initializeOffline: Boolean = false,
    /**
     * When disabled, the SDK will not hash gate/config/experiment names, instead they will be readable as plain text.
     * Note: This requires special authorization from Statsig
     */
    @SerializedName("disableHashing") var disableHashing: Boolean? = false,
    /**
     * Callback function when user is being set (with updateUser/initialize)  to validate user object
     * If user object is modified, modified version will be used for evaluation and logging
     */
    @SerializedName("userObjectValidator") var userObjectValidator: ((user: StatsigUser) -> Unit)? = null,
    /**
     * A list of URLs to try if the primary initializeUrl fails.
     */
    @SerializedName("initializeFallbackUrls") var initializeFallbackUrls: List<String>? = null,
    /**
     * A list of URLs to try if the primary logEventUrl fails.
     */
    @SerializedName("logEventFallbackUrls") var logEventFallbackUrls: List<String>? = null,

    var evaluationCallback: ((BaseConfig) -> Unit)? = null,

    var customCacheKey: ((String, StatsigUser) -> String) = { sdkKey, user -> "${user.getCacheKey()}:$sdkKey" },

    var disableLogEventRetries: Boolean = false,

    var optOutNonSdkMetadata: Boolean = false,

    var onDeviceEvalAdapter: OnDeviceEvalAdapter? = null,
) {

    private var environment: MutableMap<String, String>? = null

    fun setTier(tier: Tier) {
        setEnvironmentParameter(TIER_KEY, tier.toString().lowercase())
    }

    fun setEnvironmentParameter(key: String, value: String) {
        val env = environment
        if (env == null) {
            environment = mutableMapOf(key to value)
            return
        }

        env[key] = value
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }

    internal fun toMap(): Map<String, Any?> {
        return mapOf(
            "api" to api,
            "disableCurrentActivityLogging" to disableCurrentActivityLogging,
            "disableDiagnosticsLogging" to disableDiagnosticsLogging,
            "initTimeoutMs" to initTimeoutMs,
            "enableAutoValueUpdate" to enableAutoValueUpdate,
            "autoValueUpdateIntervalMinutes" to autoValueUpdateIntervalMinutes,
            "overrideStableID" to overrideStableID,
            "loadCacheAsync" to loadCacheAsync,
            "initializeValues" to initializeValues,
            "disableHashing" to disableHashing,
            "environment" to environment,
        )
    }

    internal fun getLoggingCopy(): Map<String, Any?> {
        return mapOf(
            "api" to api,
            "eventLoggingApi" to eventLoggingAPI,
            "disableCurrentActivityLogging" to disableCurrentActivityLogging,
            "disableDiagnosticsLogging" to disableDiagnosticsLogging,
            "initTimeoutMs" to initTimeoutMs,
            "enableAutoValueUpdate" to enableAutoValueUpdate,
            "autoValueUpdateIntervalMinutes" to autoValueUpdateIntervalMinutes,
            "overrideStableID" to overrideStableID,
            "loadCacheAsync" to loadCacheAsync,
            "initializeValues" to "SET",
            "disableHashing" to disableHashing,
            "environment" to environment,
            "initializeFallbackUrls" to initializeFallbackUrls,
            "logEventFallbackUrls" to logEventFallbackUrls,
            "disableLogEventRetries" to disableLogEventRetries,
            "initRetryLimit" to initRetryLimit,
            "initializeOffline" to initializeOffline,
        )
    }
}
