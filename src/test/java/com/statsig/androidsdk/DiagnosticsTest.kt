package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class DiagnosticsTest {
    private lateinit var app: Application
    private lateinit var sharedPrefs: TestSharedPreferences
    private lateinit var network: StatsigNetwork
    private var client: StatsigClient = StatsigClient()
    private val user: StatsigUser = StatsigUser("testUser")
    private val logEvents: MutableList<LogEventData> = mutableListOf()

    @Before
    fun setup() = runBlocking {
        app = mockk()
        sharedPrefs = TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtil()
        TestUtil.mockDispatchers()
        TestUtil.mockNetworkConnectivityService(app)
        client = StatsigClient()
        network = TestUtil.mockNetwork(onLog = {
            logEvents.add(it)
        })
        client.statsigNetwork = network
    }

    @Test
    fun testConcurrency() = runBlocking {
        client.initialize(app, "client-sdkKey", user)
        enforceDiagnosticsSample()
        val threads = arrayListOf<Thread>()
        val iterations = 3
        val threadSize = 3
        for (i in 1..threadSize) {
            val t = Thread {
                for (j in 1..iterations) {
                    runBlocking {
                        client.checkGate("always_on")
                    }
                }
            }
            threads.add(t)
        }
        for (t in threads) {
            t.start()
        }
        for (t in threads) {
            t.join()
        }
        client.shutdown()
        val markers = logEvents[0].events.find { it.eventName == "statsig::diagnostics" && it.metadata?.get("context") == "api_call" }
            ?.let { getMarkers(it) }
        assert(markers?.size == threadSize * iterations * 2)
    }

    private fun enforceDiagnosticsSample() {
        val diagnosticsField = client::class.java.getDeclaredField("diagnostics")
        diagnosticsField.isAccessible = true
        val diagnostics = diagnosticsField[client] as Diagnostics
        val maxMarkersField = diagnostics::class.java.getDeclaredField("maxMarkers")
        maxMarkersField.isAccessible = true
        maxMarkersField.set(diagnostics, mutableMapOf(ContextType.API_CALL to 30, ContextType.API_CALL to 30))
    }

    private fun getMarkers(log: LogEvent): List<Marker> {
        val listType = object : TypeToken<List<Marker>>() {}.type
        return Gson().fromJson(log.metadata?.get("markers") ?: "", listType)
    }
}
