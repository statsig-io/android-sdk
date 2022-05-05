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
    TestUtil.overrideMainDispatcher()

    app = mockk()

    sharedPrefs = TestUtil.stubAppFunctions(app)

    TestUtil.mockStatsigUtil()

    initClient()
    assertTrue(sharedPrefs.all.isEmpty())

    layer = Layer(
      client,
      "a_layer",
      TestUtil.getConfigValueMap(),
      "default",
      EvaluationDetails(EvaluationReason.Network),
    )

    TestUtil.startStatsigAndWait(app)

    return@runBlocking
  }

  @After
  internal fun tearDown() {
    unmockkAll()
  }

  @Test
  fun testDummy() {
    val dummyConfig = Layer(client, "", mapOf(), "", EvaluationDetails(EvaluationReason.Unrecognized))
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
    assertEquals(dummyConfig.getEvaluationDetails().reason, EvaluationReason.Unrecognized)
  }

  @Test
  fun testEmpty() {
    val emptyConfig = Layer(
      client,
      "test_config",
      mapOf(),
      "default",
      EvaluationDetails(EvaluationReason.Uninitialized),
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

    assertEquals(emptyConfig.getEvaluationDetails().reason, EvaluationReason.Uninitialized)
  }

  @Test
  fun testPrimitives() {
    assertEquals("test", layer.getString("testString", "1234"))
    assertTrue(layer.getBoolean("testBoolean", false))
    assertEquals(12, layer.getInt("testInt", 13))
    assertEquals(42.3, layer.getDouble("testDouble", 13.0), 0.0)
    assertEquals(9223372036854775806, layer.getLong("testLong", 1))
  }

  @Test
  fun testArrays() {
    assertArrayEquals(arrayOf("one", "two"), layer.getArray("testArray", arrayOf(1, "one")))
    assertArrayEquals(arrayOf(3L, 2L), layer.getArray("testIntArray", arrayOf(1, 2)))
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
        "nestedLong" to 13L
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
  fun testGettingStickyValuesWhenLayerChanges() = runBlocking {
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
  fun testGettingStickyValuesAcrossSessions() = runBlocking {
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

    initClient()

    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))
  }

  @Test
  fun testGettingStickyValuesWhenUserIsNoLongerInExperiment() = runBlocking {
    var config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))

    val updatedLayerResponse = TestUtil.makeInitializeResponse(layerConfigs = mapOf(
      "allocated_layer!" to APIDynamicConfig(
        "allocated_layer!",
        mapOf(
          "string" to "default_string",
        ),
        "default",
        isExperimentActive = true,
        isUserInExperiment = false,
        allocatedExperimentName = "different_exp!"
      ),
    ))

    client.getStore().save(updatedLayerResponse)

    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals(
      "Layer allocation changed, but should still get original sticky value",
      "test", config.getString("string", "ERR"))

    client.getStore().save(
      TestUtil.makeInitializeResponse(
        dynamicConfigs = mapOf(
          "layer_exp!" to APIDynamicConfig(
            "layer_exp!",
            mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
            "exp_rule",
            arrayOf(),
            isExperimentActive = false,
            isUserInExperiment = true,
          ),
        ),
        layerConfigs = updatedLayerResponse.layerConfigs!!
      )
    )

    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("The original sticky Experiment is no longer active, should return updated Layer value",
      "default_string", config.getString("string", "ERR"))
  }

  @Test
  fun testGettingStickyValuesWhenUserIsNowInDifferentExperiment() = runBlocking {
    var config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals("test", config.getString("string", "ERR"))

    val response = TestUtil.makeInitializeResponse(layerConfigs = mapOf(
      "allocated_layer!" to APIDynamicConfig(
        "allocated_layer!",
        mapOf(
          "string" to "default_string",
        ),
        "default",
        isExperimentActive = true,
        isUserInExperiment = true,
        allocatedExperimentName = "completely_different_exp"
      ),
    ))

    client.getStore().save(response)

    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals(
      "Should still return the original sticky value because the original experiment is still active",
      "test", config.getString("string", "ERR"))

    (response.configs as MutableMap)["layer_exp!"] = APIDynamicConfig(
      "layer_exp!",
      mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
      "exp_rule",
      arrayOf(),
      isExperimentActive = false,
      isUserInExperiment = true,
    )
    client.getStore().save(response)

    config = client.getLayer("allocated_layer", keepDeviceValue = true)
    assertEquals(
      "Should get updated value because the original experiment is no longer active",
      "default_string", config.getString("string", "ERR"))
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

  private fun initClient() = runBlocking {
    val statsigNetwork = TestUtil.mockNetwork()

    client = StatsigClient()
    client.statsigNetwork = statsigNetwork
    client.initialize(app, "test-key")
  }
}