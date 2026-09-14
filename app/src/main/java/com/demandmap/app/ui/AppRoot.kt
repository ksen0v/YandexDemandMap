package com.demandmap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Two tabs sharing one DemandViewModel: Map (tap-to-query, full-bleed) and
 * Settings (radius, taxi/courier, widget). The nav bar floats on top of
 * whichever tab is showing rather than reserving its own Scaffold slot, so
 * the map stays edge-to-edge behind it - matching the reference design's
 * floating pill nav.
 */
@Composable
fun AppRoot() {
    val viewModel: DemandViewModel = viewModel()
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                AppTab.MAP -> MapScreen(viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel)
            }

            AppBottomNav(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
