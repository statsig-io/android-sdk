package com.statsig.androidsdk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.HashMap;
import java.util.Map;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

@RunWith(RobolectricTestRunner.class)
public class StatsigFromJavaTest {
    private Application app;
    private Map<String, APIFeatureGate> gates = new HashMap<>();
    private Map<String, APIDynamicConfig> configs = new HashMap<>();
    private Map<String, APIDynamicConfig> layers = new HashMap<>();
    private final Map<String, Object> values = new HashMap<String, Object>() {
        {
            put("a_bool", true);
            put("an_int", 1);
            put("a_double", 1.0);
            put("a_long", 1L);
            put("a_string", "val");
            put("an_array", new String[] { "a", "b" });
            put("an_object", new HashMap<String, String>() {
                {
                    put("a_key", "val");
                }
            });
            put("another_object", new HashMap<String, String>() {
                {
                    put("another_key", "another_val");
                }
            });
        }
    };
    private LogEventData logs;

    @Before
    public void setup() {
        TestUtil.Companion.mockDispatchers();

        app = RuntimeEnvironment.getApplication();
        TestUtil.Companion.mockHashing();
    }

    @Test
    public void testGate() {
        gates = new HashMap<String, APIFeatureGate>() {
            {
                put("true_gate!", makeGate("true_gate!", true));
                put("false_gate!", makeGate("false_gate!", false));
            }
        };
        start();

        assertTrue(Statsig.checkGate("true_gate"));
        assertFalse(Statsig.checkGate("false_gate"));
    }

    @Test
    public void testConfig() {
        configs = new HashMap<String, APIDynamicConfig>() {
            {
                put("config!", makeConfig("config!", values));
            }
        };
        start();

        DynamicConfig config = Statsig.getConfig("config");

        assertTrue(config.getBoolean("a_bool", false));
        assertEquals(1, config.getInt("an_int", 0));
        assertEquals(1.0, config.getDouble("a_double", 0.0), 0.1);
        assertEquals(1L, config.getLong("a_long", 0L));
        assertEquals("val", config.getString("a_string", "err"));
        assertArrayEquals(new String[] { "a", "b" }, config.getArray("an_array", new String[0]));
        assertEquals(new HashMap<String, String>() {
            {
                put("a_key", "val");
            }
        }, config.getDictionary("an_object", new HashMap<String, Object>()));

        DynamicConfig another = config.getConfig("another_object");
        assertNotNull(another);
        assertEquals("another_val", another.getString("another_key", "err"));
    }

    @Test
    public void testExperiment() {
        configs = new HashMap<String, APIDynamicConfig>() {
            {
                put("exp!", makeConfig("exp!", values));
            }
        };
        start();

        DynamicConfig experiment = Statsig.getExperiment("exp");

        assertTrue(experiment.getBoolean("a_bool", false));
        assertEquals(1, experiment.getInt("an_int", 0));
        assertEquals(1.0, experiment.getDouble("a_double", 0.0), 0.1);
        assertEquals(1L, experiment.getLong("a_long", 0L));
        assertEquals("val", experiment.getString("a_string", "err"));
        assertArrayEquals(new String[] { "a", "b" }, experiment.getArray("an_array", new String[0]));
        assertEquals(new HashMap<String, String>() {
            {
                put("a_key", "val");
            }
        }, experiment.getDictionary("an_object", new HashMap<String, Object>()));

        DynamicConfig another = experiment.getConfig("another_object");
        assertNotNull(another);
        assertEquals("another_val", another.getString("another_key", "err"));
    }

    @Test
    public void testLayer() {
        layers = new HashMap<String, APIDynamicConfig>() {
            {
                put("layer!", makeConfig("layer!", values));
            }
        };
        start();

        Layer layer = Statsig.getLayer("layer");

        assertTrue(layer.getBoolean("a_bool", false));
        assertEquals(1, layer.getInt("an_int", 0));
        assertEquals(1.0, layer.getDouble("a_double", 0.0), 0.1);
        assertEquals(1L, layer.getLong("a_long", 0L));
        assertEquals("val", layer.getString("a_string", "err"));
        assertArrayEquals(new String[] { "a", "b" }, layer.getArray("an_array", new String[0]));
        assertEquals(new HashMap<String, String>() {
            {
                put("a_key", "val");
            }
        }, layer.getDictionary("an_object", new HashMap<String, Object>()));

        DynamicConfig config = layer.getConfig("another_object");
        assertNotNull(config);
        assertEquals("another_val", config.getString("another_key", "err"));
    }

    @Test
    public void testLogging() {
        start();

        Statsig.logEvent("test-event");
        Statsig.shutdown();

        assertEquals("test-event", logs.getEvents().get(1).getEventName());
    }

    private void start() {
        StatsigNetwork network = TestUtil.Companion.mockNetwork(
                gates,
                configs,
                layers,
                null,
                true,
                null, null);

        TestUtil.Companion.captureLogs(network, new Function1<LogEventData, Unit>() {
            @Override
            public Unit invoke(LogEventData logEventData) {
                logs = logEventData;
                return null;
            }
        });

        TestUtil.Companion.startStatsigAndWait(
                app,
                new StatsigUser("dloomb"),
                new StatsigOptions(),
                network);
    }

    private APIFeatureGate makeGate(String name, Boolean value) {
        return new APIFeatureGate(
                name,
                value,
                "default",
                null,
                new Map[] {},
                null);
    }

    private APIDynamicConfig makeConfig(String name, Map<String, Object> values) {
        return new APIDynamicConfig(
                name,
                values,
                "default",
                null,
                new Map[] {},
                new Map[] {},
                false,
                true,
                true,
                null,
                new String[] {},
                null,
                null);
    }
}
