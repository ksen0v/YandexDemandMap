package com.demandmap.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plain android.location.LocationManager, deliberately not
 * FusedLocationProviderClient - avoids pulling in Google Play Services
 * just for one "locate me" button, consistent with using osmdroid (no
 * Google Maps key) elsewhere in this app.
 *
 * Earlier version of this file picked whichever provider's last-known fix
 * had the newest *timestamp*, full stop. That's how a stale-but-fine GPS
 * fix lost to a NETWORK_PROVIDER fix that was a few seconds "newer" but
 * carried an accuracy radius of several kilometers - explaining reports of
 * the app placing "current location" well outside the actual city while
 * apps like Yandex Maps (which fuse location more carefully) got it right.
 * This version weighs accuracy together with recency, and - for a fresh
 * fix - waits a short grace period after the first provider answers in
 * case a second, better one is only moments behind, instead of locking in
 * on whichever one happens to respond first.
 */
object LocationHelper {

    private const val CACHE_FRESH_MS = 2 * 60 * 1000L
    private const val CACHE_MAX_ACCEPTABLE_ACCURACY_M = 300f
    private const val FRESH_FIX_TIMEOUT_MS = 15_000L
    private const val GRACE_PERIOD_AFTER_FIRST_FIX_MS = 4_000L
    private const val TWO_MINUTES_MS = 2 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Returns a recent-*and-accurate*-enough last-known fix instantly if
     * there is one, otherwise races GPS and network for a fresh fix (with
     * a short grace period so a slightly-slower-but-better provider isn't
     * discarded just for answering second) and waits up to ~15s total.
     * Caller must check [hasPermission] first - this returns null on a
     * SecurityException rather than crash if it wasn't.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val bestCached = locationManager.getProviders(true)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .reduceOrNull { best, candidate -> if (isBetterLocation(candidate, best)) candidate else best }

        val cacheIsGoodEnough = bestCached != null &&
            System.currentTimeMillis() - bestCached.time < CACHE_FRESH_MS &&
            bestCached.hasAccuracy() &&
            bestCached.accuracy <= CACHE_MAX_ACCEPTABLE_ACCURACY_M
        if (cacheIsGoodEnough) return bestCached

        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { locationManager.isProviderEnabled(it) },
        )
        if (providers.isEmpty()) return bestCached

        val fresh = try {
            withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { requestBestFreshFix(locationManager, providers) }
        } catch (e: SecurityException) {
            null
        }

        return fresh ?: bestCached
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestBestFreshFix(locationManager: LocationManager, providers: List<String>): Location? {
        val results = Channel<Location>(Channel.UNLIMITED)
        val listeners = providers.associateWith { provider ->
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    results.trySend(location)
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
        }

        return try {
            providers.forEach { provider ->
                locationManager.requestSingleUpdate(provider, listeners.getValue(provider), Looper.getMainLooper())
            }

            val first = results.receiveCatching().getOrNull() ?: return null
            var best = first

            // A second provider might only be moments behind the first -
            // this grace period is what stops a fast-but-coarse network fix
            // from "winning" merely by answering before a slightly slower,
            // far more accurate GPS fix does.
            withTimeoutOrNull(GRACE_PERIOD_AFTER_FIRST_FIX_MS) {
                while (true) {
                    val next = results.receive()
                    if (isBetterLocation(next, best)) best = next
                }
            }
            best
        } finally {
            listeners.values.forEach { runCatching { locationManager.removeUpdates(it) } }
            results.close()
        }
    }

    /**
     * Classic "which location reading is actually better" heuristic,
     * weighing recency and accuracy together rather than either alone.
     * Adapted from Android's own long-standing location-strategies
     * guidance - taking whichever fix simply has the latest timestamp (the
     * previous version of this function) is exactly what let a coarse,
     * kilometers-off network fix beat a slightly older but far more
     * accurate GPS one.
     */
    private fun isBetterLocation(location: Location, currentBest: Location?): Boolean {
        if (currentBest == null) return true

        val timeDelta = location.time - currentBest.time
        val isSignificantlyNewer = timeDelta > TWO_MINUTES_MS
        val isSignificantlyOlder = timeDelta < -TWO_MINUTES_MS
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val accuracyDelta = (location.accuracy - currentBest.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        return when {
            isMoreAccurate -> true
            isNewer && !isLessAccurate -> true
            isNewer && !isSignificantlyLessAccurate -> true
            else -> false
        }
    }
}
