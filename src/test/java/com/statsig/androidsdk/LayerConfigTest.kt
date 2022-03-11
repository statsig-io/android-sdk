package com.statsig.androidsdk

import android.app.Application
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*;
import org.junit.Before
import org.junit.Test

class LayerConfigTest {
  private lateinit var app: Application
  private lateinit var sharedPrefs: TestSharedPreferences
  private var client: StatsigClient = StatsigClient()
  private lateinit var layer: Layer

  @Before
  internal fun setup() = runBlocking {
    app = mockk()

    Dispatchers.setMain(TestCoroutineDispatcher())

    sharedPrefs = TestUtil.mockApp(app)

    TestUtil.mockStatsigUtil()

    val statsigNetwork = TestUtil.mockNetwork()

    client = StatsigClient()
    client.statsigNetwork = statsigNetwork
    client.initialize(app, "test-key")

    assertTrue(sharedPrefs.all.isEmpty())

    layer = Layer(
      "a_layer",
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
      "default",
    )
  }

  @After
  internal fun tearDown() {
    unmockkAll()
  }

  @Test
  fun testDummy() {
    val dummyConfig = Layer("")
    assertEquals("provided default", dummyConfig.getString("test", "provided default"))
    assertEquals(true, dummyConfig.getBoolean("test", true))
    assertEquals(12, dummyConfig.getInt("test", 12))
    assertEquals("hello world", dummyConfig.getString("test", "hello world"))
    assertEquals("", dummyConfig.getRuleID())
    assertNull(dummyConfig.getString("test", null))
    assertNull(dummyConfig.getConfig("nested"))
    assertNull(dummyConfig.getString("testnodefault", null))
    assertNull(dummyConfig.getArray("testnodefault", null))
    assertNull(dummyConfig.getDictionary("testnodefault", null))
  }

  @Test
  fun testEmpty() {
    val emptyConfig = Layer(
      "test_config",
      mapOf(),
      "default",
    )

    assertEquals("provided default", emptyConfig.getString("test", "provided default"))
    assertEquals(12, emptyConfig.getInt("testInt", 12))
    assertEquals(true, emptyConfig.getBoolean("test_config", true))
    assertEquals(3.0, emptyConfig.getDouble("test_config", 3.0), 0.0)
    val arr = arrayOf("test", "one")
    assertArrayEquals(arr, emptyConfig.getArray("test_config", arr as Array<Any>))
    assertEquals("default", emptyConfig.getRuleID())
    assertNull(emptyConfig.getConfig("nested"))
    assertNull(emptyConfig.getString("testnodefault", null))
    assertNull(emptyConfig.getArray("testnodefault", null))
    assertNull(emptyConfig.getDictionary("testnodefault", null))
  }

  @Test
  fun testPrimitives() {
    assertEquals("test", layer.getString("testString", "1234"))
    assertTrue(layer.getBoolean("testBoolean", false))
    assertEquals(12, layer.getInt("testInt", 13))
    assertEquals(42.3, layer.getDouble("testDouble", 13.0), 0.0)
  }

  @Test
  fun testArrays() {
    assertArrayEquals(arrayOf("one", "two"), layer.getArray("testArray", arrayOf(1, "one")))
    assertArrayEquals(arrayOf(3, 2), layer.getArray("testIntArray", arrayOf(1, 2)))
    assertArrayEquals(arrayOf(3.1, 2.1), layer.getArray("testDoubleArray", arrayOf(1, "one")))
    assertArrayEquals(arrayOf(true, false), layer.getArray("testBooleanArray", arrayOf(1, "one")))
  }

  @Test
  fun testNested() {
    assertEquals("nested", layer.getConfig("testNested")!!.getString("nestedString", "111"))
    assertTrue(layer.getConfig("testNested")!!.getBoolean("nestedBoolean", false))
    assertEquals(13.74, layer.getConfig("testNested")!!.getDouble("nestedDouble", 99.99), 0.0)
    assertEquals(13, layer.getConfig("testNested")!!.getInt("nestedInt", 13))
    assertNull(layer.getConfig("testNested")!!.getConfig("testNestedAgain"))

    assertEquals(
      mapOf(
        "nestedString" to "nested",
        "nestedBoolean" to true,
        "nestedDouble" to 13.74,
        "nestedInt" to 13
      ), layer.getDictionary("testNested", mapOf())
    )
  }

  @Test
  fun testReturningValues() = runBlocking {
    val config = client.getLayer("allocated_layer")
    assertEquals("test", config.getString("string", "ERR"))
    assertEquals(42, config.getInt("number", -1))
    assertEquals(17, config.getInt("otherNumber", -1))
  }

  @Test
  fun testGettingStickyValues() = runBlocking {
    var config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))

    client.getStore().save(
      TestUtil.makeInitializeResponse(layerConfigs = mapOf(
        "allocated_layer!" to APIDynamicConfig(
          "allocated_layer!",
          mapOf(
            "string" to "default_string",
          ),
          "default",
          isExperimentActive = true,
          isUserInExperiment = true,
        ),
      )))


    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))
  }

  @Test
  fun testWipingStickyValues() = runBlocking {
    var config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))

    client.getStore().save(
      TestUtil.makeInitializeResponse(layerConfigs = mapOf(
        "allocated_layer!" to APIDynamicConfig(
          "allocated_layer!",
          mapOf(
            "string" to "default_string"
          ),
          "default",
          isExperimentActive = true,
          isUserInExperiment = true,
        ),
      )))


    config = client.getLayer("allocated_layer")
    assertEquals("default_string", config.getString("string", "ERR"))
  }
}