package com.demandmap.app.data

/**
 * Tiny in-memory TTL cache. Avoids re-querying (mock or real) for an
 * identical (location, radius, service, mode) combination within
 * [ttlMillis] - same purpose as cachetools.TTLCache in the FastAPI
 * prototype's demand_service.py.
 */
class TtlCache<K, V>(private val ttlMillis: Long, private val maxSize: Int = 500) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    // access-order = true turns this into a simple LRU when we evict from the front.
    private val map = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        if (map.size >= maxSize) {
            map.keys.firstOrNull()?.let { map.remove(it) }
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }
}
