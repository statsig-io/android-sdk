package com.statsig.androidsdk

import com.google.gson.Gson
import android.content.SharedPreferences
import kotlinx.coroutines.*

const val MAX_EVENTS: Int = 500
const val FLUSH_TIMER_MS: Long = 60000

const val CONFIG_EXPOSURE = "statsig::config_exposure"
const val GATE_EXPOSURE = "statsig::gate_exposure"

class StatsigLogger(
    private val sdkKey: String,
    private val api: String,
    private val statsigMetadata: StatsigMetadata,
    private val sharedPrefs: SharedPreferences?,
) {
    private var events: MutableList<LogEvent> = ArrayList()
    private var gateExposures: MutableSet<String> = HashSet()
    private var configExposures: MutableSet<String> = HashSet()

    fun log(event: LogEvent) {
        this.events.add(event)

        if (this.events.size >= MAX_EVENTS) {
            this.flush()
        }

        if (this.events.size == 1) {
            val logger = this
            GlobalScope.launch {
                delay(FLUSH_TIMER_MS)
                logger.flush()
            }
        }
    }

    @Synchronized
    fun flush() {
        if (events.size == 0) {
            return
        }
        val flushEvents: MutableList<LogEvent> = ArrayList(this.events.size)
        flushEvents.addAll(this.events)
        this.events = ArrayList()

        val body = mapOf("events" to flushEvents, "statsigMetadata" to this.statsigMetadata)
        StatsigNetwork.apiPostLogs(this.api, sdkKey, Gson().toJson(body), this.sharedPrefs)
    }

    fun onUpdateUser() {
        this.flush()
        this.configExposures = HashSet()
        this.gateExposures = HashSet()
    }

    fun logGateExposure(gateName: String, value: Boolean, ruleID: String, user: StatsigUser?) {
        if (gateExposures.contains(gateName)) {
            return;
        }
        gateExposures.add(gateName)
        var event = LogEvent(GATE_EXPOSURE)
        event.user = user
        event.metadata =
            mapOf("gate" to gateName, "gateValue" to value.toString(), "ruleID" to ruleID)
        this.log(event)
    }

    fun logConfigExposure(configName: String, ruleID: String, user: StatsigUser?) {
        if (configExposures.contains(configName)) {
            return;
        }
        configExposures.add(configName)
        var event = LogEvent(CONFIG_EXPOSURE)
        event.user = user
        event.metadata = mapOf("config" to configName, "ruleID" to ruleID)
        this.log(event)
    }
}
