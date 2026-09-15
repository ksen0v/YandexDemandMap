package com.demandmap.app.ui

import android.Manifest
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.demandmap.app.data.LocationHelper
import com.demandmap.app.domain.ServiceType
import com.demandmap.app.ui.theme.AppOnSurfaceMuted
import com.demandmap.app.ui.theme.AppPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

/**
 * Map tab: full-bleed map, tap to query, locate-me FAB, a floating result
 * card at the bottom. Radius, taxi/courier and the widget all live on the
 * Settings tab now -- this screen just shows what they resolve to.
 */
@Composable
fun MapScreen(viewModel: DemandViewModel) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mapViewRef = remember { arrayOfNulls<MapView>(1) }
    // osmdroid's own "current position" dot/arrow overlay - GpsMyLocationProvider
    // wraps plain LocationManager under the hood too, same as LocationHelper
    // elsewhere in this app, so this still doesn't pull in Play Services.
    // Distinct from the one-shot LocationHelper.getCurrentLocation() used by
    // the locate-me FAB and auto-center below: this one keeps *updating* the
    // marker on the map as you move, it doesn't just fetch once and stop.
    val myLocationOverlayRef = remember { arrayOfNulls<MyLocationNewOverlay>(1) }
    val heatmapOverlay = remember { DemandHeatmapOverlay() }
    val pointLabelsOverlay = remember { DemandPointLabelsOverlay() }
    var heatmapBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var locating by remember { mutableStateOf(false) }

    // Recompute the hex-grid bitmap off the main thread whenever the query
    // result or radius changes - cheap but no reason to do it inline during
    // composition/recomposition.
    LaunchedEffect(state.points, state.tapped, state.radiusM) {
        val tapped = state.tapped
        heatmapBitmap = if (tapped == null || state.points.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                renderHeatmapBitmap(tapped.lat, tapped.lon, state.radiusM.toDouble(), state.points)
            }
        }
    }

    // Opens on where you actually are (if permission's already granted)
    // instead of always the persisted/default center - guarded by the
    // ViewModel (not just this LaunchedEffect's Unit key) so it fires once
    // per app session, not once per tab switch: MapScreen is fully
    // unmounted/remounted whenever AppRoot swaps tabs, which would
    // otherwise re-run this and re-center on live GPS every time you come
    // back to the Map tab. Doesn't touch "tapped" or trigger a query.
    LaunchedEffect(Unit) {
        if (viewModel.tryConsumeAutoCenter() && LocationHelper.hasPermission(context)) {
            val location = LocationHelper.getCurrentLocation(context)
            if (location != null) {
                mapViewRef[0]?.controller?.animateTo(GeoPoint(location.latitude, location.longitude))
                viewModel.updateCenter(location.latitude, location.longitude)
            }
        }
    }

    fun locateMe() {
        scope.launch {
            locating = true
            val location = LocationHelper.getCurrentLocation(context)
            locating = false
            if (location == null) {
                Toast.makeText(context, "Не удалось определить местоположение", Toast.LENGTH_SHORT).show()
                return@launch
            }
            mapViewRef[0]?.controller?.let { controller ->
                controller.setZoom(15.0)
                controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }
            viewModel.onMapTapped(location.latitude, location.longitude)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            myLocationOverlayRef[0]?.enableMyLocation()
            locateMe()
        } else {
            Toast.makeText(context, "Нужно разрешение на геолокацию", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val mapView = MapView(ctx)
                mapView.setTileSource(TileSourceFactory.MAPNIK)
                mapView.setMultiTouchControls(true)
                @Suppress("DEPRECATION") // osmdroid has no non-deprecated replacement for this toggle
                mapView.setBuiltInZoomControls(false) // replaced by the custom column on the right
                mapView.controller.setZoom(state.zoom)
                mapView.controller.setCenter(GeoPoint(state.center.lat, state.center.lon))

                // Remember wherever the map ends up - panning and
                // pinch-zooming, not just taps - so the app reopens at the
                // same place/scale instead of resetting each time, *and*
                // so switching tabs and back (which recreates this MapView
                // from viewModel.state) picks up where you left off rather
                // than a stale initial position. Debounced so a drag/pinch
                // gesture doesn't write on every intermediate frame.
                // Routed through the ViewModel (not a direct AppPreferences
                // write) so state.center/state.zoom stay current too.
                var persistCameraJob: Job? = null
                fun schedulePersist() {
                    persistCameraJob?.cancel()
                    persistCameraJob = scope.launch {
                        delay(500)
                        viewModel.onCameraMoved(mapView.mapCenter.latitude, mapView.mapCenter.longitude, mapView.zoomLevelDouble)
                    }
                }
                mapView.addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        schedulePersist()
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        schedulePersist()
                        return false
                    }
                })

                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        if (p == null) return false
                        viewModel.onMapTapped(p.latitude, p.longitude)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                }
                // Index 0 = tap handling, index 1 = the hex gradient overlay
                // (both touched by the redraw logic below via their own
                // vars); the my-location and point-label overlays are
                // appended after, in draw order.
                mapView.overlays.add(0, MapEventsOverlay(receiver))
                mapView.overlays.add(1, heatmapOverlay)

                // Added last (drawn on top of the gradient) so your own
                // position is never hidden under the demand overlay.
                val myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), mapView)
                // Same plain circle for both the "person" (stationary) and
                // "direction" (once a bearing is known) states, so it never
                // switches to osmdroid's default walking-person/arrow icons.
                val locationDot = createLocationDotBitmap(ctx)
                myLocationOverlay.setDirectionArrow(locationDot, locationDot)
                myLocationOverlay.setPersonAnchor(0.5f, 0.5f)
                myLocationOverlay.setDirectionAnchor(0.5f, 0.5f)
                mapView.overlays.add(myLocationOverlay)
                myLocationOverlayRef[0] = myLocationOverlay
                if (LocationHelper.hasPermission(ctx)) {
                    myLocationOverlay.enableMyLocation()
                }

                // Drawn last of all so per-point labels always sit on top,
                // never hidden under the hex fill or the location marker.
                mapView.overlays.add(pointLabelsOverlay)

                mapView
            },
            update = { mapView ->
                mapViewRef[0] = mapView
                heatmapOverlay.bitmap = heatmapBitmap
                heatmapOverlay.centerGeo = state.tapped?.let { GeoPoint(it.lat, it.lon) }
                heatmapOverlay.radiusM = state.radiusM.toDouble()
                pointLabelsOverlay.points = state.points
                mapView.invalidate()
            },
        )

        // osmdroid's MapView needs its lifecycle hooked up manually in a Compose host.
        // The my-location overlay gets the same treatment - no point polling
        // GPS to animate a dot on a screen that isn't visible.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        mapViewRef[0]?.onResume()
                        if (LocationHelper.hasPermission(context)) {
                            myLocationOverlayRef[0]?.enableMyLocation()
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        mapViewRef[0]?.onPause()
                        myLocationOverlayRef[0]?.disableMyLocation()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Small read-only reminder of the current mode/radius - the actual
        // controls live on the Settings tab now.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .wrapContentWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(50),
            shadowElevation = 3.dp,
        ) {
            Text(
                text = "${if (state.service == ServiceType.TAXI) "Такси" else "Курьер"} · ${state.radiusM} м",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // Custom zoom +/- - osmdroid's own built-in buttons (disabled above)
        // used to land bottom-center, right on top of the result card.
        // Two separate fixed-size circles, not a joined pill: nothing here
        // asks for fillMaxWidth (that was the earlier bug - a default
        // HorizontalDivider stretches full-width and drags its parents
        // along with it if they're not otherwise size-constrained).
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                onClick = { mapViewRef[0]?.controller?.zoomIn() },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = AppPrimary,
                shadowElevation = 6.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, contentDescription = "Приблизить", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Surface(
                onClick = { mapViewRef[0]?.controller?.zoomOut() },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = AppPrimary,
                shadowElevation = 6.dp,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Remove, contentDescription = "Отдалить", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 100.dp), // clearance for the floating nav bar
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(
                onClick = {
                    if (LocationHelper.hasPermission(context)) {
                        locateMe()
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                containerColor = AppPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(end = 20.dp, bottom = 16.dp),
            ) {
                if (locating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Моё местоположение")
                }
            }

            if (state.tapped != null) {
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        when {
                            state.loading -> Row {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AppPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Обновляем данные…", style = MaterialTheme.typography.bodyMedium)
                            }
                            state.error != null -> Text(
                                "Ошибка: ${state.error}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            state.centerPoint != null -> {
                                val center = state.centerPoint!!
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "x${String.format(Locale.US, "%.2f", center.coefficient)}",
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                    if (center.bonusRub > 0) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Surface(color = AppPrimary, shape = RoundedCornerShape(50)) {
                                            Text(
                                                "+${center.bonusRub} ₽",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Источник: ${state.source ?: "—"} · ${state.points.size} точек",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppOnSurfaceMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
