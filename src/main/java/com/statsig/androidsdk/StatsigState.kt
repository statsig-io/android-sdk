package com.statsig.androidsdk

class StatsigState(private val initializeResponse: InitializeResponse) {
    fun checkGate(gateName: String): FeatureGate? {
        if (initializeResponse.featureGates == null) {
            return null
        }
        return initializeResponse.featureGates[gateName];
    }

    fun getConfig(configName: String): DynamicConfig? {
        if (initializeResponse.configs == null) {
            return null
        }
        val config = initializeResponse.configs[configName] ?: return null
        return DynamicConfig(config)
    }
}