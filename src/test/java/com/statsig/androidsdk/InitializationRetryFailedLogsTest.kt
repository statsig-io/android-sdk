package com.statsig.androidsdk

import android.app.Application
import android.util.Base64
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

class InitializationRetryFailedLogsTest {
    private lateinit var mockWebServer: MockWebServer
    private var logEventHits = 0
    private val gson = Gson()
    private val app: Application = mockk()

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        mockkStatic(Base64::class)

        val arraySlot = slot<ByteArray>()
        every {
            Base64.encodeToString(capture(arraySlot), Base64.NO_WRAP)
        } answers {
            java.util.Base64.getEncoder().encodeToString(arraySlot.captured)
        }
    }

    @Test
    fun testStoredFailedLogsDoNotBlockInitialization() = runBlocking {
        val url = mockWebServer.url("/v1").toString()
        val sharedPrefs = TestUtil.stubAppFunctions(app)

        sharedPrefs.edit().clear().commit()

        val failedLogsJson = this::class.java.classLoader!!
            .getResource("sample_failed_logs.json")
            .readText()

        sharedPrefs.edit()
            .putString("StatsigNetwork.OFFLINE_LOGS:client-key", failedLogsJson)
            .commit()

        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path!!.contains("initialize") -> {
                        MockResponse()
                            .setBody(gson.toJson(TestUtil.makeInitializeResponse()))
                            .setResponseCode(200)
                    }
                    request.path!!.contains("log_event") -> {
                        logEventHits++
                        MockResponse().setResponseCode(200)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        mockWebServer.dispatcher = dispatcher

        Statsig.initialize(
            app,
            "client-key",
            StatsigUser("test"),
            StatsigOptions(api = url, eventLoggingAPI = url),
        )
        assert(logEventHits == 0)

        val gateResult = Statsig.checkGate("test_gate")

        Statsig.shutdown()

        assert(!gateResult)
        assert(logEventHits > 1) // SDK retried saved logs, shutdown should trigger one as well
    }
}
