package com.statsig.androidsdk

import com.statsig.androidsdk.evaluator.ConfigEvaluation
import com.statsig.androidsdk.evaluator.Evaluator
import com.statsig.androidsdk.evaluator.SpecStore
import com.statsig.androidsdk.evaluator.SpecsResponse

class OnDeviceEvalAdapter(private val data: String?) {
    private val store = SpecStore()
    private val evaluator = Evaluator(store)
    private val gson = StatsigUtil.getGson()

    init {
        data?.let { setData(it) }
    }

    fun setData(data: String) {
        val specs: SpecsResponse = try {
            gson.fromJson(data, SpecsResponse::class.java)
        } catch (e: Exception) {
            println("[Statsig]: Failed to parse specs from data string.")
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
        val details = getEvaluationDetails(evaluation)

        return FeatureGate(gateName, evaluation, details)
    }

    fun getDynamicConfig(current: DynamicConfig, user: StatsigUser): DynamicConfig? {
        if (!shouldTryOnDeviceEvaluation(current.getEvaluationDetails())) {
            return null
        }

        val configName = current.getName()
        val evaluation = evaluator.evaluateConfig(configName, user)
        val details = getEvaluationDetails(evaluation)

        return DynamicConfig(configName, evaluation, details)
    }

    fun getLayer(client: StatsigClient?, current: Layer, user: StatsigUser): Layer? {
        if (!shouldTryOnDeviceEvaluation(current.getEvaluationDetails())) {
            return null
        }

        val layerName = current.getName()
        val evaluation = evaluator.evaluateLayer(layerName, user)
        val details = getEvaluationDetails(evaluation)

        return Layer(client, layerName, evaluation, details)
    }

    private fun shouldTryOnDeviceEvaluation(details: EvaluationDetails): Boolean {
        val specs = store.getRawSpecs() ?: return false
        return specs.time > details.lcut
    }

    private fun getEvaluationDetails(evaluation: ConfigEvaluation): EvaluationDetails {
        val lcut = store.getLcut() ?: 0
        if (evaluation.isUnrecognized) {
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
