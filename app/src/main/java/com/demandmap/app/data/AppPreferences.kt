package com.demandmap.app.data

import android.content.Context
import com.demandmap.app.domain.ServiceType

/**
 * Small SharedPreferences-backed settings shared between MainActivity/
 * DemandViewModel and OverlayWidgetService (which runs independently of any
 * Activity, so it can't just read a ViewModel's in-memory StateFlow).
 */
object AppPreferences {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_SERVICE_TYPE = "service_type"
    private const val KEY_WIDGET_INTERVAL_SEC = "widget_interval_sec"
    private const val KEY_LAST_CENTER_LAT = "last_center_lat"
    private const val KEY_LAST_CENTER_LON = "last_center_lon"
    private const val KEY_LAST_ZOOM = "last_zoom"

    /**
     * Not user-adjustable (there used to be a Settings slider for this) -
     * fixed at 500m so the zone stays one consistent, predictable size
     * instead of a per-user setting. Not persisted since there's nothing
     * to persist.
     */
    const val QUERY_RADIUS_M = 500
    const val DEFAULT_ZOOM = 13.0

    /**
     * Hard floor, not just a suggested default: sprostaxi.ru's own limit is
     * 10 requests / 10 seconds (see SprosTaxiClient's docs). A widget tick
     * below this would risk tripping that limit on its own, before the app
     * itself does anything. Enforced here so it can never be set lower,
     * regardless of what UI ends up calling into this.
     */
    const val MIN_WIDGET_INTERVAL_SEC = 10
    const val DEFAULT_WIDGET_INTERVAL_SEC = 30

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServiceType(context: Context): ServiceType =
        if (prefs(context).getString(KEY_SERVICE_TYPE, ServiceType.TAXI.name) == ServiceType.COURIER.name) {
            ServiceType.COURIER
        } else {
            ServiceType.TAXI
        }

    fun setServiceType(context: Context, value: ServiceType) {
        prefs(context).edit().putString(KEY_SERVICE_TYPE, value.name).apply()
    }

    fun getWidgetIntervalSec(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_INTERVAL_SEC, DEFAULT_WIDGET_INTERVAL_SEC).coerceAtLeast(MIN_WIDGET_INTERVAL_SEC)

    fun setWidgetIntervalSec(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_INTERVAL_SEC, seconds.coerceAtLeast(MIN_WIDGET_INTERVAL_SEC)).apply()
    }

    /**
     * Last place the map was actually centered on - either the user's last
     * tap, or a successful GPS auto-center on a previous launch. Used so
     * the app reopens where you left off / where you are, instead of
     * always defaulting to the hardcoded Moscow fallback. Stored as
     * strings since SharedPreferences has no native double type.
     */
    fun getLastCenter(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        val lat = p.getString(KEY_LAST_CENTER_LAT, null)?.toDoubleOrNull()
        val lon = p.getString(KEY_LAST_CENTER_LON, null)?.toDoubleOrNull()
        return if (lat != null && lon != null) lat to lon else null
    }

    fun setLastCenter(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString(KEY_LAST_CENTER_LAT, lat.toString())
            .putString(KEY_LAST_CENTER_LON, lon.toString())
            .apply()
    }

    fun getLastZoom(context: Context): Double =
        prefs(context).getString(KEY_LAST_ZOOM, null)?.toDoubleOrNull() ?: DEFAULT_ZOOM

    fun setLastZoom(context: Context, zoom: Double) {
        prefs(context).edit().putString(KEY_LAST_ZOOM, zoom.toString()).apply()
    }
}
