package com.statsig.androidsdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val EXPOSURE_DEDUPE_INTERVAL: Long = 10 * 60 * 1000

internal const val MAX_EVENTS: Int = 50
internal const val FLUSH_TIMER_MS: Long = 60000

internal const val SHUTDOWN_WAIT_S: Long = 3

internal const val CONFIG_EXPOSURE = "statsig::config_exposure"
internal const val LAYER_EXPOSURE = "statsig::layer_exposure"
internal const val GATE_EXPOSURE = "statsig::gate_exposure"

internal data class LogEventData(
    @SerializedName("events") val events: ArrayList<LogEvent>,
    @SerializedName("statsigMetadata") val statsigMetadata: StatsigMetadata,
)

internal class StatsigLogger(
    coroutineScope: CoroutineScope,
    private val sdkKey: String,
    private val api: String,
    private val statsigMetadata: StatsigMetadata,
    private val statsigNetwork: StatsigNetwork
) {
    private val gson = Gson()

    private val executor = Executors.newSingleThreadExecutor();
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private val timer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }
    // Modify in a single thread only
    internal var events = arrayListOf<LogEvent>()

    private var loggedExposures: MutableMap<String, Long> = HashMap()

    suspend fun log(event: LogEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)

            if (events.size >= MAX_EVENTS) {
                flush()
            }
        }
    }

    fun onUpdateUser() {
        this.loggedExposures = HashMap()
    }

    suspend fun flush() {
        withContext(singleThreadDispatcher) {
            if (events.size == 0) {
                return@withContext
            }
            val flushEvents = events
            events = arrayListOf()
            statsigNetwork.apiPostLogs(api, sdkKey, gson.toJson(LogEventData(flushEvents, statsigMetadata)))
        }
    }

    suspend fun logGateExposure(gateName: String, gateValue: Boolean, ruleID: String,
                                secondaryExposures: Array<Map<String, String>>, user: StatsigUser?, details: EvaluationDetails) {
        val dedupeKey = gateName + gateValue + ruleID + details.reason.toString()
        if (shouldLogExposure(dedupeKey)) {
            withContext(singleThreadDispatcher) {
                var event = LogEvent(GATE_EXPOSURE)
                event.user = user
                event.metadata =
                    mapOf(
                        "gate" to gateName,
                        "gateValue" to gateValue.toString(),
                        "ruleID" to ruleID,
                        "reason" to details.reason.toString(),
                        "time" to details.time.toString()
                    )
                event.secondaryExposures = secondaryExposures
                log(event)
            }
        }
    }

    suspend fun logConfigExposure(configName: String, ruleID: String, secondaryExposures: Array<Map<String, String>>,
                                  user: StatsigUser?, details: EvaluationDetails) {
        val dedupeKey = configName + ruleID + details.reason.toString()

        if (shouldLogExposure(dedupeKey)) {
            withContext(singleThreadDispatcher) {
                var event = LogEvent(CONFIG_EXPOSURE)
                event.user = user
                event.metadata =
                    mutableMapOf(
                        "config" to configName,
                        "ruleID" to ruleID,
                        "reason" to details.reason.toString(),
                        "time" to details.time.toString()
                    )
                event.secondaryExposures = secondaryExposures
                log(event)
            }
        }
    }

    suspend fun logLayerExposure(
        configName: String,
        ruleID: String,
        secondaryExposures: Array<Map<String, String>>,
        user: StatsigUser?,
        allocatedExperiment: String,
        parameterName: String,
        isExplicitParameter: Boolean,
        details: EvaluationDetails) {
        val metadata = mutableMapOf(
            "config" to configName,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter.toString(),
            "reason" to details.reason.toString(),
            "time" to details.time.toString()
        )

        val dedupeKey = arrayOf(configName, ruleID, allocatedExperiment, parameterName, isExplicitParameter.toString(),
            details.reason.toString()).joinToString("|")
        if (shouldLogExposure(dedupeKey)) {
            withContext(singleThreadDispatcher) {
                val event = LogEvent(LAYER_EXPOSURE)
                event.user = user
                event.metadata = metadata
                event.secondaryExposures = secondaryExposures
                log(event)
            }
        }
    }

    suspend fun shutdown() {
        timer.cancel()
        flush()

        executor.shutdown()
        runCatching {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_S, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        }.onFailure {
            executor.shutdownNow()
        }
    }

    private fun shouldLogExposure(key: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = loggedExposures[key] ?: 0
        if (lastTime >= now - EXPOSURE_DEDUPE_INTERVAL) {
            return false
        }
        loggedExposures[key] = now
        return true
    }
}
