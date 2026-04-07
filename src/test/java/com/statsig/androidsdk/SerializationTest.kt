package com.statsig.androidsdk

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
* Gson serialization and deserialization does not use default value
* set in data class when a field is missing
* */
class SerializationTest {
    val gson = Gson()

    @Test
    fun testSerializeResponseWithIncomplete() {
        val initializeResponseSkipFields = "{\"feature_gates\":{\"245595137\":{\"name\":\"245595137\",\"value\":true,\"rule_id\":\"1uj9J1jxY2jnBAChgGB1jR:0.00:35\",\"id_type\":\"userID\"}},\"dynamic_configs\":{\"2887220988\":{\"name\":\"2887220988\",\"value\":{\"num\": 13},\"rule_id\":\"prestart\",\"group\":\"prestart\",\"is_device_based\":false,\"id_type\":\"userID\",\"is_experiment_active\":true,\"is_user_in_experiment\":true}},\"layer_configs\":{},\"sdkParams\":{},\"has_updates\":true,\"time\":1717536742309,\"company_lcut\":1717536742309,\"hash_used\":\"djb2\"}"
        val parsedResponse = gson.fromJson(
            initializeResponseSkipFields,
            InitializeResponse.SuccessfulInitializeResponse::class.java
        )
        val gate =
            FeatureGate(
                "some_gate",
                parsedResponse.featureGates!!.get("245595137")!!,
                EvalDetails(EvalSource.Error, EvalReason.Unrecognized, lcut = 0)
            )
        val config =
            DynamicConfig(
                "some_config",
                parsedResponse.configs!!.get("2887220988")!!,
                EvalDetails(EvalSource.Error, EvalReason.Unrecognized, lcut = 0)
            )
        assert(gate.getValue())
        assert(gate.getSecondaryExposures().isEmpty())
        assert(config.getInt("num", 0) == 13)
        assert(config.getSecondaryExposures().isEmpty())
    }

    @Test
    fun testSerializeLoggingEvalDetailsIncludesSyncTimesWhenAvailable() {
        val details = EvalDetails(
            EvalSource.Network,
            EvalReason.Recognized,
            lcut = 123L,
            receivedAt = 456L
        )

        val serialized = gson.toJson(details.toLoggingEvaluationDetails())
        val parsed: Map<String, Any> = gson.fromJson(
            serialized,
            object : TypeToken<Map<String, Any>>() {}.type
        )

        assertEquals("Network:Recognized", parsed["reason"])
        assertEquals(123.0, parsed["lcut"])
        assertEquals(456.0, parsed["receivedAt"])
    }

    @Test
    fun testSerializeLoggingEvalDetailsOmitsSyncTimesForNoValues() {
        val details = EvalDetails(
            EvalSource.NoValues,
            null,
            lcut = 0L,
            receivedAt = 0L
        )

        val serialized = gson.toJson(details.toLoggingEvaluationDetails())
        val parsed: Map<String, Any> = gson.fromJson(
            serialized,
            object : TypeToken<Map<String, Any>>() {}.type
        )

        assertEquals("NoValues", parsed["reason"])
        assertFalse(parsed.containsKey("lcut"))
        assertFalse(parsed.containsKey("receivedAt"))
    }
}
