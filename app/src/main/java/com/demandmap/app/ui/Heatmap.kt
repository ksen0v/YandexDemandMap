package com.demandmap.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import com.demandmap.app.domain.DemandPoint
import com.demandmap.app.domain.haversineMeters
import com.demandmap.app.domain.metersToDeg
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot
import kotlin.math.pow

private const val HEATMAP_SIZE_PX = 96

/**
 * Renders a square bitmap covering the query circle's bounding box - each
 * pixel's coefficient is inverse-distance-weighted from the sampled grid
 * points (see domain.discGrid / SprosTaxiClient), so the
 * whole radius reads as one smooth gradient instead of separate dots per
 * sample point. Pixels outside the circle stay transparent so it reads as
 * a filled disc, not a square.
 *
 * Cheap enough (96x96 = ~9.2k pixels x a handful of sample points) to run
 * on a background dispatcher without any real jank - see the
 * LaunchedEffect in MapScreen that calls this off the main thread.
 */
fun renderHeatmapBitmap(
    centerLat: Double,
    centerLon: Double,
    radiusM: Double,
    points: List<DemandPoint>,
): Bitmap {
    val pixels = IntArray(HEATMAP_SIZE_PX * HEATMAP_SIZE_PX)
    if (points.isNotEmpty() && radiusM > 0) {
        val (degLatPerM, degLonPerM) = metersToDeg(centerLat, 1.0)
        for (py in 0 until HEATMAP_SIZE_PX) {
            val fy = (py + 0.5) / HEATMAP_SIZE_PX * 2.0 - 1.0
            for (px in 0 until HEATMAP_SIZE_PX) {
                val fx = (px + 0.5) / HEATMAP_SIZE_PX * 2.0 - 1.0
                val offsetEastM = fx * radiusM
                val offsetNorthM = -fy * radiusM // bitmap y grows downward, latitude grows upward
                val distFromCenterM = hypot(offsetEastM, offsetNorthM)
                val idx = py * HEATMAP_SIZE_PX + px
                if (distFromCenterM > radiusM) {
                    pixels[idx] = Color.TRANSPARENT
                    continue
                }
                val pointLat = centerLat + offsetNorthM * degLatPerM
                val pointLon = centerLon + offsetEastM * degLonPerM
                pixels[idx] = colorForCoefficient(idwCoefficient(pointLat, pointLon, points))
            }
        }
    }
    return Bitmap.createBitmap(pixels, HEATMAP_SIZE_PX, HEATMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
}

/** Shepard's method / inverse-distance weighting - smooth, no external deps. */
private fun idwCoefficient(lat: Double, lon: Double, points: List<DemandPoint>, power: Double = 2.0): Double {
    var weightedSum = 0.0
    var weightTotal = 0.0
    for (point in points) {
        val d = haversineMeters(lat, lon, point.lat, point.lon)
        if (d < 1.0) return point.coefficient
        val w = 1.0 / d.pow(power)
        weightedSum += w * point.coefficient
        weightTotal += w
    }
    return if (weightTotal > 0) weightedSum / weightTotal else 1.0
}

/**
 * Purple gradient by coefficient: calm (~1.0x) is faint light lavender,
 * hot (4.0x+) is a near-opaque deep purple. Color and alpha ramp up
 * together so intensity reads clearly even over map tiles.
 */
fun colorForCoefficient(coefficient: Double): Int {
    val t = ((coefficient - 1.0) / 3.0).coerceIn(0.0, 1.0)
    // Material "Purple 100" (#E1BEE7) -> "Purple 900" (#4A148C).
    val r = lerp(0xE1, 0x4A, t)
    val g = lerp(0xBE, 0x14, t)
    val b = lerp(0xE7, 0x8C, t)
    val alpha = lerp(70, 235, t)
    return Color.argb(alpha, r, g, b)
}

private fun lerp(from: Int, to: Int, t: Double): Int = (from + (to - from) * t).toInt().coerceIn(0, 255)

/**
 * Draws the pre-rendered heatmap bitmap positioned and scaled to the query
 * circle in the *current* map projection every frame. Recomputing the
 * bitmap itself happens elsewhere (MapScreen's LaunchedEffect); this
 * overlay only repositions/rescales an already-built bitmap, which is
 * cheap enough to do on every pan/zoom frame.
 */
class DemandHeatmapOverlay : Overlay() {
    var centerGeo: GeoPoint? = null
    var radiusM: Double = 0.0
    var bitmap: Bitmap? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val screenPoint = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val bmp = bitmap ?: return
        val center = centerGeo ?: return
        val projection = mapView.projection
        projection.toPixels(center, screenPoint)
        val radiusPx = projection.metersToEquatorPixels(radiusM.toFloat())
        if (radiusPx <= 0f) return
        val dst = RectF(
            screenPoint.x - radiusPx,
            screenPoint.y - radiusPx,
            screenPoint.x + radiusPx,
            screenPoint.y + radiusPx,
        )
        canvas.drawBitmap(bmp, null, dst, paint)
    }
}
