package com.statsig.androidsdk

import com.google.gson.Gson
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val MAX_EVENTS: Int = 500
const val FLUSH_TIMER_MS: Long = 60000

const val CONFIG_EXPOSURE = "statsig::config_exposure"
const val GATE_EXPOSURE = "statsig::gate_exposure"

private const val EVENTS = "events"
private const val STATSIG_METADATA = "statsigMetadata"

private const val GATE = "gate"
private const val GATE_VALUE = "gateValue"
private const val RULE_ID = "ruleID"

private const val CONFIG = "config"

internal class StatsigLogger(
    private val sdkKey: String,
    private val api: String,
    private val statsigMetadata: StatsigMetadata,
    private val sharedPrefs: SharedPreferences,
    private val statsigNetwork: StatsigNetwork
) {

    private val gson = Gson()

    // Since these collections are not thread-safe, they will be modified in a single thread only
    private var events = arrayListOf<LogEvent>()
    private var gateExposures = hashSetOf<String>()
    private var configExposures = hashSetOf<String>()

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
            statsigNetwork.apiPostLogs(api, sdkKey, gson.toJson(body), sharedPrefs)
        }
    }

    suspend fun onUpdateUser() {
        withContext(Dispatchers.Main.immediate) {
            flush()
            configExposures = hashSetOf()
            gateExposures = hashSetOf()
        }
    }

    suspend fun logGateExposure(gateName: String, value: Boolean, ruleID: String, user: StatsigUser?) {
        withContext(Dispatchers.Main.immediate) {
            if (gateExposures.contains(gateName)) {
                return@withContext
            }
            gateExposures.add(gateName)
            val event = LogEvent(GATE_EXPOSURE)
            event.user = user
            event.metadata =
                mapOf(GATE to gateName, GATE_VALUE to value.toString(), RULE_ID to ruleID)
            log(event)
        }
    }

    suspend fun logConfigExposure(configName: String, ruleID: String, user: StatsigUser?) {
        withContext(Dispatchers.Main.immediate) {
            if (configExposures.contains(configName)) {
                return@withContext
            }
            configExposures.add(configName)
            val event = LogEvent(CONFIG_EXPOSURE)
            event.user = user
            event.metadata = mapOf(CONFIG to configName, RULE_ID to ruleID)
            log(event)
        }
    }
}
