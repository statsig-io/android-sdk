package com.statsig.androidsdk

import android.app.Application
import android.content.SharedPreferences
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
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var storage: KeyValueStorage<String>

    private val storeName = "This value is ignored by the Legacy implementation"
    private val keyName = "keyToWrite"
    private val value = "valueToRead"

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        sharedPrefs = TestUtil.getTestSharedPrefs(app)
        storage = LegacyKeyValueStorage(app)
    }

    @Test
    fun writeValue_writesToBackingSharedPrefs() = runTest {
        storage.writeValue(storeName, keyName, value)

        assertThat(storage.readValue(storeName, keyName)).isEqualTo(value)
        assertThat(sharedPrefs.getString(keyName, "")).isEqualTo(value)
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
    fun readValue_pullsPreExistingValueFromSharedPrefs() = runTest {
        sharedPrefs.edit().putString(keyName, value).apply()

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
        sharedPrefs.edit().putString(keyName, value).putString("extra key", "extra value").apply()

        assertThat(storage.readAll(storeName)).hasSize(2)
        assertThat(storage.readAll(storeName)[keyName]).isEqualTo(value)
    }

    @Test
    fun clear_clearsStorage() = runTest {
        sharedPrefs.edit().putString(keyName, value).putString("extra key", "extra value").apply()

        assertThat(sharedPrefs.all).isNotEmpty()
        storage.clearAll()
        assertThat(sharedPrefs.all).isEmpty()
        assertThat(storage.readAll(storeName)).isEmpty()
    }
}
