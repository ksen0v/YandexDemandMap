package com.demandmap.app.data

import com.demandmap.app.domain.DemandPoint
import com.demandmap.app.domain.ServiceType

private data class DemandCacheKey(
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val service: ServiceType,
)

/**
 * Only real data now - no mock/demo mode. A failed real-provider call
 * propagates as an exception; DemandViewModel's existing catch block turns
 * that into a visible error message instead of quietly serving fabricated
 * numbers dressed up as real ones.
 */
class DemandRepository(private val real: SprosTaxiClient) {
    private val cache = TtlCache<DemandCacheKey, Pair<List<DemandPoint>, String>>(ttlMillis = 20_000)

    suspend fun samplePoints(
        lat: Double,
        lon: Double,
        radiusM: Int,
        service: ServiceType,
    ): Pair<List<DemandPoint>, String> {
        val key = DemandCacheKey(round5(lat), round5(lon), radiusM, service)
        cache.get(key)?.let { return it }

        val result = real.samplePoints(lat, lon, radiusM, service) to "sprostaxi"
        cache.put(key, result)
        return result
    }

    private fun round5(v: Double) = Math.round(v * 1e5) / 1e5
}
