package com.statsig.androidsdk.evaluator

internal class SpecStore {
    private var rawSpecs: SpecsResponse? = null
    private var gates: Map<String, Spec> = mapOf()
    private var configs: Map<String, Spec> = mapOf()
    private var layers: Map<String, Spec> = mapOf()

    fun getRawSpecs(): SpecsResponse? {
        return rawSpecs
    }

    fun getLcut(): Long? {
        return rawSpecs?.time
    }

    fun setSpecs(specs: SpecsResponse) {
        rawSpecs = specs

        gates = specs.featureGates.associateBy { it.name }
        configs = specs.dynamicConfigs.associateBy { it.name }
        layers = specs.layerConfigs.associateBy { it.name }
    }

    fun getGate(name: String): Spec? {
        return gates[name]
    }

    fun getConfig(name: String): Spec? {
        return configs[name]
    }

    fun getLayer(name: String): Spec? {
        return layers[name]
    }
}
