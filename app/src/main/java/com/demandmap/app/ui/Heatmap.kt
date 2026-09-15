package com.demandmap.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val HEATMAP_SIZE_PX = 160

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
 * Renders a single hexagon (not a circle), flat-filled with the one color
 * for [coefficient] - matches [com.demandmap.app.data.SprosTaxiClient]
 * making exactly one request per tap now: one real number, one shape, no
 * interpolated gradient trying to represent data that isn't actually
 * there. Sized square to the query radius so [DemandHeatmapOverlay] can
 * scale/position it the same way regardless of what's drawn inside.
 */
fun renderHeatmapBitmap(radiusM: Double, coefficient: Double?): Bitmap {
    val bitmap = Bitmap.createBitmap(HEATMAP_SIZE_PX, HEATMAP_SIZE_PX, Bitmap.Config.ARGB_8888)
    if (coefficient == null || radiusM <= 0) return bitmap

    val half = HEATMAP_SIZE_PX / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorForCoefficient(coefficient) }
    Canvas(bitmap).drawPath(hexPath(half, half, half), paint)
    return bitmap
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

private const val POINT_LABEL_SIZE_TO_RADIUS_RATIO = 0.22f // text size relative to the hexagon's on-screen circumradius
private const val POINT_LABEL_MIN_TEXT_SIZE_PX = 14f
private const val POINT_LABEL_MAX_TEXT_SIZE_PX = 36f
private const val POINT_LABEL_BOLT_SIZE_RATIO = 0.8f // of text size
private const val POINT_LABEL_STROKE_WIDTH_RATIO = 0.16f // of text size

/**
 * Purple lightning-bolt + bold "xN" label, drawn once, centered on the
 * query point - one request now yields exactly one coefficient (see
 * [com.demandmap.app.data.SprosTaxiClient.samplePoints]), so there's no
 * longer a set of points that could overlap each other; sizing just scales
 * with the hexagon's own on-screen size instead of needing to measure
 * spacing between multiple labels.
 */
class DemandPointLabelsOverlay : Overlay() {
    var centerGeo: GeoPoint? = null
    var radiusM: Double = 0.0
    var coefficient: Double? = null

    private val boltColor = Color.parseColor("#7B1FA2") // vivid purple - distinct from the heatmap's own purple range
    // Condensed system family (bundled with every Android release, no .ttf
    // to fetch/bundle) instead of plain DEFAULT_BOLD - narrower glyphs at
    // the same point size, which both reads better as a compact map label
    // and leaves more headroom before it overruns the hexagon.
    private val labelTypeface = Typeface.create("sans-serif-condensed-medium", Typeface.BOLD)
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = boltColor
        style = Paint.Style.FILL
    }
    private val textFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = boltColor
        typeface = labelTypeface
        textAlign = Paint.Align.LEFT
    }
    // White halo drawn under the fill so the label stays legible over both
    // light map tiles and the heatmap's own deep-purple hex fill.
    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        typeface = labelTypeface
        textAlign = Paint.Align.LEFT
    }
    private val screenPoint = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val coeff = coefficient ?: return
        val center = centerGeo ?: return
        val projection = mapView.projection
        val radiusPx = projection.metersToEquatorPixels(radiusM.toFloat())
        if (radiusPx <= 0f) return
        projection.toPixels(center, screenPoint)

        val textSizePx = (radiusPx * POINT_LABEL_SIZE_TO_RADIUS_RATIO)
            .coerceIn(POINT_LABEL_MIN_TEXT_SIZE_PX, POINT_LABEL_MAX_TEXT_SIZE_PX)
        textFillPaint.textSize = textSizePx
        textStrokePaint.textSize = textSizePx
        textStrokePaint.strokeWidth = textSizePx * POINT_LABEL_STROKE_WIDTH_RATIO
        val boltSizePx = textSizePx * POINT_LABEL_BOLT_SIZE_RATIO

        val label = "x" + String.format(Locale.US, "%.2f", coeff)
        val labelWidthPx = textFillPaint.measureText(label)

        // Center the bolt+label pair as one unit on the query point, rather
        // than anchoring the bolt at the point and letting text trail off
        // to one side.
        val totalWidthPx = boltSizePx * 1.75f + labelWidthPx
        val bx = screenPoint.x - totalWidthPx / 2f + boltSizePx * 0.5f
        val by = screenPoint.y.toFloat()

        drawBolt(canvas, bx, by, boltSizePx)
        val textX = bx + boltSizePx * 0.75f
        val textY = by + textSizePx * 0.35f
        canvas.drawText(label, textX, textY, textStrokePaint)
        canvas.drawText(label, textX, textY, textFillPaint)
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
