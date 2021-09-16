package com.statsig.androidsdk

internal class StatsigState(private val initializeResponse: InitializeResponse) {
    fun checkGate(gateName: String): APIFeatureGate {
        if (
            initializeResponse.featureGates == null ||
            !initializeResponse.featureGates.containsKey(gateName)) {
            return APIFeatureGate(gateName, false, "")
        }
        return initializeResponse.featureGates[gateName] ?: APIFeatureGate(gateName, false, "")
    }

    fun getConfig(configName: String): DynamicConfig {
        if (
            initializeResponse.configs == null ||
            !initializeResponse.configs.containsKey(configName)) {
            return DynamicConfig(configName)
        }
        var config = initializeResponse.configs[configName]
        return DynamicConfig(configName, config?.value ?: mapOf(), config?.ruleID ?: "")
    }
}
