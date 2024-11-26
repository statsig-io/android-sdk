package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val EXPOSURE_DEDUPE_INTERVAL: Long = 10 * 60 * 1000

internal const val MAX_EVENTS: Int = 50
internal const val FLUSH_TIMER_MS: Long = 60000

internal const val SHUTDOWN_WAIT_S: Long = 3

internal const val CONFIG_EXPOSURE = "statsig::config_exposure"
internal const val LAYER_EXPOSURE = "statsig::layer_exposure"
internal const val GATE_EXPOSURE = "statsig::gate_exposure"
internal const val DIAGNOSTICS_EVENT = "statsig::diagnostics"
internal const val NON_EXPOSED_CHECKS_EVENT = "statsig::non_exposed_checks"

internal data class LogEventData(
    @SerializedName("events") val events: ArrayList<LogEvent>,
    @SerializedName("statsigMetadata") val statsigMetadata: StatsigMetadata,
)

internal class StatsigLogger(
    private val coroutineScope: CoroutineScope,
    private val sdkKey: String,
    private val api: String,
    private val statsigMetadata: StatsigMetadata,
    private val statsigNetwork: StatsigNetwork,
    private val statsigUser: StatsigUser,
    private val diagnostics: Diagnostics,
    private val fallbackUrls: List<String>? = null,
) {
    private val gson = StatsigUtil.getGson()

    private val executor = Executors.newSingleThreadExecutor()
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private val timer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }

    private var events = ConcurrentLinkedQueue<LogEvent>()
    private var loggedExposures = ConcurrentHashMap<String, Long>()
    private var nonExposedChecks = ConcurrentHashMap<String, Long>()
    suspend fun log(event: LogEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)

            if (events.size >= MAX_EVENTS) {
                flush()
            }
        }
    }

    fun onUpdateUser() {
        this.loggedExposures = ConcurrentHashMap()
        diagnostics.clearContext(ContextType.API_CALL)
    }

    suspend fun flush() {
        withContext(singleThreadDispatcher) {
            addErrorBoundaryDiagnostics()
            addNonExposedChecksEvent()
            if (events.size == 0) {
                return@withContext
            }
            val eventsCount = events.size.toString()
            val flushEvents = ArrayList(events)
            events = ConcurrentLinkedQueue()
            statsigNetwork.apiPostLogs(api, gson.toJson(LogEventData(flushEvents, statsigMetadata)), eventsCount, fallbackUrls)
        }
    }

    fun logExposure(name: String, gate: FeatureGate, user: StatsigUser, isManual: Boolean) {
        val dedupeKey = name + gate.getValue() + gate.getRuleID() + gate.getEvaluationDetails().reason.toString()
        if (!shouldLogExposure(dedupeKey)) {
            return
        }

        coroutineScope.launch(singleThreadDispatcher) {
            val event = LogEvent(GATE_EXPOSURE)
            event.user = user

            val metadata = mutableMapOf(
                "gate" to name,
                "gateValue" to gate.getValue().toString(),
                "ruleID" to gate.getRuleID(),
                "reason" to gate.getEvaluationDetails().reason.toString(),
                "time" to gate.getEvaluationDetails().time.toString(),
            )
            addManualFlag(metadata, isManual)

            event.metadata = metadata
            event.secondaryExposures = gate.getSecondaryExposures()
            log(event)
        }
    }

    fun logExposure(name: String, config: DynamicConfig, user: StatsigUser, isManual: Boolean) {
        val dedupeKey = name + config.getRuleID() + config.getEvaluationDetails().reason.toString()
        if (!shouldLogExposure(dedupeKey)) {
            return
        }

        coroutineScope.launch(singleThreadDispatcher) {
            val event = LogEvent(CONFIG_EXPOSURE)
            event.user = user
            val metadata = mutableMapOf(
                "config" to name,
                "ruleID" to config.getRuleID(),
                "reason" to config.getEvaluationDetails().reason.toString(),
                "time" to config.getEvaluationDetails().time.toString(),
            )
            addManualFlag(metadata, isManual)

            event.metadata = metadata
            event.secondaryExposures = config.getSecondaryExposures()

            log(event)
        }
    }

    fun logLayerExposure(
        configName: String,
        ruleID: String,
        secondaryExposures: Array<Map<String, String>>,
        user: StatsigUser?,
        allocatedExperiment: String,
        parameterName: String,
        isExplicitParameter: Boolean,
        details: EvaluationDetails,
        isManual: Boolean,
    ) {
        val metadata = mutableMapOf(
            "config" to configName,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter.toString(),
            "reason" to details.reason.toString(),
            "time" to details.time.toString(),
        )
        addManualFlag(metadata, isManual)

        val dedupeKey = arrayOf(
            configName,
            ruleID,
            allocatedExperiment,
            parameterName,
            isExplicitParameter.toString(),
            details.reason.toString(),
        ).joinToString("|")
        if (!shouldLogExposure(dedupeKey)) {
            return
        }

        coroutineScope.launch(singleThreadDispatcher) {
            val event = LogEvent(LAYER_EXPOSURE)
            event.user = user
            event.metadata = metadata
            event.secondaryExposures = secondaryExposures
            log(event)
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

    /*
     * Diagnostics
     * */
    fun logDiagnostics(overrideContext: ContextType? = null) {
        val context = overrideContext ?: diagnostics.diagnosticsContext
        val markers = diagnostics.getMarkers(context)
        if (markers.isEmpty()) {
            return
        }
        val event = this.makeDiagnosticsEvent(context, markers)
        coroutineScope.launch(singleThreadDispatcher) { log(event) }
        diagnostics.clearContext()
    }

    private fun addManualFlag(metadata: MutableMap<String, String>, isManual: Boolean): MutableMap<String, String> {
        if (isManual) {
            metadata["isManualExposure"] = "true"
        }
        return metadata
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

    fun addNonExposedCheck(configName: String) {
        val count = nonExposedChecks[configName] ?: 0
        nonExposedChecks[configName] = count + 1
    }

    private fun makeDiagnosticsEvent(context: ContextType, markers: Collection<Marker>): LogEvent {
        // Need to verify if the JSON is in the right format for log event
        val event = LogEvent(DIAGNOSTICS_EVENT)
        event.user = this.statsigUser
        event.metadata = mapOf("context" to context.toString().lowercase(), "markers" to gson.toJson(markers))
        return event
    }

    private fun addNonExposedChecksEvent() {
        if (nonExposedChecks.isEmpty()) {
            return
        }
        val event = LogEvent(NON_EXPOSED_CHECKS_EVENT)
        event.metadata = mapOf("checks" to gson.toJson(nonExposedChecks))
        this.events.add(event)
        nonExposedChecks.clear()
    }

    private fun addErrorBoundaryDiagnostics() {
        val markers = diagnostics.getMarkers(ContextType.API_CALL)
        if (markers.isEmpty()) {
            return
        }
        val event = this.makeDiagnosticsEvent(ContextType.API_CALL, markers)
        this.events.add(event)
        diagnostics.clearContext(ContextType.API_CALL)
    }
}
