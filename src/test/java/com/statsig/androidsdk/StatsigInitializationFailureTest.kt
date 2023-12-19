package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.Exception
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatsigInitializationFailureTest {
    private lateinit var client: StatsigClient
    private lateinit var eb: ErrorBoundary
    private lateinit var network: StatsigNetwork
    private lateinit var callback: IStatsigCallback
    private var initDetails: InitializationDetails? = null
    private var logEventRequests = mutableListOf<LogEventData>()
    private var app: Application = mockk()
    private var countdown: CountDownLatch = CountDownLatch(1)

    @Before
    internal fun setup() {
        client = StatsigClient()
        client.errorBoundary = spyk(client.errorBoundary)
        eb = client.errorBoundary
        network = TestUtil.mockNetwork {
            logEventRequests.add(it)
        }
        client.statsigNetwork = network
        TestUtil.mockDispatchers()
        TestUtil.stubAppFunctions(app)
        callback = object : IStatsigCallback {
            override fun onStatsigUpdateUser() {
                throw Exception("Update user should not be called")
            }

            override fun onStatsigInitialize(initDetails: InitializationDetails) {
                this@StatsigInitializationFailureTest.initDetails = initDetails
                countdown.countDown()
            }
        }
    }

    @After
    fun cleanup() {
        initDetails = null
        logEventRequests = mutableListOf()
        unmockkAll()
        countdown = CountDownLatch(1)
    }

    @Test
    fun testInitializeSetupException() = runBlocking {
        mockkConstructor(Store::class)
        every {
            anyConstructed<Store>().bootstrap(any(), any())
        } answers {
            throw Exception("Throwing exceptions from bootstrap")
        }
        initDetails = client.initialize(app, "client-key", StatsigUser("test_user"), StatsigOptions(initializeValues = mapOf()))
        assert(initDetails?.success === false)
        assert(initDetails?.failureDetails?.reason === InitializeFailReason.InternalError)
        assert(initDetails?.failureDetails?.exception?.message == "Throwing exceptions from bootstrap")
        assert(logEventRequests.size === 1)
        assert(logEventRequests[0].events[0].eventName == "statsig::diagnostics")
        verify {
            eb.logException(any())
        }
        val markers = Gson().fromJson(logEventRequests[0].events[0].metadata?.get("markers") ?: "", Array<Marker>::class.java)
        assert(markers.size === 2)
        assert(markers[1].success === false)
    }

    @Test
    fun testInitializeSetupExceptionWithoutLogging() = runBlocking {
        // Mock exceptions within setup before logger being constructed
        every {
            app.registerActivityLifecycleCallbacks(any())
        } answers {
            throw Exception("Mock throwing exception when registering lifecycle callbacks")
        }
        initDetails = client.initialize(app, "client-key")

        assert(initDetails?.success === false)
        assert(initDetails?.failureDetails?.reason === InitializeFailReason.InternalError)
        assert(initDetails?.failureDetails?.exception?.message == "Mock throwing exception when registering lifecycle callbacks")
        // Logger has not been initialized because it's not initialized
        assert(logEventRequests.size === 0)
        verify {
            eb.logException(any())
        }
    }

    @Test
    fun testInitializeAsyncException() = runBlocking {
        // Exceptions thrown in setup
        mockkConstructor(Store::class)
        every {
            anyConstructed<Store>().bootstrap(any(), any())
        } answers {
            throw Exception("Throwing exceptions from bootstrap")
        }
        assert(initDetails === null)
        client.initializeAsync(app, "client-key", StatsigUser("test_user"), callback, StatsigOptions(initializeValues = mapOf()))
        countdown.await(1, TimeUnit.SECONDS)
        assert(initDetails?.success === false)
        assert(initDetails?.failureDetails?.reason === InitializeFailReason.InternalError)
        assert(initDetails?.failureDetails?.exception?.message == "Throwing exceptions from bootstrap")
        assert(logEventRequests.size === 1)
        assert(logEventRequests[0].events[0].eventName == "statsig::diagnostics")
        verify {
            eb.logException(any())
        }
        var markers = Gson().fromJson(logEventRequests[0].events[0].metadata?.get("markers") ?: "", Array<Marker>::class.java)
        assert(markers.size === 2)
        assert(markers[1].success === false)
        assert(markers[1].key === KeyType.OVERALL)
    }

    @Test
    fun testInitializeAsyncSetupAsyncException() = runBlocking {
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            throw Exception("Something wrong happen when connecting to network")
        }
        assert(initDetails === null)
        client.initializeAsync(app, "client-key", StatsigUser("test_user"), callback)
        countdown.await(100, TimeUnit.SECONDS)
        assert(initDetails?.success === false)
        assert(initDetails?.failureDetails?.reason === InitializeFailReason.InternalError)
        assert(initDetails?.failureDetails?.exception?.message == "Something wrong happen when connecting to network")
        assert(logEventRequests.size === 1)
        assert(logEventRequests[0].events[0].eventName == "statsig::diagnostics")
        verify {
            eb.logException(any())
        }
        val markers = Gson().fromJson(logEventRequests[0].events[0].metadata?.get("markers") ?: "", Array<Marker>::class.java)
        assert(markers.size === 2)
        assert(markers[1].success === false)
    }

    @Test
    fun testInitializeAsyncExceptionWithoutLogging() = runBlocking {
        every {
            app.registerActivityLifecycleCallbacks(any())
        } answers {
            throw Exception("Mock throwing exception when registering lifecycle callbacks")
        }
        assert(initDetails === null)
        client.initializeAsync(app, "client-key", StatsigUser("test_user"), callback)
        countdown.await(1, TimeUnit.SECONDS)
        assert(initDetails?.success === false)
        assert(initDetails?.failureDetails?.reason === InitializeFailReason.InternalError)
        assert(initDetails?.failureDetails?.exception?.message == "Mock throwing exception when registering lifecycle callbacks")
        // Logger has not been initialized because it's not initialized
        assert(logEventRequests.size === 0)
        verify {
            eb.logException(any())
        }
    }
}
