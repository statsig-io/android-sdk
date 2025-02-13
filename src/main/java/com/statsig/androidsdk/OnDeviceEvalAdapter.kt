package com.statsig.androidsdk

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

        return null
    }

    private fun shouldTryOnDeviceEvaluation(details: EvaluationDetails): Boolean {
        val specs = store.getRawSpecs() ?: return false
        return specs.time > details.lcut
    }
}
