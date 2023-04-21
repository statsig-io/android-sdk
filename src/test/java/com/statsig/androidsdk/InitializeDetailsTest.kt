package com.statsig.androidsdk

import android.app.Application
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

class InitializeDetailsTest {
    lateinit var app: Application

    private fun mockInitializeSuccessfulResponse() {
        val network = TestUtil.mockNetwork()
        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = network
    }

    private fun mockInitializeInvalidResponse() {
        val network = TestUtil.mockNetwork()
        coEvery {
            network.initialize(any(), any(), any(), any(), any(), any())
        } coAnswers {
            InitializeResponse.FailedInitializeResponse(InitializeFailReason.InvalidResponse, null, 403)
        }
        Statsig.client = StatsigClient()
        Statsig.client.statsigNetwork = network
    }

    @Before
    internal fun setup() {
        TestUtil.mockDispatchers()

        app = mockk()
        TestUtil.stubAppFunctions(app)
        TestUtil.mockStatsigUtil()
    }

    @Test
    fun testAsyncSuccess() {
        mockInitializeSuccessfulResponse()
        val user = StatsigUser("test-user")
        val waitOnInitialize = CountDownLatch(1)
        var initialized = false
        var duration: Long? = null
        var failure: InitializeResponse.FailedInitializeResponse? = null

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize(initDetails: InitializationDetails) {
                waitOnInitialize.countDown()
                initialized = initDetails.success
                duration = initDetails.duration
                failure = initDetails.failureDetails
            }

            override fun onStatsigUpdateUser() {}
        }
        Statsig.initializeAsync(app, "client-key", user, callback)
        waitOnInitialize.await()
        assertTrue(initialized)
        assertTrue(duration!! > 0)
        assertNull(failure)
    }

    @Test
    fun testAsyncFailureInvalidResponse() {
        mockInitializeInvalidResponse()
        val user = StatsigUser("test-user")
        val waitOnInitialize = CountDownLatch(1)
        var initialized = false
        var duration: Long? = null
        var failure: InitializeResponse.FailedInitializeResponse? = null

        val callback = object : IStatsigCallback {
            override fun onStatsigInitialize(initDetails: InitializationDetails) {
                waitOnInitialize.countDown()
                initialized = initDetails.success
                duration = initDetails.duration
                failure = initDetails.failureDetails
            }

            override fun onStatsigUpdateUser() {}
        }
        Statsig.initializeAsync(app, "client-key", user, callback)
        waitOnInitialize.await()
        assertFalse(initialized)
        assertTrue(duration!! > 0)
        assertNotNull(failure)
        assertEquals(InitializeFailReason.InvalidResponse, failure?.reason)
    }
}


