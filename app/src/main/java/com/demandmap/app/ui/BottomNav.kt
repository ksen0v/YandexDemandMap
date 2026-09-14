package com.demandmap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.demandmap.app.ui.theme.AppPrimary

enum class AppTab { MAP, SETTINGS }

@Composable
fun AppBottomNav(selectedTab: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(bottom = 24.dp).wrapContentWidth(),
        color = AppPrimary,
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavIcon(
                icon = Icons.Filled.Map,
                selected = selectedTab == AppTab.MAP,
                onClick = { onSelect(AppTab.MAP) },
            )
            Spacer(modifier = Modifier.width(28.dp))
            NavIcon(
                icon = Icons.Filled.Settings,
                selected = selectedTab == AppTab.SETTINGS,
                onClick = { onSelect(AppTab.SETTINGS) },
            )
        }
    }
}

@Composable
private fun NavIcon(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(if (selected) Color.White else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) AppPrimary else Color.White,
        )
    }
}
