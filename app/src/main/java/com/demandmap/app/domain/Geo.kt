package com.demandmap.app.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct Kotlin port of app/providers/geo.py from the FastAPI prototype.
 * Verified against the Python original for several (lat, lon, radius, divisions)
 * combinations - the two implementations produce identical points.
 */
private const val EARTH_RADIUS_M = 6_371_000.0

data class GridPoint(val lat: Double, val lon: Double, val distanceM: Double)

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).pow(2) + cos(p1) * cos(p2) * sin(dLambda / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/** Rough meters -> degrees conversion, good enough at city scale. */
fun metersToDeg(lat: Double, meters: Double): Pair<Double, Double> {
    val dLat = meters / 111_320.0
    val dLon = meters / (111_320.0 * max(cos(Math.toRadians(lat)), 0.01))
    return dLat to dLon
}

/**
 * Samples a small grid of points inside a disc of [radiusM] around
 * ([lat], [lon]). Always includes the exact center point first. [divisions]
 * controls grid density (divisions x divisions square grid, corners outside
 * the circle are dropped).
 */
fun discGrid(lat: Double, lon: Double, radiusM: Int, divisions: Int = 5): List<GridPoint> {
    val points = mutableListOf(GridPoint(lat, lon, 0.0))
    if (divisions < 2) return points

    val (dLat, dLon) = metersToDeg(lat, radiusM.toDouble())
    for (i in 0 until divisions) {
        for (j in 0 until divisions) {
            val fx = -1.0 + 2.0 * i / (divisions - 1)
            val fy = -1.0 + 2.0 * j / (divisions - 1)
            val pLat = lat + fy * dLat
            val pLon = lon + fx * dLon
            val d = haversineMeters(lat, lon, pLat, pLon)
            if (d <= radiusM && d > 1.0) {
                points.add(GridPoint(pLat, pLon, d))
            }
        }
    }
    return points
}
