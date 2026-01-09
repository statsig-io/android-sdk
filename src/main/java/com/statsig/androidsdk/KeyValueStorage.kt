package com.statsig.androidsdk

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal interface KeyValueStorage<T> {
    /**
     * Write a value to storage
     */
    suspend fun writeValue(storeName: String, key: String, value: T)

    /**
     * Write multiple values to storage in a single transaction
     */
    suspend fun writeValues(storeName: String, entries: Map<String, T>)

    /**
     * Read a value from storage
     */
    suspend fun readValue(storeName: String, key: String): T?

    /**
     * Remove a key and its associated value from storage
     */
    suspend fun removeValue(storeName: String, key: String)

    /**
     * Remove all key:value pairs associated with [storeName]
     */
    suspend fun clearStore(storeName: String)

    /**
     * Remove all key:value pairs for every sub-store controlled by this [KeyValueStorage]
     */
    suspend fun clearAll()

    /**
     * Return all key:value pairs stored under [storeName]
     */
    suspend fun readAll(storeName: String): Map<String, T>
}

/**
 * Extension helper for reading synchronously.
 * Heavily discouraged and only present for consistency with certain legacy behaviors.
 */
@Deprecated("Exists for migrating legacy sync behavior only - prefer readValue()")
internal fun <T> KeyValueStorage<T>.readValueSync(storeName: String, key: String): T? =
    runBlocking { this@readValueSync.readValue(storeName, key) }

/**
 * [KeyValueStorage] with identical behavior to the prior storage methodology.
 * Every key:value pair is read from and written to a single underlying [SharedPreferences]
 *
 * This means the backing storage is NOT actually divided into multiple subfiles/sub-stores
 */
class LegacyKeyValueStorage(val context: Context) : KeyValueStorage<String> {
    companion object {
        @VisibleForTesting
        internal const val SHARED_PREFERENCES_KEY: String = "com.statsig.androidsdk"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        SHARED_PREFERENCES_KEY,
        Context.MODE_PRIVATE
    )

    private val dispatcherProvider by lazy { CoroutineDispatcherProvider() }

    override suspend fun writeValue(storeName: String, key: String, value: String) {
        withContext(dispatcherProvider.io) {
            sharedPreferences.edit {
                putString(key, value)
            }
        }
    }

    override suspend fun writeValues(storeName: String, entries: Map<String, String>) {
        withContext(dispatcherProvider.io) {
            sharedPreferences.edit {
                entries.forEach {
                    putString(it.key, it.value)
                }
            }
        }
    }

    override suspend fun readValue(storeName: String, key: String): String? =
        withContext(dispatcherProvider.io) {
            sharedPreferences.getString(key, null)
        }

    override suspend fun removeValue(storeName: String, key: String) {
        withContext(dispatcherProvider.io) {
            sharedPreferences.edit {
                remove(key)
            }
        }
    }

    override suspend fun clearStore(storeName: String) {
        // Not supported by this implementation, since it doesn't actually divide into sub-stores...
        throw NotImplementedError("LegacyKeyValueStore does not support clearStore(storeName)")
    }

    override suspend fun clearAll() {
        withContext(dispatcherProvider.io) {
            sharedPreferences.edit { clear() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun readAll(storeName: String): Map<String, String> =
        withContext(dispatcherProvider.io) {
            // Cast should be safe given that we're excluding anything that isn't a String
            sharedPreferences.all.filterValues { it is String } as Map<String, String>
        }
}
