package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork
    private var client: StatsigClient = StatsigClient()
    private val user: StatsigUser = StatsigUser("testUser")
    private val logEvents: MutableList<LogEventData> = mutableListOf()

    @Before
    fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        TestUtil.mockHashing()
        TestUtil.mockDispatchers()
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

    @Test
    fun testInitialize() {
        val options = StatsigOptions(
            api = "http://statsig.api",
            initializeValues = mapOf(),
            initTimeoutMs = 10000
        )
        runBlocking {
            client.initialize(app, "client-key", StatsigUser("test-user"), options)
            client.shutdown()
        }
        val optionsLoggingCopy: Map<String, Any> = Gson().fromJson(
            logEvents[0].events[0].metadata?.get("statsigOptions"),
            object : TypeToken<Map<String, Any>>() {}.type
        )
        assertEquals(optionsLoggingCopy["api"], "http://statsig.api")
        assertEquals(optionsLoggingCopy["initializeValues"], "SET")
        assertEquals(optionsLoggingCopy["initTimeoutMs"], 10000.0)
    }
}
