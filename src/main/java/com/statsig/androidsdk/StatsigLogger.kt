package com.statsig.androidsdk

import com.google.gson.Gson
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val MAX_EVENTS: Int = 500
internal const val FLUSH_TIMER_MS: Long = 60000

internal const val CONFIG_EXPOSURE = "statsig::config_exposure"
internal const val GATE_EXPOSURE = "statsig::gate_exposure"

private const val EVENTS = "events"
private const val STATSIG_METADATA = "statsigMetadata"

internal class StatsigLogger(
    private val sdkKey: String,
    private val api: String,
    private val statsigMetadata: StatsigMetadata,
    private val statsigNetwork: StatsigNetwork
) {
    private val gson = Gson()

    // Since these collections are not thread-safe, they will be modified in a single thread only
    internal var events = arrayListOf<LogEvent>()

    suspend fun log(event: LogEvent) {
        withContext(Dispatchers.Main.immediate) { // Run on main thread if not already in it
            events.add(event)

            if (events.size >= MAX_EVENTS) {
                flush()
            }

            if (events.size == 1) {
                delay(FLUSH_TIMER_MS)
                flush()
            }
        }
    }

    suspend fun flush() {
        withContext(Dispatchers.Main.immediate) {
            if (events.size == 0) {
                return@withContext
            }
            val flushEvents = events
            events = arrayListOf()

            val body = mapOf(EVENTS to flushEvents, STATSIG_METADATA to statsigMetadata)
            statsigNetwork.apiPostLogs(api, sdkKey, gson.toJson(body))
        }
    }

    suspend fun logGateExposure(gateName: String, gateValue: Boolean, ruleID: String,
                                secondaryExposures: Array<Map<String, String>>, user: StatsigUser?) {
        withContext(Dispatchers.Main.immediate) {
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
        withContext(Dispatchers.Main.immediate) {
            var event = LogEvent(CONFIG_EXPOSURE)
            event.user = user
            event.metadata = mapOf("config" to configName, "ruleID" to ruleID)
            event.secondaryExposures = secondaryExposures
            log(event)
        }
    }
}
