package com.statsig.androidsdk

import android.util.Log
import com.statsig.androidsdk.evaluator.Evaluator
import com.statsig.androidsdk.evaluator.SpecStore
import com.statsig.androidsdk.evaluator.SpecsResponse

class OnDeviceEvalAdapter(private val data: String?) {
    private companion object {
        private const val TAG: String = "statsig::OnDeviceEval"
    }
    private val store = SpecStore()
    private val evaluator = Evaluator(store)

    private var receivedAt: Long = 0L

    init {
        data?.let { setData(it) }
    }

    fun setData(data: String) {
        val specs: SpecsResponse = try {
            StatsigUtil.getOrBuildGson().fromJson(data, SpecsResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse specs from data string")
            return
        }
        receivedAt = System.currentTimeMillis()
        store.setSpecs(specs)
    }

    fun getGate(current: FeatureGate, user: StatsigUser): FeatureGate? {
        if (!shouldTryOnDeviceEvaluation(current.getEvalDetails())) {
            return null
        }

        val gateName = current.getName()
        val evaluation = evaluator.evaluateGate(gateName, user)
        val details = getEvalDetails(evaluation.isUnrecognized)

        return FeatureGate(gateName, evaluation, details)
    }

    fun getDynamicConfig(current: DynamicConfig, user: StatsigUser): DynamicConfig? {
        if (!shouldTryOnDeviceEvaluation(current.getEvalDetails())) {
            return null
        }

        val configName = current.getName()
        val evaluation = evaluator.evaluateConfig(configName, user)
        val details = getEvalDetails(evaluation.isUnrecognized)

        return DynamicConfig(configName, evaluation, details)
    }

    fun getLayer(client: StatsigClient?, current: Layer, user: StatsigUser): Layer? {
        if (!shouldTryOnDeviceEvaluation(current.getEvalDetails())) {
            return null
        }

        val layerName = current.getName()
        val evaluation = evaluator.evaluateLayer(layerName, user)
        val details = getEvalDetails(evaluation.isUnrecognized)

        return Layer(client, layerName, evaluation, details)
    }

    fun getParamStore(client: StatsigClient, current: ParameterStore): ParameterStore? {
        if (!shouldTryOnDeviceEvaluation(current.getEvalDetails())) {
            return null
        }

        val spec = store.getParamStore(current.name)
        val details = getEvalDetails(spec == null)

        return ParameterStore(client, spec?.parameters ?: mapOf(), current.name, details, null)
    }

    private fun shouldTryOnDeviceEvaluation(details: EvalDetails): Boolean {
        val specs = store.getRawSpecs() ?: return false
        return specs.time > (details.lcut ?: 0)
    }

    private fun getEvalDetails(isUnrecognized: Boolean): EvalDetails {
        val lcut = store.getLcut() ?: 0
        if (isUnrecognized) {
            return EvalDetails(
                EvalSource.OnDeviceEvalAdapterBootstrap,
                EvalReason.Unrecognized,
                lcut = lcut,
                receivedAt = receivedAt
            )
        }

        return EvalDetails(
            EvalSource.OnDeviceEvalAdapterBootstrap,
            EvalReason.Recognized,
            lcut = lcut,
            receivedAt = receivedAt
        )
    }
}
