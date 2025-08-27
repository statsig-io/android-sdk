package com.statsig.androidsdk

import java.util.concurrent.ConcurrentHashMap

internal class BoundedMemo<K, V> {
    private val cache = ConcurrentHashMap<K, V>()
    companion object {
        private const val MAX_CACHE_SIZE = 1000
    }

    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V {
        if (cache.size >= MAX_CACHE_SIZE) {
            cache.clear()
        }
        return cache.getOrPut(key) { mappingFunction.invoke(key) }
    }

    fun size(): Int = cache.size

    fun clear() = cache.clear()
}
