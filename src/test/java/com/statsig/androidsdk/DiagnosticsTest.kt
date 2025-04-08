package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before

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

    private fun enforceDiagnosticsSample() {
        val diagnosticsField = client::class.java.getDeclaredField("diagnostics")
        diagnosticsField.isAccessible = true
        val diagnostics = diagnosticsField[client] as Diagnostics
        val maxMarkersField = diagnostics::class.java.getDeclaredField("maxMarkers")
        maxMarkersField.isAccessible = true
    }

    private fun getMarkers(log: LogEvent): List<Marker> {
        val listType = object : TypeToken<List<Marker>>() {}.type
        return Gson().fromJson(log.metadata?.get("markers") ?: "", listType)
    }
}
