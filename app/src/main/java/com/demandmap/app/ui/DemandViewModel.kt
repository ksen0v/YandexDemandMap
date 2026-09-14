package com.demandmap.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demandmap.app.data.AppPreferences
import com.demandmap.app.data.AppServices
import com.demandmap.app.domain.DemandPoint
import com.demandmap.app.domain.GeoPointSimple
import com.demandmap.app.domain.ServiceType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DemandUiState(
    val center: GeoPointSimple = GeoPointSimple(55.751244, 37.618423), // Moscow, just a default
    val zoom: Double = AppPreferences.DEFAULT_ZOOM,
    val radiusM: Int = AppPreferences.DEFAULT_RADIUS_M,
    val service: ServiceType = ServiceType.TAXI,
    val tapped: GeoPointSimple? = null,
    val points: List<DemandPoint> = emptyList(),
    val centerPoint: DemandPoint? = null,
    val source: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class DemandViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppServices.repository(application)

    private val _state = MutableStateFlow(
        DemandUiState(
            center = AppPreferences.getLastCenter(application)
                ?.let { (lat, lon) -> GeoPointSimple(lat, lon) }
                ?: GeoPointSimple(55.751244, 37.618423),
            zoom = AppPreferences.getLastZoom(application),
            radiusM = AppPreferences.getRadiusM(application),
            service = AppPreferences.getServiceType(application),
        ),
    )
    val state: StateFlow<DemandUiState> = _state.asStateFlow()

    private var pendingJob: Job? = null

    private fun runQuery(lat: Double, lon: Double) {
        pendingJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        pendingJob = viewModelScope.launch {
            val current = _state.value
            try {
                val (points, source) = repository.samplePoints(lat, lon, current.radiusM, current.service)
                // discGrid always includes the exact tapped point first, at
                // distance 0 - that's "the coefficient here", as opposed to
                // the hottest point somewhere else within the radius.
                val center = points.minByOrNull { it.distanceM }
                _state.update { it.copy(points = points, centerPoint = center, source = source, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Неизвестная ошибка") }
            }
        }
    }

    fun onMapTapped(lat: Double, lon: Double) {
        AppPreferences.setLastCenter(getApplication(), lat, lon)
        _state.update { it.copy(tapped = GeoPointSimple(lat, lon), center = GeoPointSimple(lat, lon)) }
        runQuery(lat, lon)
    }

    /**
     * Silent re-center (no query, no "tapped" card) - used once on launch
     * when location permission is already granted, so the app opens on
     * where you actually are instead of the last place you tapped or the
     * hardcoded Moscow fallback.
     */
    fun updateCenter(lat: Double, lon: Double) {
        AppPreferences.setLastCenter(getApplication(), lat, lon)
        _state.update { it.copy(center = GeoPointSimple(lat, lon)) }
    }

    /** Debounced: mirrors the web prototype not re-querying on every pixel of slider drag. */
    fun onRadiusChanged(newRadius: Int) {
        AppPreferences.setRadiusM(getApplication(), newRadius)
        _state.update { it.copy(radiusM = newRadius) }
        val tapped = _state.value.tapped ?: return
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            delay(400)
            runQuery(tapped.lat, tapped.lon)
        }
    }

    fun onServiceChanged(newService: ServiceType) {
        AppPreferences.setServiceType(getApplication(), newService)
        _state.update { it.copy(service = newService) }
        _state.value.tapped?.let { runQuery(it.lat, it.lon) }
    }
}
