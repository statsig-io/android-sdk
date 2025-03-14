package com.statsig.androidsdk

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ParameterStoreTest {

    private lateinit var paramStore: ParameterStore

    @Before
    internal fun setup() {
        paramStore = ParameterStore(
            statsigClient = StatsigClient(),
            paramStore = mapOf(
                "testString" to mapOf(
                    "value" to "test",
                    "ref_type" to "static",
                    "param_type" to "string",
                ),
            ),
            name = "test_parameter_store",
            evaluationDetails = EvaluationDetails(EvaluationReason.Network, lcut = 0),
            options = ParameterStoreEvaluationOptions(disableExposureLog = true),
        )
    }

    @Test
    fun testNullFallback() {
        assertNull(paramStore.getString("nonexistent", null))
        assertEquals("test", paramStore.getString("testString", null))
    }
}
