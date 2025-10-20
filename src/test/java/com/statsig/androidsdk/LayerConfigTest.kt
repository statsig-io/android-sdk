package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
class LayerConfigTest {
    private lateinit var app: Application
    private lateinit var sharedPrefs: SharedPreferences
    private var client: StatsigClient = StatsigClient()
    private lateinit var layer: Layer
    private val nullUser = StatsigUser(null)

    @Before
    internal fun setup() = runBlocking {
        app = RuntimeEnvironment.getApplication()
        sharedPrefs = TestUtil.getTestSharedPrefs(app)

        TestUtil.mockHashing()
        TestUtil.mockDispatchers()

        initClient()

        layer = Layer(
            client,
            "a_layer",
            EvaluationDetails(EvaluationReason.Network, lcut = 0),
            TestUtil.getConfigValueMap(),
            "default"
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
        val dummyConfig =
            Layer(client, "", EvaluationDetails(EvaluationReason.Unrecognized, lcut = 0))
        assertEquals("provided default", dummyConfig.getString("test", "provided default"))
        assertEquals(true, dummyConfig.getBoolean("test", true))
        assertEquals(12, dummyConfig.getInt("test", 12))
        assertEquals("hello world", dummyConfig.getString("test", "hello world"))
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
        val emptyConfig = Layer(
            client,
            "test_config",
            EvaluationDetails(EvaluationReason.Uninitialized, lcut = 0),
            mapOf(),
            "default"
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
        assertArrayEquals(
            arrayOf(true, false),
            layer.getArray("testBooleanArray", arrayOf(1, "one"))
        )
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
                "nestedLong" to 13L,
                "nestedEmptyDict" to Collections.EMPTY_MAP
            ),
            layer.getDictionary("testNested", mapOf())
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
            TestUtil.makeInitializeResponse(
                layerConfigs = mapOf(
                    "allocated_layer!" to APIDynamicConfig(
                        "allocated_layer!",
                        mapOf(
                            "string" to "default_string"
                        ),
                        "default",
                        isExperimentActive = true,
                        isUserInExperiment = true
                    )
                )
            ),
            nullUser
        )
        client.getStore().persistStickyValues()

        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))
    }

    @Test
    fun testGettingStickyValuesAcrossSessions() = runBlocking {
        var config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))
        client.getStore().persistStickyValues()

        client.getStore().save(
            TestUtil.makeInitializeResponse(
                layerConfigs = mapOf(
                    "allocated_layer!" to APIDynamicConfig(
                        "allocated_layer!",
                        mapOf(
                            "string" to "default_string"
                        ),
                        "default",
                        isExperimentActive = true,
                        isUserInExperiment = true
                    )
                )
            ),
            nullUser
        )
        client.shutdown()
        initClient()
        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))
    }

    @Test
    fun testGettingStickyValuesWhenUserIsNoLongerInExperiment() = runBlocking {
        var config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))

        val updatedLayerResponse = TestUtil.makeInitializeResponse(
            layerConfigs = mapOf(
                "allocated_layer!" to APIDynamicConfig(
                    "allocated_layer!",
                    mapOf(
                        "string" to "default_string"
                    ),
                    "default",
                    isExperimentActive = true,
                    isUserInExperiment = false,
                    allocatedExperimentName = "different_exp!"
                )
            )
        )

        client.getStore().save(updatedLayerResponse, nullUser)
        client.getStore().persistStickyValues()

        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals(
            "Layer allocation changed, but should still get original sticky value",
            "test",
            config.getString("string", "ERR")
        )

        client.getStore().save(
            TestUtil.makeInitializeResponse(
                dynamicConfigs = mapOf(
                    "layer_exp!" to APIDynamicConfig(
                        "layer_exp!",
                        mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
                        "exp_rule",
                        "exp_group",
                        arrayOf(),
                        isExperimentActive = false,
                        isUserInExperiment = true
                    )
                ),
                layerConfigs = updatedLayerResponse.layerConfigs!!
            ),
            nullUser
        )
        client.getStore().persistStickyValues()

        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals(
            "The original sticky Experiment is no longer active, should return updated Layer value",
            "default_string",
            config.getString("string", "ERR")
        )
    }

    @Test
    fun testGettingStickyValuesWhenUserIsNowInDifferentExperiment() = runBlocking {
        var config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))

        val response = TestUtil.makeInitializeResponse(
            layerConfigs = mapOf(
                "allocated_layer!" to APIDynamicConfig(
                    "allocated_layer!",
                    mapOf(
                        "string" to "default_string"
                    ),
                    "default",
                    isExperimentActive = true,
                    isUserInExperiment = true,
                    allocatedExperimentName = "completely_different_exp"
                )
            )
        )

        client.getStore().save(response, nullUser)
        client.getStore().persistStickyValues()

        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals(
            "Should still return the original sticky value because the original experiment is still active",
            "test",
            config.getString("string", "ERR")
        )

        (response.configs as MutableMap)["layer_exp!"] = APIDynamicConfig(
            "layer_exp!",
            mutableMapOf("string" to "test", "number" to 42, "otherNumber" to 17),
            "exp_rule",
            "exp_group",
            arrayOf(),
            isExperimentActive = false,
            isUserInExperiment = true
        )
        client.getStore().save(response, nullUser)
        client.getStore().persistStickyValues()

        config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals(
            "Should get updated value because the original experiment is no longer active",
            "default_string",
            config.getString("string", "ERR")
        )
    }

    @Test
    fun testWipingStickyValues() = runBlocking {
        var config = client.getLayer("allocated_layer", keepDeviceValue = true)
        assertEquals("test", config.getString("string", "ERR"))

        client.getStore().save(
            TestUtil.makeInitializeResponse(
                layerConfigs = mapOf(
                    "allocated_layer!" to APIDynamicConfig(
                        "allocated_layer!",
                        mapOf(
                            "string" to "default_string"
                        ),
                        "default",
                        isExperimentActive = true,
                        isUserInExperiment = true
                    )
                )
            ),
            nullUser
        )
        client.getStore().persistStickyValues()

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
