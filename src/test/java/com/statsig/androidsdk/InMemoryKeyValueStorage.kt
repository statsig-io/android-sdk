package com.statsig.androidsdk

/**
 * Simple in-memory implementation of [KeyValueStorage] for test injection.
 */
internal class InMemoryKeyValueStorage : KeyValueStorage<String> {
    private val stores: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    override suspend fun writeValue(storeName: String, key: String, value: String) {
        stores.getOrPut(storeName) { mutableMapOf() }[key] = value
    }

    override suspend fun writeValues(storeName: String, entries: Map<String, String>) {
        stores.getOrPut(storeName) { mutableMapOf() }.putAll(entries)
    }

    override suspend fun readValue(storeName: String, key: String): String? =
        stores[storeName]?.get(key)

    override suspend fun removeValue(storeName: String, key: String) {
        stores[storeName]?.remove(key)
    }

    override suspend fun clearStore(storeName: String) {
        stores.remove(storeName)
    }

    override suspend fun clearAll() {
        stores.clear()
    }

    override suspend fun readAll(storeName: String): Map<String, String> =
        stores[storeName]?.toMap() ?: emptyMap()
}
