package com.statsig.androidsdk

class StatsigState(private val initializeResponse: InitializeResponse) {
    fun checkGate(gateName: String): Boolean {
        if (initializeResponse.gates == null) {
            return false
        }
        return initializeResponse.gates[gateName] == true;
    }

    fun getConfig(configName: String): DynamicConfig? {
        if (initializeResponse.configs == null) {
            return null
        }
        val config = initializeResponse.configs[configName] ?: return null
        return DynamicConfig(config)
    }
}