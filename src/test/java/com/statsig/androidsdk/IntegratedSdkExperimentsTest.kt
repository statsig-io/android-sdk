package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IntegratedSdkExperimentsTest {

    private lateinit var app: Application
    private lateinit var storage: KeyValueStorage<String>
    private lateinit var experiments: IntegratedSdkExperiments

    private val storeName = IntegratedSdkExperiments.STORE_NAME
    private val keyName = IntegratedSdkExperiments.STORAGE_MIGRATION_KEY

    private val gateName = "TEST_CONFIG_GATE"

    private val sdkConfigs =
        mapOf<String, Any>(IntegratedSdkExperiments.STORAGE_MIGRATION_SDK_CONFIG_KEY to gateName)

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        storage = InMemoryKeyValueStorage()
        experiments = IntegratedSdkExperiments()
        experiments.initialize(storage)
    }

    @Test
    fun getStorageImplementation_noValuePresent_returnsLegacy() {
        assertThat(
            experiments.getStorageImplementation()
        ).isEqualTo(StatsigClient.Companion.KeyValueStorageImplementation.LEGACY)
    }

    @Test
    fun getStorageImplementation_badValue_returnsLegacy() = runTest {
        storage.writeValue(storeName, keyName, "THIS_IS_NOT_A_VALID_VALUE")

        assertThat(
            experiments.getStorageImplementation()
        ).isEqualTo(StatsigClient.Companion.KeyValueStorageImplementation.LEGACY)
    }

    @Test
    fun getStorageImplementation_goodValue_returnsCorrectValue() = runTest {
        StatsigClient.Companion.KeyValueStorageImplementation.entries.forEach {
            storage.writeValue(storeName, keyName, it.name)

            assertThat(experiments.getStorageImplementation()).isEqualTo(it)
        }
    }

    @Test
    fun checkForMigration_configPresentAndGatePass_storesHint() = runTest {
        val mockClient = mockk<StatsigClient>()
        coEvery { mockClient.checkGate(gateName) } answers { true }
        coEvery { mockClient.checkGateWithExposureLoggingDisabled(gateName) } answers { true }

        experiments.processSdkConfigs(sdkConfigs, mockClient)
        coVerify(exactly = 1) { mockClient.checkGateWithExposureLoggingDisabled(gateName) }
        assertThat(
            storage.readValue(storeName, keyName)
        ).isEqualTo(StatsigClient.Companion.KeyValueStorageImplementation.MIGRATION.name)
    }

    @Test
    fun checkForMigration_configPresent_noGate_writesLegacy() = runTest {
        val mockClient = mockk<StatsigClient>()
        storage.writeValue(
            storeName,
            keyName,
            StatsigClient.Companion.KeyValueStorageImplementation.MIGRATION.name
        )
        coEvery { mockClient.checkGateWithExposureLoggingDisabled(gateName) } answers { false }

        experiments.processSdkConfigs(sdkConfigs, mockClient)

        coVerify(exactly = 1) { mockClient.checkGateWithExposureLoggingDisabled(gateName) }
        assertThat(
            storage.readValue(storeName, keyName)
        ).isEqualTo(StatsigClient.Companion.KeyValueStorageImplementation.LEGACY.name)
    }

    @Test
    fun checkForMigration_noConfig_clearsBit() = runTest {
        val mockClient = mockk<StatsigClient>()
        storage.writeValue(
            storeName,
            keyName,
            StatsigClient.Companion.KeyValueStorageImplementation.MIGRATION.name
        )

        experiments.processSdkConfigs(emptyMap(), mockClient)

        coVerify { mockClient wasNot Called }
        assertThat(storage.readValue(storeName, keyName)).isNull()
    }

    @After
    fun teardown() {
        TestUtil.reset()
    }
}
