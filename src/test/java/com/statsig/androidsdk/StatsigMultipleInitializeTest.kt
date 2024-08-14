package com.statsig.androidsdk

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test

class StatsigMultipleInitializeTest {

    private lateinit var client: StatsigClient
    private lateinit var app: Application
    private lateinit var network: StatsigNetwork

    @Before
    fun setup() {
        TestUtil.mockDispatchers()
        app = mockk(relaxed = true)
        client = spyk(StatsigClient(), recordPrivateCalls = true)
        network = TestUtil.mockNetwork()
        client.statsigNetwork = network

        TestUtil.stubAppFunctions(app)

        coEvery {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                context = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
            )
        } coAnswers {
            TestUtil.makeInitializeResponse()
        }
    }

    @Test
    fun testMultipleInitializeAsyncCalls() {
        val job1 = GlobalScope.launch(Dispatchers.IO) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        val job2 = GlobalScope.launch(Dispatchers.IO) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        val job3 = GlobalScope.launch(Dispatchers.IO) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        runBlocking {
            joinAll(job1, job2, job3)
        }
        coVerify(exactly = 1) {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                context = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
            )
        }
    }

    @Test
    fun testMultipleInitializeCalls() {
        val job1 = GlobalScope.launch(Dispatchers.IO) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        val job2 = GlobalScope.launch(Dispatchers.IO) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        val job3 = GlobalScope.launch(Dispatchers.IO) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        runBlocking {
            joinAll(job1, job2, job3)
        }
        coVerify(exactly = 1) {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                context = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
            )
        }
    }

    @Test
    fun testMultipleInitializeCallsOnMain() {
        val job1 = GlobalScope.launch(Dispatchers.Default) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        val job2 = GlobalScope.launch(Dispatchers.Default) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        val job3 = GlobalScope.launch(Dispatchers.Default) {
            client.initialize(app, "client-key", StatsigUser("test_user"))
        }

        runBlocking {
            joinAll(job1, job2, job3)
        }

        coVerify(exactly = 1) {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                context = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
            )
        }
    }

    @Test
    fun testMultipleInitializeAsyncCallsOnMain() {
        val job1 = GlobalScope.launch(Dispatchers.Default) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        val job2 = GlobalScope.launch(Dispatchers.Default) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        val job3 = GlobalScope.launch(Dispatchers.Default) {
            client.initializeAsync(app, "client-key", StatsigUser("test_user"))
        }

        runBlocking {
            joinAll(job1, job2, job3)
        }
        coVerify(exactly = 1) {
            network.initialize(
                api = any(),
                user = any(),
                sinceTime = any(),
                metadata = any(),
                coroutineScope = any(),
                context = any(),
                diagnostics = any(),
                hashUsed = any(),
                previousDerivedFields = any(),
            )
        }
    }
}
