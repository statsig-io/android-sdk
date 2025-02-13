package com.statsig.androidsdk

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DynamicConfigTest {

    private lateinit var dc: DynamicConfig

    @Before
    internal fun setup() {
        dc = DynamicConfig(
            "test_config",
            EvaluationDetails(EvaluationReason.Network, lcut = 0),
            TestUtil.getConfigValueMap(),
            "default",
        )
    }

    @Test
    fun testDummy() {
        val dummyConfig = DynamicConfig("", EvaluationDetails(EvaluationReason.Unrecognized, lcut = 0))
        assertEquals("provided default", dummyConfig.getString("test", "provided default"))
        assertEquals(true, dummyConfig.getBoolean("test", true))
        assertEquals(12, dummyConfig.getInt("test", 12))
        assertEquals("hello world", dummyConfig.getString("test", "hello world"))
        assertEquals(0, dummyConfig.getValue().size)
        assertEquals("", dummyConfig.getRuleID())
        assertNull(dummyConfig.getGroupName())
        assertNull(dummyConfig.getString("test", null))
        assertNull(dummyConfig.getConfig("nested"))
        assertNull(dummyConfig.getString("testnodefault", null))
        assertNull(dummyConfig.getArray("testnodefault", null))
        assertNull(dummyConfig.getDictionary("testnodefault", null))
        assertEquals(dummyConfig.getEvaluationDetails().reason, EvaluationReason.Unrecognized)
    }

    @Test
    fun testEmpty() {
        val emptyConfig = DynamicConfig(
            "test_config",
            EvaluationDetails(EvaluationReason.Uninitialized, lcut = 0),
            mapOf(),
            "default",
        )

        assertEquals("provided default", emptyConfig.getString("test", "provided default"))
        assertEquals(12, emptyConfig.getInt("testInt", 12))
        assertEquals(true, emptyConfig.getBoolean("test_config", true))
        assertEquals(3.0, emptyConfig.getDouble("test_config", 3.0), 0.0)
        val arr = arrayOf("test", "one")
        @Suppress("UNCHECKED_CAST")
        assertArrayEquals(arr, emptyConfig.getArray("test_config", arr as Array<Any>))
        assertEquals("default", emptyConfig.getRuleID())
        assertNull(emptyConfig.getGroupName())
        assertNull(emptyConfig.getConfig("nested"))
        assertNull(emptyConfig.getString("testnodefault", null))
        assertNull(emptyConfig.getArray("testnodefault", null))
        assertNull(emptyConfig.getDictionary("testnodefault", null))

        assertEquals(emptyConfig.getEvaluationDetails().reason, EvaluationReason.Uninitialized)
    }

    @Test
    fun testPrimitives() {
        assertEquals("test", dc.getString("testString", "1234"))
        assertTrue(dc.getBoolean("testBoolean", false))
        assertEquals(12, dc.getInt("testInt", 13))
        assertEquals(42.3, dc.getDouble("testDouble", 13.0), 0.0)
        assertEquals(9223372036854775806, dc.getLong("testLong", 1))
        assertEquals(12.0, dc.getDouble("testInt", 13.0), 0.0)
        assertEquals(41, dc.getInt("testAnotherDouble", 44))
    }

    @Test
    fun testArrays() {
        assertArrayEquals(arrayOf("one", "two"), dc.getArray("testArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(3L, 2L), dc.getArray("testIntArray", arrayOf(1, 2)))
        assertArrayEquals(arrayOf(3.1, 2.1), dc.getArray("testDoubleArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(true, false), dc.getArray("testBooleanArray", arrayOf(1, "one")))
    }

    @Test
    fun testNested() {
        assertEquals("nested", dc.getConfig("testNested")!!.getString("nestedString", "111"))
        assertTrue(dc.getConfig("testNested")!!.getBoolean("nestedBoolean", false))
        assertEquals(13.74, dc.getConfig("testNested")!!.getDouble("nestedDouble", 99.99), 0.0)
        assertEquals(13, dc.getConfig("testNested")!!.getInt("nestedInt", 13))
        assertNull(dc.getConfig("testNested")!!.getConfig("testNestedAgain"))

        assertEquals(
            mapOf(
                "nestedString" to "nested",
                "nestedBoolean" to true,
                "nestedDouble" to 13.74,
                "nestedLong" to 13L,
            ),
            dc.getDictionary("testNested", mapOf()),
        )
    }
}
