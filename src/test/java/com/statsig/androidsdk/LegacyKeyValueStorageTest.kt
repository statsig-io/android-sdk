package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LegacyKeyValueStorageTest {
    private lateinit var app: Application
    private lateinit var storage: KeyValueStorage<String>

    private val storeName = "This value is ignored by the Legacy implementation"
    private val keyName = "keyToWrite"
    private val value = "valueToRead"

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        storage = LegacyKeyValueStorage(app)
    }

    @Test
    fun writeValue_writesToBackingStorage() = runTest {
        storage.writeValue(storeName, keyName, value)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo(value)
        val newStorage = LegacyKeyValueStorage(app)
        assertThat(newStorage.readValue(storeName, keyName)).isEqualTo(value)
    }

    @Test
    fun writeValues_writesAllValues() = runTest {
        val expectedSize = 10
        val range = 1..expectedSize
        val map = range.associate { "Key$it" to "Val$it" }

        storage.writeValues(storeName, map)

        assertThat(storage.readAll(storeName)).hasSize(expectedSize)
        for (i in range) {
            assertThat(storage.readValue(storeName, "Key$i")).isEqualTo("Val$i")
        }
    }

    @Test
    fun readValue_pullsPreExistingValueFromStorage() = runTest {
        val preExistingStorage = LegacyKeyValueStorage(app)
        preExistingStorage.writeValue(storeName, keyName, value)

        assertThat(
            storage.readValue(storeName, keyName)
        ).isEqualTo(value)
    }

    @Test
    fun readValue_noValue_returnsNull() = runTest {
        assertThat(storage.readValue(storeName, keyName)).isNull()
    }

    @Test
    fun readAll_returnsAll() = runTest {
        storage.writeValues(
            storeName,
            mapOf(keyName to value, "extra key" to "extra value")
        )

        assertThat(storage.readAll(storeName)).hasSize(2)
        assertThat(storage.readAll(storeName)[keyName]).isEqualTo(value)
    }

    @Test
    fun clear_clearsStorage() = runTest {
        storage.writeValues(
            storeName,
            mapOf(keyName to value, "extra key" to "extra value")
        )

        assertThat(storage.readAll(storeName)).isNotEmpty()
        storage.clearAll()
        assertThat(storage.readAll(storeName)).isEmpty()
    }
}
