package com.statsig.androidsdk

import android.util.Log
import com.statsig.androidsdk.StatsigClient.Companion.KeyValueStorageImplementation.LEGACY
import com.statsig.androidsdk.StatsigClient.Companion.KeyValueStorageImplementation.MIGRATION

/**
 * Data class representing a customer-integrated SDK experiment.
 * These are SDK features where the impact of a rollout is best measured in a customer's
 * dedicated Statsig project rather than Statsig's diagnostics.
 *
 * Statsig sets an SDK flag to a target experiment.
 */
internal class IntegratedSdkExperiments {
    companion object {
        const val STORAGE_MIGRATION_SDK_CONFIG_KEY = "store_g"
        const val STORE_NAME = "integrated_sdk_experiments"
        const val STORAGE_MIGRATION_KEY = "storage_migration_value"
    }

    private lateinit var storage: KeyValueStorage<String>

    fun initialize(storage: KeyValueStorage<String>) {
        this.storage = storage
    }
    internal fun getStorageImplementation(): StatsigClient.Companion.KeyValueStorageImplementation {
        try {
            val storageMigrationValue =
                storage.readValueSync(STORE_NAME, STORAGE_MIGRATION_KEY)
                    ?: LEGACY.name
            return StatsigClient.Companion.KeyValueStorageImplementation.entries.first {
                it.name == storageMigrationValue
            }
        } catch (e: RuntimeException) {
            // swallow parsing failures; return LEGACY
            Log.e("statsig::SdkExperiments", "failed to read storage migration value", e)
        }

        return LEGACY
    }

    internal suspend fun processSdkConfigs(sdkConfigs: Map<String, Any>, client: StatsigClient) {
        if (sdkConfigs.containsKey(STORAGE_MIGRATION_SDK_CONFIG_KEY)) {
            val gateToCheck = sdkConfigs[STORAGE_MIGRATION_SDK_CONFIG_KEY] as String
            val priorWrittenValue = storage.readValue(STORE_NAME, STORAGE_MIGRATION_KEY)
            val shouldMigrate = client.checkGateWithExposureLoggingDisabled(gateToCheck)
            val implToWrite = if (shouldMigrate) {
                MIGRATION.name
            } else {
                LEGACY.name
            }
            if (priorWrittenValue == implToWrite) {
                // Only log an exposure if the values are consistent - we don't want to log
                // for sessions that didn't start and load storage with the appropriate value
                client.manuallyLogGateExposure(gateToCheck)
            } else {
                storage.writeValue(
                    STORE_NAME,
                    STORAGE_MIGRATION_KEY,
                    implToWrite
                )
            }
        } else {
            storage.removeValue(STORE_NAME, STORAGE_MIGRATION_KEY)
        }
    }
}
