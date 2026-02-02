package com.statsig.androidsdk

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StatsigUtilTest {

    @Before
    fun `Bypass android_util_Base64 to java_util_Base64`() {
        mockkStatic(Base64::class)
        val arraySlot = slot<ByteArray>()

        every {
            Base64.encodeToString(capture(arraySlot), Base64.NO_WRAP)
        } answers {
            java.util.Base64.getEncoder().encodeToString(arraySlot.captured)
        }

        val stringSlot = slot<String>()
        every {
            Base64.decode(capture(stringSlot), Base64.DEFAULT)
        } answers {
            java.util.Base64.getDecoder().decode(stringSlot.captured)
        }
    }

    @After
    internal fun tearDown() {
        unmockkAll()
    }

    @Test
    fun normalizeUser() {
        assertNull(StatsigUtil.normalizeUser(null))

        assertEquals(0, StatsigUtil.normalizeUser(mapOf())!!.size)

        val inputMap: Map<String, Any> = mapOf(
            "testString" to "test",
            "testBoolean" to true,
            "testInt" to 12,
            "testDouble" to 42.3,
            "testLong" to 7L,
            "testArray" to arrayOf("one", "two"),
            "testIntArray" to intArrayOf(3, 2)
        )
        val resultMap = StatsigUtil.normalizeUser(inputMap)

        assertEquals(42.3, resultMap!!.get("testDouble"))
        assertFalse(resultMap.containsKey("testInt"))
        assertEquals(true, resultMap.get("testBoolean"))
        assertEquals("test", resultMap.get("testString"))
        assertEquals(42.3, resultMap.get("testDouble"))
        assertTrue(resultMap.containsKey("testArray"))
        assertFalse(resultMap.containsKey("testIntArray"))
    }

    @Test
    fun testHashing() {
        assertEquals(
            "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
            Hashing.getHashedString("test", null)
        )
        assertEquals(
            "7NcYcNGWMxapfjrDQIyYNa2M8PPBvHA1J8MCZVNPda4=",
            Hashing.getHashedString("test123", null)
        )
    }

    @Test
    fun testUserHashing() {
        val gson = StatsigUtil.getOrBuildGson()
        val userA = gson.fromJson(
            "{\"userID\":\"userA\",\"email\":\"userA@gmail.com\",\"country\":\"US\"}",
            StatsigUser::class.java
        )
        userA.statsigEnvironment = mapOf("tier" to "production")
        val userB = gson.fromJson(
            "{\"userID\":\"userB\",\"email\":\"userB@gmail.com\",\"country\":\"US\"}",
            StatsigUser::class.java
        )
        val userADifferentEnvironment = userA.getCopyForEvaluation()
        userADifferentEnvironment.statsigEnvironment = mapOf("tier" to "staging")
        val userADifferentOrder = gson.fromJson(
            "{\"userID\":\"userA\",\"country\":\"US\", \"email\":\"userA@gmail.com\"}",
            StatsigUser::class.java
        )
        userADifferentOrder.statsigEnvironment = mapOf("tier" to "production")
        val userAMoreDetails = gson.fromJson(
            "{\"userID\":\"userA\",\"email\":\"userA@gmail.com\",\"country\":\"US\",\"userAgent\":\"userAgent\"}",
            StatsigUser::class.java
        )
        userAMoreDetails.statsigEnvironment = mapOf("tier" to "production")

        assertTrue(userA.toHashString(gson) == userADifferentOrder.toHashString(gson))
        assertTrue(userA.toHashString(gson) != userB.toHashString(gson))
        assertTrue(userA.toHashString(gson) != userADifferentEnvironment.toHashString(gson))
        assertTrue(userA.toHashString(gson) != userAMoreDetails.toHashString(gson))
    }
}
