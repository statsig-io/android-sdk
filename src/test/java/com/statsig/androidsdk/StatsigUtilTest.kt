package com.statsig.androidsdk

import org.junit.Test
import org.junit.Assert.*

class StatsigUtilTest {

    @Test
    fun normalizeUser() {
        assertNull(StatsigUtil.normalizeUser(null))

        assertEquals(0, StatsigUtil.normalizeUser(mapOf())!!.size)

        val inputMap : Map<String, Any> = mapOf(
            "testString" to "test",
            "testBoolean" to true,
            "testInt" to 12,
            "testDouble" to 42.3,
            "testLong" to 7L,
            "testArray" to arrayOf("one", "two"),
            "testIntArray" to intArrayOf(3, 2),
        )
        val resultMap = StatsigUtil.normalizeUser(inputMap)

        assertEquals(42.3, resultMap!!.get("testDouble"))
        assertEquals(12, resultMap.get("testInt"))
        assertEquals(true, resultMap.get("testBoolean"))
        assertEquals("test", resultMap.get("testString"))
        assertEquals(42.3, resultMap.get("testDouble"))
        assertTrue(resultMap.containsKey("testArray"))
        assertFalse(resultMap.containsKey("testIntArray"))
    }
}