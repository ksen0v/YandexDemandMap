package com.demandmap.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import com.demandmap.app.domain.DemandPoint
import com.demandmap.app.domain.haversineMeters
import com.demandmap.app.domain.metersToDeg
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val HEATMAP_SIZE_PX = 160

private const val MIN_HEXES_ACROSS_RADIUS = 2.5
private const val MAX_HEXES_ACROSS_RADIUS = 6.0
private const val MIN_HEX_SIZE_M = 6.0

// Blur radius as a fraction of a hex's own size - big enough that adjacent
// hexes visibly blend into each other at their shared edge instead of a
// hard line, small enough that each cell is still individually readable.
private const val HEX_EDGE_BLUR_FRACTION = 0.28f

// Sharper than plain Shepard IDW (power 2) so each hex leans toward its
// nearest real sample instead of broadly blending every point in range -
// see the doc comment on renderHeatmapBitmap for why this matters.
private const val HEX_IDW_POWER = 3.5

private const val LOCATION_DOT_OUTER_DP = 20f
private const val LOCATION_DOT_INNER_DP = 12f

/**
 * Plain circle for the "my location" marker - white ring, solid black
 * center (same black as AppPrimary/the zoom buttons/nav pill elsewhere on
 * this screen) - used as both the "person" and "direction" icon on
 * MyLocationNewOverlay so it never switches to osmdroid's default walking-
 * person icon (stationary) / arrow icon (once a bearing is known); see
 * MapScreen's setup of that overlay.
 */
fun createLocationDotBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val outerPx = LOCATION_DOT_OUTER_DP * density
    val innerRadiusPx = LOCATION_DOT_INNER_DP * density / 2f
    val size = outerPx.toInt()

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawCircle(center, center, outerPx / 2f, ringPaint)

    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141414") } // AppPrimary
    canvas.drawCircle(center, center, innerRadiusPx, dotPaint)

    return bitmap
}

/**
 * Renders a square bitmap covering the query radius as a honeycomb of
 * hexagons, not one smooth blob - each hexagon's own color comes from
 * inverse-distance-weighting the sampled grid points (see domain.discGrid /
 * SprosTaxiClient) at that hexagon's center, same technique the old
 * single-blob renderer used per-pixel, just evaluated once per cell instead
 * of once per pixel (cheaper, too). Each hex is filled with a mask-blurred
 * Paint so neighboring cells blend at their shared edge instead of showing
 * a hard border - the blur only works because this Canvas is backed by a
 * plain software Bitmap, not a hardware-accelerated View layer
 * (BlurMaskFilter is unsupported on those). The whole thing is then clipped
 * to one big hexagon the size of the query radius, not a circle - the
 * outer silhouette should read as hex-shaped too, not just its insides.
 *
 * Cheap enough (~25 hex fills, not ~9.2k+ per-pixel samples) to run on a
 * background dispatcher without any real jank - see the LaunchedEffect in
 * MapScreen that calls this off the main thread.
 */
fun renderHeatmapBitmap(
    centerLat: Double,
    centerLon: Double,
    radiusM: Double,
    points: List<DemandPoint>,
): Bitmap {
    val bitmap = Bitmap.createBitmap(HEATMAP_SIZE_PX, HEATMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
    if (points.isEmpty() || radiusM <= 0) return bitmap

    val canvas = Canvas(bitmap)
    val half = HEATMAP_SIZE_PX / 2f
    // The overall zone outline is one big hexagon now too, not a circle -
    // same hexPath() helper used for each small cell, just scaled up to
    // the full query radius. A circular outer clip was undercutting the
    // whole point of this change: no matter how textured the inside was,
    // the silhouette still read as "a circle" at a glance.
    canvas.clipPath(hexPath(half, half, half))

    val pxPerMeter = HEATMAP_SIZE_PX / (2.0 * radiusM)
    // Size hexes to roughly match how far apart the real sampled points
    // actually are, not a fixed ratio blind to that - with a sparse sample
    // (small `points`), hexes finer than the data's own resolution just end
    // up IDW-blending the same 1-2 nearby points into near-identical colors,
    // which is invisible once blurred: looks like one smooth blob again.
    val hexesAcrossRadius = sqrt(points.size.toDouble()).coerceIn(MIN_HEXES_ACROSS_RADIUS, MAX_HEXES_ACROSS_RADIUS)
    val hexSizeM = (radiusM / hexesAcrossRadius).coerceAtLeast(MIN_HEX_SIZE_M)
    val hexSizePx = (hexSizeM * pxPerMeter).toFloat()
    val blurPx = (hexSizePx * HEX_EDGE_BLUR_FRACTION).coerceAtLeast(1f)

    val (degLatPerM, degLonPerM) = metersToDeg(centerLat, 1.0)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    }

    for ((eastM, northM) in hexGridMeters(radiusM, hexSizeM)) {
        val hexLat = centerLat + northM * degLatPerM
        val hexLon = centerLon + eastM * degLonPerM
        val cx = half + (eastM * pxPerMeter).toFloat()
        val cy = half - (northM * pxPerMeter).toFloat() // pixel y grows downward, latitude grows upward

        paint.color = colorForCoefficient(idwCoefficient(hexLat, hexLon, points, power = HEX_IDW_POWER))
        canvas.drawPath(hexPath(cx, cy, hexSizePx), paint)
    }

    return bitmap
}

/**
 * Pointy-top hex grid centers (in local meters, east/north from the query
 * center) covering a disc of [radiusM], using axial coordinates - standard
 * hex-grid math, see e.g. redblobgames.com/grids/hexagons. A little
 * padding beyond radiusM is included so cells straddling the circle's edge
 * are still generated (and then cut off cleanly by the canvas clip in
 * [renderHeatmapBitmap] rather than leaving a gap).
 */
private fun hexGridMeters(radiusM: Double, hexSizeM: Double): List<Pair<Double, Double>> {
    val sqrt3 = sqrt(3.0)
    val maxR = ceil((radiusM + hexSizeM) / (1.5 * hexSizeM)).toInt() + 1
    val maxQ = ceil((radiusM + hexSizeM) / (sqrt3 * hexSizeM)).toInt() + 1

    val centers = mutableListOf<Pair<Double, Double>>()
    for (r in -maxR..maxR) {
        for (q in -maxQ..maxQ) {
            val eastM = hexSizeM * (sqrt3 * q + sqrt3 / 2.0 * r)
            val northM = hexSizeM * (1.5 * r)
            if (hypot(eastM, northM) <= radiusM + hexSizeM) {
                centers.add(eastM to northM)
            }
        }
    }
    return centers
}

/** Pointy-top hexagon (vertex straight up) path, corners at 60-degree steps starting at -90. */
private fun hexPath(cx: Float, cy: Float, circumradiusPx: Float): Path {
    val path = Path()
    for (i in 0 until 6) {
        val angleRad = Math.toRadians(-90.0 + 60.0 * i)
        val x = cx + circumradiusPx * cos(angleRad).toFloat()
        val y = cy + circumradiusPx * sin(angleRad).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
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
 * radius in the *current* map projection every frame. Recomputing the
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

private const val POINT_LABEL_DEFAULT_SPACING_M = 150.0
private const val POINT_LABEL_FONT_TO_SPACING_RATIO = 0.4f
private const val POINT_LABEL_MAX_WIDTH_FRACTION = 0.85f // of the on-screen point spacing
private const val POINT_LABEL_MIN_TEXT_SIZE_PX = 16f
private const val POINT_LABEL_MAX_TEXT_SIZE_PX = 40f
private const val POINT_LABEL_BOLT_SIZE_RATIO = 0.85f // of text size
private const val POINT_LABEL_STROKE_WIDTH_RATIO = 0.16f // of text size

/**
 * Purple lightning-bolt + bold "xN" label at every individually sampled
 * point (not just the interpolated hex fill), so the raw value behind each
 * query point is visible on the map, not only the smoothed zone color.
 *
 * Font size is derived from how far apart the points actually sit *on
 * screen right now* - nearest-neighbor spacing between points in meters,
 * converted through the map's live projection every frame - rather than
 * from the query radius alone, so labels stay non-overlapping whether the
 * radius changes, the map is zoomed, or a query happens to return
 * unusually long numbers (checked directly via Paint.measureText, not just
 * assumed from a fixed string length).
 */
class DemandPointLabelsOverlay : Overlay() {
    var points: List<DemandPoint> = emptyList()
        set(value) {
            field = value
            minSpacingM = nearestNeighborSpacingM(value)
        }

    private var minSpacingM: Double = POINT_LABEL_DEFAULT_SPACING_M

    private val boltColor = Color.parseColor("#7B1FA2") // vivid purple - distinct from the heatmap's own purple range
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = boltColor
        style = Paint.Style.FILL
    }
    private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = boltColor
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    // White halo drawn under the fill so the label stays legible over both
    // light map tiles and the heatmap's own deep-purple hex fills.
    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }
    private val screenPoint = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val projection = mapView.projection
        val spacingPx = projection.metersToEquatorPixels(minSpacingM.toFloat())
        val labels = points.map { it to "x" + String.format(Locale.US, "%.2f", it.coefficient) }

        var textSizePx = (spacingPx * POINT_LABEL_FONT_TO_SPACING_RATIO)
            .coerceIn(POINT_LABEL_MIN_TEXT_SIZE_PX, POINT_LABEL_MAX_TEXT_SIZE_PX)
        textFillPaint.textSize = textSizePx
        val widestLabelPx = labels.maxOf { (_, label) -> textFillPaint.measureText(label) }
        val neededWidth = widestLabelPx + textSizePx * POINT_LABEL_BOLT_SIZE_RATIO * 1.75f // bolt + gap
        val availableWidth = spacingPx * POINT_LABEL_MAX_WIDTH_FRACTION
        if (neededWidth > 0f && neededWidth > availableWidth) {
            textSizePx = (textSizePx * (availableWidth / neededWidth)).coerceAtLeast(POINT_LABEL_MIN_TEXT_SIZE_PX * 0.5f)
        }

        textFillPaint.textSize = textSizePx
        textStrokePaint.textSize = textSizePx
        textStrokePaint.strokeWidth = textSizePx * POINT_LABEL_STROKE_WIDTH_RATIO
        val boltSizePx = textSizePx * POINT_LABEL_BOLT_SIZE_RATIO

        for ((point, label) in labels) {
            projection.toPixels(GeoPoint(point.lat, point.lon), screenPoint)
            val bx = screenPoint.x.toFloat()
            val by = screenPoint.y.toFloat()

            drawBolt(canvas, bx, by, boltSizePx)
            val textX = bx + boltSizePx * 0.75f
            val textY = by + textSizePx * 0.35f
            canvas.drawText(label, textX, textY, textStrokePaint)
            canvas.drawText(label, textX, textY, textFillPaint)
        }
    }

    /** Simple jagged lightning-bolt silhouette, centered at (cx, cy). */
    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val path = Path().apply {
            moveTo(cx + size * 0.15f, cy - size * 0.5f)
            lineTo(cx - size * 0.25f, cy + size * 0.05f)
            lineTo(cx - size * 0.02f, cy + size * 0.05f)
            lineTo(cx - size * 0.15f, cy + size * 0.5f)
            lineTo(cx + size * 0.3f, cy - size * 0.05f)
            lineTo(cx + size * 0.05f, cy - size * 0.05f)
            close()
        }
        canvas.drawPath(path, boltPaint)
    }
}

/** Smallest distance between any two points, in meters - the tightest constraint on label size. */
private fun nearestNeighborSpacingM(points: List<DemandPoint>): Double {
    if (points.size < 2) return POINT_LABEL_DEFAULT_SPACING_M
    var minDist = Double.MAX_VALUE
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            val d = haversineMeters(points[i].lat, points[i].lon, points[j].lat, points[j].lon)
            if (d > 1.0 && d < minDist) minDist = d
        }
    }
    return if (minDist == Double.MAX_VALUE) POINT_LABEL_DEFAULT_SPACING_M else minDist
}
