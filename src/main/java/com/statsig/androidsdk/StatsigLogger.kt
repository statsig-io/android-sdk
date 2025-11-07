package com.statsig.androidsdk

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlinx.coroutines.*

private const val EXPOSURE_DEDUPE_INTERVAL: Long = 10 * 60 * 1000

internal const val MAX_EVENTS_BEFORE_FLUSH_ATTEMPT: Int = 50
internal const val MAX_EVENT_BUFFER_SIZE: Int = 1000
internal const val FLUSH_TIMER_MS: Long = 60000

internal const val SHUTDOWN_WAIT_S: Long = 3

internal const val CONFIG_EXPOSURE = "statsig::config_exposure"
internal const val LAYER_EXPOSURE = "statsig::layer_exposure"
internal const val GATE_EXPOSURE = "statsig::gate_exposure"
internal const val DIAGNOSTICS_EVENT = "statsig::diagnostics"
internal const val NON_EXPOSED_CHECKS_EVENT = "statsig::non_exposed_checks"

internal data class LogEventData(
    @SerializedName("events") val events: ArrayList<LogEvent>,
    @SerializedName("statsigMetadata") val statsigMetadata: StatsigMetadata
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
    private var loggingEnabled: Boolean,
    private val gson: Gson
) {
    companion object {
        private const val TAG: String = "statsig::StatsigLogger"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private val coroutineDispatcherProvider = CoroutineDispatcherProvider()
    private val timer = coroutineScope.launch(coroutineDispatcherProvider.io) {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }
    private var events = ConcurrentLinkedQueue<LogEvent>()
    private val loggedExposures = ConcurrentHashMap<ExposureKey, Long>()
    private var nonExposedChecks = ConcurrentHashMap<String, Long>()

    suspend fun log(event: LogEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)
            val size = events.size
            if (size > MAX_EVENT_BUFFER_SIZE) {
                // Drop the oldest events
                repeat(size - MAX_EVENT_BUFFER_SIZE, { events.poll() })
            }
            if (size >= MAX_EVENTS_BEFORE_FLUSH_ATTEMPT) {
                flush()
            }
        }
    }

    fun onUpdateUser() {
        this.loggedExposures.clear()
    }

    suspend fun flush() {
        withContext(singleThreadDispatcher) {
            addNonExposedChecksEvent()
            if (events.size == 0) {
                return@withContext
            }
            if (!loggingEnabled) {
                Log.d(TAG, "loggingEnabled is FALSE, flush() skipped")
                return@withContext
            }
            val eventsCount = events.size.toString()
            val flushEvents = ArrayList(events)
            events = ConcurrentLinkedQueue()
            statsigNetwork.apiPostLogs(
                api,
                gson.toJson(LogEventData(flushEvents, statsigMetadata)),
                eventsCount,
                fallbackUrls
            )
            Log.v(TAG, "flush() completed with $eventsCount events")
        }
    }

    fun logExposure(name: String, gate: FeatureGate, user: StatsigUser, isManual: Boolean) {
        val key = ExposureKey.Gate(
            name = name,
            value = gate.getValue(),
            ruleID = gate.getRuleID(),
            reason = gate.getEvaluationDetails().reason
        )
        if (!shouldLogExposure(key)) {
            Log.v(TAG, "logExposure() skipped due to recent exposure to same gate")
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
                "time" to gate.getEvaluationDetails().time.toString()
            )
            addManualFlag(metadata, isManual)

            event.metadata = metadata
            event.secondaryExposures = gate.getSecondaryExposures()
            log(event)
        }
    }

    fun logExposure(name: String, config: DynamicConfig, user: StatsigUser, isManual: Boolean) {
        val key = ExposureKey.Config(
            name,
            config.getRuleID(),
            config.getEvaluationDetails().reason
        )
        if (!shouldLogExposure(key)) return

        coroutineScope.launch(singleThreadDispatcher) {
            val event = LogEvent(CONFIG_EXPOSURE)
            event.user = user
            val metadata = mutableMapOf(
                "config" to name,
                "ruleID" to config.getRuleID(),
                "reason" to config.getEvaluationDetails().reason.toString(),
                "time" to config.getEvaluationDetails().time.toString()
            )

            config.getRulePassed()?.let {
                metadata["rulePassed"] = it.toString()
            }

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
        isManual: Boolean
    ) {
        val key = ExposureKey.Layer(
            configName = configName,
            ruleID = ruleID,
            allocatedExperiment = allocatedExperiment,
            parameterName = parameterName,
            isExplicitParameter = isExplicitParameter,
            reason = details.reason
        )
        if (!shouldLogExposure(key)) return
        val metadata = mutableMapOf(
            "config" to configName,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter.toString(),
            "reason" to details.reason.toString(),
            "time" to details.time.toString()
        )
        addManualFlag(metadata, isManual)

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
        val optionsLoggingCopy = if (context ==
            ContextType.INITIALIZE
        ) {
            diagnostics.statsigOptionsLoggingCopy
        } else {
            null
        }
        val event = this.makeDiagnosticsEvent(context, markers, optionsLoggingCopy)
        coroutineScope.launch(singleThreadDispatcher) { log(event) }
        diagnostics.clearContext()
    }

    fun setLoggingEnabled(loggingEnabled: Boolean) {
        this.loggingEnabled = loggingEnabled
    }

    private fun addManualFlag(
        metadata: MutableMap<String, String>,
        isManual: Boolean
    ): MutableMap<String, String> {
        if (isManual) {
            metadata["isManualExposure"] = "true"
        }
        return metadata
    }

    private fun shouldLogExposure(key: ExposureKey): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = loggedExposures[key]

        return if (lastTime != null && now - lastTime < EXPOSURE_DEDUPE_INTERVAL) {
            false
        } else {
            loggedExposures[key] = now
            true
        }
    }

    fun addNonExposedCheck(configName: String) {
        val count = nonExposedChecks[configName] ?: 0
        nonExposedChecks[configName] = count + 1
    }

    private fun makeDiagnosticsEvent(
        context: ContextType,
        markers: Collection<Marker>,
        statsigOptions: Map<String, Any?>?
    ): LogEvent {
        // Need to verify if the JSON is in the right format for log event
        val event = LogEvent(DIAGNOSTICS_EVENT)
        event.user = this.statsigUser
        event.metadata =
            mapOf(
                "context" to context.toString().lowercase(),
                "markers" to gson.toJson(markers),
                "statsigOptions" to gson.toJson(statsigOptions)
            )
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
}
