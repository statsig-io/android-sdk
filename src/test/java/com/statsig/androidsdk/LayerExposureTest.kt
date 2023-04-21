package com.statsig.androidsdk

import android.app.Application
import com.google.gson.Gson
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LayerExposureTest {
  lateinit var app: Application
  private var logs: LogEventData? = null
  private var initTime = System.currentTimeMillis()

  @Before
  fun setup() {
    TestUtil.mockDispatchers()
    app = mockk()
    TestUtil.stubAppFunctions(app)
    TestUtil.mockStatsigUtil()
  }

  @Test
  fun testDoesNotLogOnInvalidType() {
    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99),
          "default",
          undelegatedSecondaryExposures = TestUtil.dummyUndelegatedSecondaryExposures,
          allocatedExperimentName = "",
          explicitParameters = arrayOf(),
        )
      )
    )

    val layer = Statsig.getLayer("layer")
    layer.getString("an_int", "")
    Statsig.shutdown()

    assertNull(logs);
  }

  @Test
  fun testDoesNotLogNonExistentKeys() {
    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf(),
          "default",
          undelegatedSecondaryExposures = TestUtil.dummyUndelegatedSecondaryExposures,
          allocatedExperimentName = "",
          explicitParameters = arrayOf(),
        )
      )
    )

    val layer = Statsig.getLayer("layer")
    layer.getInt("an_int", 0)
    Statsig.shutdown()

    assertNull(logs);
  }

  @Test
  fun testUnallocatedLayerLogging() {
    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99),
          "default",
          undelegatedSecondaryExposures = TestUtil.dummyUndelegatedSecondaryExposures,
          allocatedExperimentName = "",
          explicitParameters = arrayOf(),
        )
      )
    )

    val layer = Statsig.getLayer("layer")
    layer.getInt("an_int", 0)
    Statsig.shutdown()

    val logs = logs!!
    assertEquals(1, logs.events.count())

    assertEquals(
      "Should use undelegated_secondary_exposures for unallocated layers",
      Gson().toJson(TestUtil.dummyUndelegatedSecondaryExposures),
      Gson().toJson(logs.events[0].secondaryExposures)
    )
    val time = logs.events[0].metadata?.get("time")!!.toLong()
    assertTrue(time >= initTime && time < initTime + 2000)
    assertEquals(
      Gson().toJson(mapOf(
        "config" to "layer",
        "ruleID" to "default",
        "allocatedExperiment" to "",
        "parameterName" to "an_int",
        "isExplicitParameter" to "false",
        "reason" to "Network",
        "time" to time.toString(),
      )),
      Gson().toJson(logs.events[0].metadata)
    )
  }

  @Test
  fun testExplicitVsImplicitParameterLogging() {
    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99, "a_string" to "value"),
          "default",
          secondaryExposures = TestUtil.dummySecondaryExposures,
          undelegatedSecondaryExposures = TestUtil.dummyUndelegatedSecondaryExposures,
          allocatedExperimentName = "the_allocated_exp!",
          explicitParameters = arrayOf("an_int"),
        )
      )
    )

    val layer = Statsig.getLayer("layer")
    layer.getInt("an_int", 0)
    layer.getString("a_string", "err")
    Statsig.shutdown()

    val logs = logs!!
    assertEquals(2, logs.events.count())

    assertEquals(
      "Should use secondary_exposures for explicit params",
      Gson().toJson(TestUtil.dummySecondaryExposures),
      Gson().toJson(logs.events[0].secondaryExposures)
    )
    var time = logs.events[0].metadata?.get("time")!!.toLong()
    assertTrue(time >= initTime && time < initTime + 2000)
    assertEquals(
      Gson().toJson(mapOf(
        "config" to "layer",
        "ruleID" to "default",
        "allocatedExperiment" to "the_allocated_exp!",
        "parameterName" to "an_int",
        "isExplicitParameter" to "true",
        "reason" to "Network",
        "time" to time.toString(),
      )),
      Gson().toJson(logs.events[0].metadata)
    )

    assertEquals(
      "Should use undelegated_secondary_exposures for implicit params",
      Gson().toJson(TestUtil.dummyUndelegatedSecondaryExposures),
      Gson().toJson(logs.events[1].secondaryExposures)
    )
    time = logs.events[1].metadata?.get("time")!!.toLong()
    assertTrue(time >= initTime && time < initTime + 2000)
    assertEquals(
      Gson().toJson(mapOf(
        "config" to "layer",
        "ruleID" to "default",
        "allocatedExperiment" to "",
        "parameterName" to "a_string",
        "isExplicitParameter" to "false",
        "reason" to "Network",
        "time" to time.toString(),
      )),
      Gson().toJson(logs.events[1].metadata)
    )
  }

  @Test
  fun testDifferentObjectTypeLogging() {
    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf(
            "a_bool" to true,
            "an_int" to 99,
            "a_double" to 1.23,
            "a_long" to 10L,
            "a_string" to "value",
            "an_array" to arrayOf("a", "b"),
            "an_object" to mapOf("key" to "value"),
            "another_object" to mapOf("another_key" to "another_value")
          ),
          "default",
        )
      )
    )

    val layer = Statsig.getLayer("layer")
    layer.getBoolean("a_bool", false)
    layer.getInt("an_int", 0)
    layer.getDouble("a_double", 0.0)
    layer.getLong("a_long", 0L)
    layer.getString("a_string", "err")
    layer.getArray("an_array", arrayOf<String>())
    layer.getDictionary("an_object", mapOf())
    layer.getConfig("another_object")
    Statsig.shutdown()


    val logs = logs!!
    assertEquals(8, logs.events.count())

    assertEquals("a_bool", logs.events[0].metadata?.get("parameterName"))
    assertEquals("an_int", logs.events[1].metadata?.get("parameterName"))
    assertEquals("a_double", logs.events[2].metadata?.get("parameterName"))
    assertEquals("a_long", logs.events[3].metadata?.get("parameterName"))
    assertEquals("a_string", logs.events[4].metadata?.get("parameterName"))
    assertEquals("an_array", logs.events[5].metadata?.get("parameterName"))
    assertEquals("an_object", logs.events[6].metadata?.get("parameterName"))
    assertEquals("another_object", logs.events[7].metadata?.get("parameterName"))
  }

  @Test
  fun testLogsUserAndEventName() {
    val user = StatsigUser(userID = "dloomb")
    user.email = "daniel@loomb.net"

    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99),
          "default",
        )
      ),
      user,
    )

    val layer = Statsig.getLayer("layer")
    layer.getInt("an_int", 0)
    Statsig.shutdown()

    val logs = logs!!
    assertEquals(1, logs.events.count())

    assertEquals(
      Gson().toJson(mapOf("userID" to "dloomb", "email" to "daniel@loomb.net")),
      Gson().toJson(logs.events[0].user)
    )
    assertEquals("statsig::layer_exposure", logs.events[0].eventName)
    assertEquals(null, logs.events[0].metadata!!["isManualExposure"])
  }

  @Test
  fun testDoesNotLogWithExposureLoggingDisabled() {
    val user = StatsigUser(userID = "dloomb")
    user.email = "daniel@loomb.net"

    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99),
          "default",
        )
      ),
      user,
    )

    val layer = Statsig.getLayerWithExposureLoggingDisabled("layer")
    layer.getInt("an_int", 0)
    Statsig.shutdown()

    assertNull(logs)
  }

  @Test
  fun testManualExposureLogging() {
    val user = StatsigUser(userID = "dloomb")
    user.email = "daniel@loomb.net"

    start(
      mapOf(
        "layer!" to APIDynamicConfig(
          "layer!",
          mapOf("an_int" to 99),
          "default",
        )
      ),
      user,
    )

    val layer = Statsig.getLayerWithExposureLoggingDisabled("layer")
    layer.getInt("an_int", 0)
    Statsig.manuallyLogLayerParameterExposure("layer", "an_int")
    Statsig.shutdown()

    val logs = logs!!
    assertEquals(1, logs.events.count())

    assertEquals(
      Gson().toJson(mapOf("userID" to "dloomb", "email" to "daniel@loomb.net")),
      Gson().toJson(logs.events[0].user)
    )
    assertEquals("statsig::layer_exposure", logs.events[0].eventName)
    assertEquals("true", logs.events[0].metadata!!["isManualExposure"])
  }

  private fun start(layers: Map<String, APIDynamicConfig>, user: StatsigUser= StatsigUser(userID = "jkw")) {
    val network = TestUtil.mockNetwork(
      layerConfigs = layers
    )

    TestUtil.captureLogs(network) {
      logs = it
    }
    initTime = System.currentTimeMillis()
    TestUtil.startStatsigAndWait(app, user = user, network = network)
  }
}