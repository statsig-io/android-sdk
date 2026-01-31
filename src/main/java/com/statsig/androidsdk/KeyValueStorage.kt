package com.statsig.androidsdk

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// TODO: Maybe rework typing here - would we prefer this interface to provide stores
//  of distinct types?  E.g. if we want stores to take/give our internal types
internal interface KeyValueStorage<T> {

    /**
     * Remove all key:value pairs for every sub-store in this [KeyValueStorage]
     */
    suspend fun clearAll()

    /**
     * Remove all key:value pairs associated with [storeName]
     */
    suspend fun clearStore(storeName: String)

    /**
     * Return all key:value pairs stored under [storeName]
     */
    suspend fun readAll(storeName: String): Map<String, T>

    /**
     * Read a value from storage
     */
    suspend fun readValue(storeName: String, key: String): T?

    /**
     * Remove a key and its associated value from storage
     */
    suspend fun removeValue(storeName: String, key: String)

    /**
     * Write a value to storage
     */
    suspend fun writeValue(storeName: String, key: String, value: T)

    /**
     * Write multiple values to storage in a single transaction
     */
    suspend fun writeValues(storeName: String, entries: Map<String, T>)
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
internal class LegacyKeyValueStorage(val context: Context) : KeyValueStorage<String> {
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

/**
 * [KeyValueStorage] with each substore backed by a [Preferences] [DataStore].
 * Each [DataStore] is shared across instances of [StatsigClient] for efficient multi-client usage
 */
internal class PreferencesDataStoreKeyValueStorage(
    val application: Application,
    val coroutineScope: CoroutineScope
) : KeyValueStorage<String> {
    companion object {
        private const val TAG = "statsig::PrefsDataStore"

        // Stores each active DataStore
        private val storeMap: MutableMap<String, DataStore<Preferences>> = mutableMapOf()

        private val dispatcherProvider by lazy { CoroutineDispatcherProvider() }

        private const val DATA_STORE_FILE_PATH = "com.statsig.androidsdk.prefs"

        private const val FOUR_KB = 4096
        private const val SIXTEEN_KB = 16384

        // Align to Android's page size: 16KB on 35+, otherwise 4KB
        // Yes, this isn't native code, but it can still help keep disk reads efficient
        private val BUFFER_SIZE = if (Build.VERSION.SDK_INT >
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            SIXTEEN_KB
        } else {
            FOUR_KB
        }

        private object GzipPreferencesSerializer : Serializer<Preferences> {
            override val defaultValue: Preferences
                get() = emptyPreferences()

            override suspend fun readFrom(input: InputStream): Preferences {
                val decompressedStream = GZIPInputStream(input, BUFFER_SIZE)
                try {
                    return BufferedPreferencesSerializer.readFrom(decompressedStream)
                } catch (e: ZipException) {
                    throw CorruptionException("Bad zip file!", e)
                } finally {
                    decompressedStream.close()
                }
            }

            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                val compressedStream = GZIPOutputStream(output, BUFFER_SIZE)
                BufferedPreferencesSerializer.writeTo(t, compressedStream)
                compressedStream.finish()
            }
        }

        private object BufferedPreferencesSerializer : Serializer<Preferences> {
            override val defaultValue: Preferences
                get() = emptyPreferences()

            override suspend fun readFrom(input: InputStream): Preferences =
                PreferencesFileSerializer.readFrom(BufferedInputStream(input, BUFFER_SIZE))

            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                PreferencesFileSerializer.writeTo(t, BufferedOutputStream(output, BUFFER_SIZE))
            }
        }
    }

    private fun getCorruptionHandler(storeName: String) = ReplaceFileCorruptionHandler {
        // TODO: Maybe also fire a diagnostics event
        Log.e(TAG, "on-disk storage for $storeName is corrupted, replacing with empty file")
        emptyPreferences()
    }

    override suspend fun writeValue(storeName: String, key: String, value: String) {
        withContext(dispatcherProvider.io) {
            val store = getData(storeName)
            store.edit {
                it[stringPreferencesKey(key)] = value
            }
        }
    }

    override suspend fun writeValues(storeName: String, entries: Map<String, String>) {
        withContext(dispatcherProvider.io) {
            getData(storeName).edit { prefs ->
                entries.forEach { entry ->
                    prefs[stringPreferencesKey(entry.key)] = entry.value
                }
            }
        }
    }

    override suspend fun readValue(storeName: String, key: String): String? =
        withContext(dispatcherProvider.io) {
            getData(storeName).data.first()[stringPreferencesKey(key)]
        }

    override suspend fun removeValue(storeName: String, key: String) {
        withContext(dispatcherProvider.io) {
            getData(storeName).edit { it.remove(stringPreferencesKey(key)) }
        }
    }

    override suspend fun clearStore(storeName: String) {
        withContext(dispatcherProvider.io) {
            getData(storeName).edit { it.clear() }
        }
    }

    override suspend fun clearAll() {
        // This impl will ONLY work for stores are loaded into the map when this is actually called.
        //  Could probably use a dedicated store to index each other store
        storeMap.keys.forEach { storeName -> getData(storeName).edit { prefs -> prefs.clear() } }
    }

    override suspend fun readAll(storeName: String): Map<String, String> =
        withContext(dispatcherProvider.io) {
            val storeData = getData(storeName)
            storeData.data.first().asMap().filterValues { it is String }
                .map { it.key.name to it.value as String }.toMap()
        }

    private fun getData(storeName: String): DataStore<Preferences> {
        maybeBuildStore(storeName)
        val data = storeMap[storeName]
        if (data == null) {
            Log.wtf(TAG, "getData failed to find!")
        }
        return data!!
    }

    internal fun initialize() {
        // TODO: maybe pre-warm some storage instances? Or a store index?
        //  Could also accept some options here (multiproc, compression, etc)
    }

    private fun maybeBuildStore(storeName: String) {
        // TODO: is this actually safe enough to ensure we don't accidentally build a dupe instance?
        if (storeMap.contains(storeName)) {
            return
        }

        // TODO: Decide on multiprocess here or not - base decision on sdkFlags or StatsigOptions?
        val multiprocess = false

        // TODO: Decide on compression here: Measure impact of Gzip / non-Gzip on performance, potentially offer as a configurable option?
        val compressed = false

        val serializer =
            if (compressed) GzipPreferencesSerializer else BufferedPreferencesSerializer
        val append = if (compressed) "_gz" else "_unc"
        val produceFileLambda = {
            application.dataStoreFile("$DATA_STORE_FILE_PATH/$storeName$append")
        }

        if (multiprocess) {
            storeMap[storeName] = MultiProcessDataStoreFactory.create(
                scope = coroutineScope,
                produceFile = produceFileLambda,
                serializer = serializer,
                corruptionHandler = getCorruptionHandler(storeName),
                migrations = mutableListOf()
            )
        } else {
            storeMap[storeName] = DataStoreFactory.create(
                scope = coroutineScope,
                serializer = serializer,
                corruptionHandler = getCorruptionHandler(storeName),
                migrations = mutableListOf(),
                produceFile = produceFileLambda
            )
        }
    }
}
