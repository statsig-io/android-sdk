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
    internal var events: MutableList<LogEvent> = ArrayList()

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
    }

    fun logGateExposure(gate: APIFeatureGate, user: StatsigUser?) {
        var event = LogEvent(GATE_EXPOSURE)
        event.user = user
        event.metadata =
            mapOf("gate" to gate.name, "gateValue" to gate.value.toString(), "ruleID" to gate.ruleID)
        event.secondaryExposures = gate.secondaryExposures
        this.log(event)
    }

    fun logConfigExposure(config: DynamicConfig, user: StatsigUser?) {
        var event = LogEvent(CONFIG_EXPOSURE)
        event.user = user
        event.metadata = mapOf("config" to config.getName(), "ruleID" to config.getRuleID())
        event.secondaryExposures = config.getSecondaryExposures()
        this.log(event)
    }
}
