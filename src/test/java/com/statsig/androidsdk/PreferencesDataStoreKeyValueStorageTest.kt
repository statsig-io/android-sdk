package com.statsig.androidsdk

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesDataStoreKeyValueStorageTest {
    private lateinit var app: Application
    private lateinit var storage: KeyValueStorage<String>

    private val storeName = "mainStore"
    private val keyName = "keyToWrite"
    private val value = "valueToRead"

    val otherStore = "otherStore"
    val otherValue = "OTHER VALUE"

    private val dispatcher = UnconfinedTestDispatcher()
    private val coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Before
    fun setup() {
        TestUtil.mockDispatchers(dispatcher)
        app = RuntimeEnvironment.getApplication()
        storage = PreferencesDataStoreKeyValueStorage(app, coroutineScope)
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.clearAll()
        }
    }

    @Test
    fun writeValue_separateStores() = runTest {
        storage.writeValue(storeName, keyName, value)
        storage.writeValue(otherStore, keyName, otherValue)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo(value)
        assertThat(storage.readValue(otherStore, keyName)).isEqualTo(otherValue)
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
        assertThat(storage.readAll(storeName)).isEqualTo(map)
    }

    @Test
    fun clearStore_clearsOneStore() = runTest {
        storage.writeValue(storeName, keyName, value)
        storage.writeValue(otherStore, keyName, otherValue)

        storage.clearStore(otherStore)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo(value)
        assertThat(storage.readValue(otherStore, keyName)).isNull()
    }

    @Test
    fun clearAll_clearsAllStores() = runTest {
        storage.writeValue(storeName, keyName, value)
        storage.writeValue(otherStore, keyName, otherValue)

        storage.clearAll()

        assertThat(storage.readValue(storeName, keyName)).isNull()
        assertThat(storage.readValue(otherStore, keyName)).isNull()
    }

    @Test
    fun readValue_noValue_returnsNull() = runTest {
        assertThat(storage.readValue(storeName, keyName)).isNull()
    }
}
