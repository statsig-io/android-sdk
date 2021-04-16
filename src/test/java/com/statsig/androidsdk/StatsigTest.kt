package com.statsig.androidsdk

import org.mockito.Mockito

import android.app.Application
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock

class StatsigTest {
    @Test
    fun beforeInitialize() {
        assertFalse(Statsig.checkGate("any_gate_name"))

        assertNull(Statsig.getConfig("any_config"))

        assertFalse(Statsig.isReady())
    }

    @Test
    fun initializeBadInput() {
        val cb = object : StatsigCallback {
            override fun onStatsigReady() {
                // no-op for now
            }
        }
        try {
            Statsig.initialize(
                Mockito.mock(Application::class.java),
                "secret-111aaa",
                cb
            )
            fail("Statsig.initialize() did not fail for a non client/test key")
        } catch (expectedException: java.lang.IllegalArgumentException) {}
    }

}
