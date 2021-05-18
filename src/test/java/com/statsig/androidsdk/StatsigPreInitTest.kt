package com.statsig.androidsdk

import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import io.mockk.*

class StatsigPreInitTest {

    @Before
    internal fun setup() {
        mockkObject(StatsigUtil)
        every {
            StatsigUtil.getHashedString(any())
        } answers {
            firstArg<String>()
        }
    }

    @Test
    fun beforeInitialize() {
        try {
            Statsig.checkGate("any_gate_name")
            fail("Statsig.checkGate() did not fail before calling initialize")
        } catch (expectedException: java.lang.IllegalStateException) {
        }

        try {
            Statsig.getConfig("any_config").getRuleID()
            fail("Statsig.getConfig() did not fail before calling initialize")
        } catch (expectedException: java.lang.IllegalStateException) {
        }
    }
}