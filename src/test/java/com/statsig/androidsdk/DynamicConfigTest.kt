package com.statsig.androidsdk

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class DynamicConfigTest {

    private lateinit var dc: DynamicConfig

    @Before
    internal fun setup() {
        dc = DynamicConfig(
            Config(
                "test_config",
                mapOf(
                    "testString" to "test",
                    "testBoolean" to true,
                    "testInt" to 12,
                    "testDouble" to 42.3,
                    "testArray" to arrayOf("one", "two"),
                    "testIntArray" to intArrayOf(3, 2),
                    "testDoubleArray" to doubleArrayOf(3.1, 2.1),
                    "testBooleanArray" to booleanArrayOf(true, false),
                    "testNested" to mapOf(
                        "nestedString" to "nested",
                        "nestedBoolean" to true,
                        "nestedDouble" to 13.74,
                        "nestedInt" to 13
                    ),
                ),
                "default"
            )
        )
    }

    @Test
    fun testEmpty() {
        val emptyConfig = DynamicConfig(
            Config(
                "test_config",
                mapOf(),
                "default"
            )
        )

        assertEquals("provided default", emptyConfig.getString("test", "provided default"))
        assertEquals(12, emptyConfig.getInt("testInt", 12))
        assertEquals(true, emptyConfig.getBoolean("test_config", true))
        assertEquals(3.0, emptyConfig.getDouble("test_config", 3.0), 0.0)
        val arr = arrayOf("test", "one")
        assertArrayEquals(arr, emptyConfig.getArray("test_config", arr as Array<Any>))
        assertNull(emptyConfig.getConfig("nested"))
        assertNull(emptyConfig.getString("testnodefault"))
        assertNull(emptyConfig.getArray("testnodefault"))
        assertNull(emptyConfig.getDictionary("testnodefault"))
    }

    @Test
    fun testPrimitives() {
        assertEquals("test", dc.getString("testString", "1234"))
        assertTrue(dc.getBoolean("testBoolean", false))
        assertEquals(12, dc.getInt("testInt", 13))
        assertEquals(42.3, dc.getDouble("testDouble", 13.0), 0.0)
    }

    @Test
    fun testArrays() {
        assertArrayEquals(arrayOf("one", "two"), dc.getArray("testArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(3, 2), dc.getArray("testIntArray", arrayOf(1, 2)))
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
                "nestedInt" to 13
            ), dc.getDictionary("testNested", mapOf())
        )
    }
}