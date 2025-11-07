package com.statsig.androidsdk

import android.util.Log
import com.google.gson.Gson
import com.statsig.androidsdk.evaluator.Evaluator
import com.statsig.androidsdk.evaluator.SpecStore
import com.statsig.androidsdk.evaluator.SpecsResponse

class OnDeviceEvalAdapter(private val data: String?, private val gson: Gson) {
    companion object {
        private const val TAG: String = "statsig::OnDeviceEval"
    }
    private val store = SpecStore()
    private val evaluator = Evaluator(store)

    init {
        data?.let { setData(it) }
    }

    fun setData(data: String) {
        val specs: SpecsResponse = try {
            gson.fromJson(data, SpecsResponse::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse specs from data string")
            return
        }

        store.setSpecs(specs)
    }

    fun getGate(current: FeatureGate, user: StatsigUser): FeatureGate? {
        if (!shouldTryOnDeviceEvaluation(current.getEvaluationDetails())) {
            return null
        }

        val gateName = current.getName()
        val evaluation = evaluator.evaluateGate(gateName, user)
        val details = getEvaluationDetails(evaluation.isUnrecognized)

        return FeatureGate(gateName, evaluation, details)
    }

    fun getDynamicConfig(current: DynamicConfig, user: StatsigUser): DynamicConfig? {
        if (!shouldTryOnDeviceEvaluation(current.getEvaluationDetails())) {
            return null
        }

        val configName = current.getName()
        val evaluation = evaluator.evaluateConfig(configName, user)
        val details = getEvaluationDetails(evaluation.isUnrecognized)

        return DynamicConfig(configName, evaluation, details)
    }

    fun getLayer(client: StatsigClient?, current: Layer, user: StatsigUser): Layer? {
        if (!shouldTryOnDeviceEvaluation(current.getEvaluationDetails())) {
            return null
        }

        val layerName = current.getName()
        val evaluation = evaluator.evaluateLayer(layerName, user)
        val details = getEvaluationDetails(evaluation.isUnrecognized)

        return Layer(client, layerName, evaluation, details)
    }

    fun getParamStore(client: StatsigClient, current: ParameterStore): ParameterStore? {
        if (!shouldTryOnDeviceEvaluation(current.evaluationDetails)) {
            return null
        }

        val spec = store.getParamStore(current.name)
        val details = getEvaluationDetails(spec == null)

        return ParameterStore(client, spec?.parameters ?: mapOf(), current.name, details, null)
    }

    private fun shouldTryOnDeviceEvaluation(details: EvaluationDetails): Boolean {
        val specs = store.getRawSpecs() ?: return false
        return specs.time > details.lcut
    }

    private fun getEvaluationDetails(isUnrecognized: Boolean): EvaluationDetails {
        val lcut = store.getLcut() ?: 0
        if (isUnrecognized) {
            return EvaluationDetails(
                EvaluationReason.OnDeviceEvalAdapterBootstrapUnrecognized,
                lcut = lcut
            )
        }

        return EvaluationDetails(
            EvaluationReason.OnDeviceEvalAdapterBootstrapRecognized,
            lcut = lcut
        )
    }
}
