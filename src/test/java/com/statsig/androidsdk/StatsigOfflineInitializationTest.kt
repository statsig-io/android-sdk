package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

class StatsigOfflineInitializationTest {
    private lateinit var app: Application
    private lateinit var initializeCountdown: CountDownLatch

    private val logs = mutableListOf<LogEventData>()
    private var initializeNetworkCalled = 0
    private val user = StatsigUser("123")
    private val gson = StatsigUtil.getGson()

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = mockk()
        TestUtil.stubAppFunctions(app)
        mockkObject(Hashing)
        mockkObject(StatsigUtil)
        every {
            Hashing.getHashedString(any(), null)
        } answers {
            firstArg<String>() + "!"
        }
        initializeCountdown = CountDownLatch(1)
        // Initialize and get response from network first so cache has most recent value
        TestUtil.startStatsigAndWait(app, user, network = TestUtil.mockNetwork())
        Statsig.shutdown()
        Statsig.client.statsigNetwork = mockNetwork()
    }

    @Test
    fun testInitializeOffline() = runBlocking {
        // Initialize async offline
        Statsig.client.initializeAsync(app, "client-apikey", user, options = StatsigOptions(initializeOffline = true), callback = InitializeCallback(initializeCountdown))
        initializeCountdown.await()
        var config = Statsig.client.getConfig("test_config")
        assert(config.getEvaluationDetails().reason === EvaluationReason.Cache)
        assert(config.getString("string", "DEFAULT") == "test")
        assert(initializeNetworkCalled === 0)
        Statsig.shutdown()
        // Initialize offline
        Statsig.client.statsigNetwork = mockNetwork()
        Statsig.client.initialize(app, "client-apikey", user, StatsigOptions(initializeOffline = true))
        config = Statsig.client.getConfig("test_config")
        assert(config.getEvaluationDetails().reason === EvaluationReason.Cache)
        assert(config.getString("string", "DEFAULT") == "test")
        assert(initializeNetworkCalled === 0)
        Statsig.shutdown()
        assert(logs.size == 2)
        assert(logs[0].events[0].eventName == "statsig::diagnostics")
        var diagnosticMarkers = (gson.fromJson(logs[0].events[0].metadata!!["markers"], Collection::class.java)).map {
            gson.fromJson(gson.toJson(it), Marker::class.java)
        }
        println(diagnosticMarkers.size)
        assert(diagnosticMarkers.size == 4)
        assert(diagnosticMarkers[0].key == KeyType.OVERALL)
        assert(diagnosticMarkers[0].action == ActionType.START)
        assert(diagnosticMarkers[3].key == KeyType.OVERALL)
        assert(diagnosticMarkers[3].action == ActionType.END)
        assert(diagnosticMarkers[3].success == true)

        diagnosticMarkers = (gson.fromJson(logs[1].events[0].metadata!!["markers"], Collection::class.java)).map {
            gson.fromJson(gson.toJson(it), Marker::class.java)
        }
        println(diagnosticMarkers.size)
        assert(diagnosticMarkers.size == 4)
        assert(diagnosticMarkers[0].key == KeyType.OVERALL)
        assert(diagnosticMarkers[0].action == ActionType.START)
        assert(diagnosticMarkers[3].key == KeyType.OVERALL)
        assert(diagnosticMarkers[3].action == ActionType.END)
        assert(diagnosticMarkers[3].success == true)
    }

    @Test
    fun testInitializeOfflineAndUpdateUser() = runBlocking {
        // Initialize async offline
        Statsig.client.initializeAsync(app, "client-apikey", user, options = StatsigOptions(initializeOffline = true), callback = InitializeCallback(initializeCountdown))
        initializeCountdown.await()
        var config = Statsig.client.getConfig("test_config")
        assert(config.getEvaluationDetails().reason === EvaluationReason.Cache)
        assert(config.getString("string", "DEFAULT") == "test")
        assert(initializeNetworkCalled === 0)
        user.email = "abc@gmail.com"
        Statsig.client.updateUser(user)
        config = Statsig.client.getConfig("test_config")
        assert(config.getEvaluationDetails().reason === EvaluationReason.Network)
        assert(config.getString("string", "DEFAULT") == "test")

        // From UpdateUser
        assert(initializeNetworkCalled === 1)
    }

    private fun mockNetwork(): StatsigNetwork {
        val statsigNetwork = mockk<StatsigNetwork>()
        coEvery {
            statsigNetwork.apiRetryFailedLogs(any())
        } answers {}

        coEvery {
            statsigNetwork.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            initializeNetworkCalled++
            TestUtil.makeInitializeResponse()
        }

        coEvery {
            statsigNetwork.addFailedLogRequest(any())
        } coAnswers {}

        coEvery {
            statsigNetwork.apiPostLogs(any(), any(), any())
        } answers {
            logs.add(gson.fromJson(secondArg<String>(), LogEventData::class.java))
        }
        return statsigNetwork
    }

    class InitializeCallback(val initializeCountdown: CountDownLatch) : IStatsigCallback {
        override fun onStatsigInitialize() {
            initializeCountdown.countDown()
        }

        override fun onStatsigUpdateUser() {
        }
    }
}
