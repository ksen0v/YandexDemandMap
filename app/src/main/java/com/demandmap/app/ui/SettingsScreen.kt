package com.demandmap.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.demandmap.app.data.AppPreferences
import com.demandmap.app.data.LocationHelper
import com.demandmap.app.domain.ServiceType
import com.demandmap.app.overlay.OverlayWidgetService
import com.demandmap.app.ui.theme.AppOnSurfaceMuted
import com.demandmap.app.ui.theme.AppPrimary

/**
 * Settings tab: radius, taxi/courier and the "widget over other apps"
 * controls (moved off the map screen so the map stays uncluttered).
 */
@Composable
fun SettingsScreen(viewModel: DemandViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Single source of truth for whether the widget is actually alive -
    // see OverlayWidgetService.isRunning's doc comment for why this can't
    // just be local remembered state.
    val widgetRunning by OverlayWidgetService.isRunning.collectAsState()
    var widgetIntervalSec by remember { mutableStateOf(AppPreferences.getWidgetIntervalSec(context)) }

    // --- Overlay widget permission chain (overlay -> location -> notifications -> start).
    // Ordered bottom-up so every reference points at something already declared above it.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Proceed either way: a foreground service still runs without
        // POST_NOTIFICATIONS, it just won't show its notification banner.
        startOverlayWidget(context, widgetIntervalSec)
    }

    fun proceedAfterLocationGranted() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOverlayWidget(context, widgetIntervalSec)
        }
    }

    val widgetLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            proceedAfterLocationGranted()
        } else {
            Toast.makeText(context, "Нужно разрешение на геолокацию", Toast.LENGTH_SHORT).show()
        }
    }

    fun proceedAfterOverlayGranted() {
        if (LocationHelper.hasPermission(context)) {
            proceedAfterLocationGranted()
        } else {
            widgetLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            proceedAfterOverlayGranted()
        } else {
            Toast.makeText(context, "Нужно разрешение «Поверх других приложений»", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestStartWidget() {
        if (!Settings.canDrawOverlays(context)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            )
        } else {
            proceedAfterOverlayGranted()
        }
    }

    fun stopWidget() {
        stopOverlayWidget(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp, top = 28.dp, bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            SettingsCard(title = "Тип поездки") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonoFilterChip(
                        label = "Такси",
                        selected = state.service == ServiceType.TAXI,
                        onClick = { viewModel.onServiceChanged(ServiceType.TAXI) },
                    )
                    MonoFilterChip(
                        label = "Курьер",
                        selected = state.service == ServiceType.COURIER,
                        onClick = { viewModel.onServiceChanged(ServiceType.COURIER) },
                    )
                }
            }
        }

        item {
            SettingsCard(title = "Радиус запроса") {
                Text(
                    "${state.radiusM} м",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppOnSurfaceMuted,
                )
                Slider(
                    value = state.radiusM.toFloat(),
                    onValueChange = { viewModel.onRadiusChanged(it.toInt()) },
                    valueRange = 50f..5000f,
                    colors = monoSliderColors(),
                )
            }
        }

        item {
            SettingsCard(title = "Виджет поверх экрана") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (widgetRunning) "Активен" else "Выключен",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppOnSurfaceMuted,
                    )
                    Switch(
                        checked = widgetRunning,
                        onCheckedChange = { checked -> if (checked) requestStartWidget() else stopWidget() },
                        colors = monoSwitchColors(),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Обновление: раз в $widgetIntervalSec с " +
                        "(не реже ${AppPreferences.MIN_WIDGET_INTERVAL_SEC} с, чтобы не забанило)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppOnSurfaceMuted,
                )
                Slider(
                    value = widgetIntervalSec.toFloat(),
                    onValueChange = { newValue ->
                        val clamped = newValue.toInt().coerceAtLeast(AppPreferences.MIN_WIDGET_INTERVAL_SEC)
                        widgetIntervalSec = clamped
                        AppPreferences.setWidgetIntervalSec(context, clamped)
                        if (widgetRunning) startOverlayWidget(context, clamped) // applies live, no restart needed
                    },
                    valueRange = AppPreferences.MIN_WIDGET_INTERVAL_SEC.toFloat()..120f,
                    colors = monoSliderColors(),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MonoFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppPrimary,
            selectedLabelColor = Color.White,
        ),
    )
}

@Composable
private fun monoSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AppPrimary,
    checkedBorderColor = AppPrimary,
)

@Composable
private fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = AppPrimary,
    activeTrackColor = AppPrimary,
)

private fun startOverlayWidget(context: android.content.Context, intervalSec: Int) {
    val intent = Intent(context, OverlayWidgetService::class.java).apply {
        putExtra(OverlayWidgetService.EXTRA_INTERVAL_SEC, intervalSec)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopOverlayWidget(context: android.content.Context) {
    context.stopService(Intent(context, OverlayWidgetService::class.java))
}
