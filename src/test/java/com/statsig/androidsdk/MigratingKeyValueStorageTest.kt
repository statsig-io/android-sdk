package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MigratingKeyValueStorageTest {
    private lateinit var app: Application
    private lateinit var legacyStorage: LegacyKeyValueStorage

    private val storeName = "store"
    private val keyName = "key"

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        legacyStorage = LegacyKeyValueStorage(app)
    }

    @After
    fun tearDown() = runTest {
        legacyStorage.clearAll()
    }

    @Test
    fun readValue_prefersPrimary() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        primary.writeValue(storeName, keyName, "new")
        legacy.writeValue(storeName, keyName, "old")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo("new")
        assertThat(primary.readValue(storeName, keyName)).isEqualTo("new")
        assertThat(legacy.readValue(storeName, keyName)).isEqualTo("old")
    }

    @Test
    fun readValue_migratesFromLegacy() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        legacy.writeValue(storeName, keyName, "old")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo("old")
        assertThat(primary.readValue(storeName, keyName)).isEqualTo("old")
        assertThat(legacy.readValue(storeName, keyName)).isNull()
    }

    @Test
    fun readAll_mergesAndMigrates() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        primary.writeValue(storeName, "primaryKey", "primaryValue")
        legacy.writeValue(storeName, "legacyKey", "legacyValue")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)

        val merged = storage.readAll(storeName)

        assertThat(merged).containsExactly(
            "primaryKey",
            "primaryValue",
            "legacyKey",
            "legacyValue"
        )
        assertThat(primary.readValue(storeName, "legacyKey")).isEqualTo("legacyValue")
        assertThat(legacy.readAll(storeName)).isEmpty()
    }

    @Test
    fun writeValue_writesOnlyToPrimary() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)

        storage.writeValue(storeName, keyName, "value")

        assertThat(primary.readValue(storeName, keyName)).isEqualTo("value")
        assertThat(legacy.readValue(storeName, keyName)).isNull()
    }

    @Test
    fun removeValue_removesFromLegacyToPreventFallback() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        legacy.writeValue(storeName, keyName, "old")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)
        storage.removeValue(storeName, keyName)

        assertThat(storage.readValue(storeName, keyName)).isNull()
        assertThat(legacy.readValue(storeName, keyName)).isNull()
    }

    @Test
    fun clearStore_disablesLegacyFallback() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        legacy.writeValue(storeName, keyName, "old")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)
        storage.clearStore(storeName)

        assertThat(storage.readValue(storeName, keyName)).isNull()
        assertThat(primary.readAll(storeName)).isEmpty()
    }

    @Test
    fun clearAll_clearsPrimaryAndLegacy() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        primary.writeValue(storeName, keyName, "primary")
        legacy.writeValue(storeName, "legacyKey", "legacy")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)
        storage.clearAll()

        assertThat(primary.readAll(storeName)).isEmpty()
        assertThat(legacy.readAll(storeName)).isEmpty()
    }

    @Test
    fun readAll_setsMarkerAndSkipsLegacyAfterMigration() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        legacy.writeValue(storeName, "legacyKey", "legacyValue")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)
        storage.readAll(storeName)

        legacy.writeValue(storeName, "lateKey", "lateValue")

        assertThat(storage.readValue(storeName, "lateKey")).isNull()
        assertThat(primary.readValue(storeName, "lateKey")).isNull()
    }

    @Test
    fun readValue_doesNotSkipLegacyForNonLegacyImpl() = runTest {
        val primary = InMemoryKeyValueStorage()
        val legacy = InMemoryKeyValueStorage()
        legacy.writeValue(storeName, keyName, "legacyValue")

        val storage = MigratingKeyValueStorage(primary, legacy, markerStorage = primary)
        assertThat(storage.readValue(storeName, keyName)).isEqualTo("legacyValue")

        legacy.writeValue(storeName, "lateKey", "lateValue")

        assertThat(storage.readValue(storeName, "lateKey")).isEqualTo("lateValue")
        assertThat(primary.readValue(storeName, "lateKey")).isEqualTo("lateValue")
    }

    @Test
    fun readValue_setsMarkerAndSkipsLegacyForLegacyImpl() = runTest {
        val primary = InMemoryKeyValueStorage()
        val markerStorage = InMemoryKeyValueStorage()
        val storage =
            MigratingKeyValueStorage(primary, legacyStorage, markerStorage = markerStorage)

        legacyStorage.writeValue(storeName, keyName, "legacyValue")

        assertThat(storage.readValue(storeName, keyName)).isEqualTo("legacyValue")

        legacyStorage.writeValue(storeName, "lateKey", "lateValue")

        assertThat(storage.readValue(storeName, "lateKey")).isNull()
        assertThat(primary.readValue(storeName, "lateKey")).isNull()
    }

    @Test
    fun readAll_doesNotSetMarkerForLegacyImpl() = runTest {
        val primary = InMemoryKeyValueStorage()
        val markerStorage = InMemoryKeyValueStorage()
        val storage =
            MigratingKeyValueStorage(primary, legacyStorage, markerStorage = markerStorage)

        legacyStorage.writeValue(storeName, keyName, "legacyValue")

        storage.readAll(storeName)

        legacyStorage.writeValue(storeName, "lateKey", "lateValue")

        assertThat(storage.readValue(storeName, "lateKey")).isEqualTo("lateValue")
        assertThat(primary.readValue(storeName, "lateKey")).isEqualTo("lateValue")
    }
}
