package com.statsig.androidsdk

import com.google.gson.Gson
import java.util.concurrent.Executors
import android.content.SharedPreferences
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*

internal const val MAX_EVENTS: Int = 10
internal const val FLUSH_TIMER_MS: Long = 60000

internal const val CONFIG_EXPOSURE = "statsig::config_exposure"
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

    suspend fun log(event: LogEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)

            if (events.size >= MAX_EVENTS) {
                flush()
            }
        }
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
                                secondaryExposures: Array<Map<String, String>>, user: StatsigUser?) {
        withContext(singleThreadDispatcher) {
            var event = LogEvent(GATE_EXPOSURE)
            event.user = user
            event.metadata =
                mapOf(
                    "gate" to gateName,
                    "gateValue" to gateValue.toString(),
                    "ruleID" to ruleID
                )
            event.secondaryExposures = secondaryExposures
            log(event)
        }
    }

    suspend fun logConfigExposure(configName: String, ruleID: String, secondaryExposures: Array<Map<String, String>>,
                                  user: StatsigUser?) {
        withContext(singleThreadDispatcher) {
            var event = LogEvent(CONFIG_EXPOSURE)
            event.user = user
            event.metadata = mapOf("config" to configName, "ruleID" to ruleID)
            event.secondaryExposures = secondaryExposures
            log(event)
        }
    }

    suspend fun shutdown() {
        timer.cancel()
        flush()
        executor.shutdown()
    }
}
