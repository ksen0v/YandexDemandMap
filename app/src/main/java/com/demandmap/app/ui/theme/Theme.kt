package com.demandmap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Monochrome, card-based look modeled on a travel-app reference (white
 * rounded cards on a warm off-white background, black pill buttons/nav).
 * Note this uses the system default font (Roboto on Android) tuned via
 * weight/size/letter-spacing to approximate the reference's clean grotesk
 * feel - swapping in an actual matching font (e.g. Inter) would need a
 * .ttf dropped into res/font, which isn't something this environment can
 * fetch on its own.
 */

val AppBackground = Color(0xFFF5F5F3)
val AppSurface = Color(0xFFFFFFFF)
val AppOnSurface = Color(0xFF1A1A1A)
val AppOnSurfaceMuted = Color(0xFF8A8A8E)
val AppPrimary = Color(0xFF141414)
val AppOnPrimary = Color(0xFFFFFFFF)
val AppChipUnselected = Color(0xFFEFEFED)
val AppOutline = Color(0xFFE6E6E3)

private val AppColorScheme = lightColorScheme(
    primary = AppPrimary,
    onPrimary = AppOnPrimary,
    secondary = AppPrimary,
    onSecondary = AppOnPrimary,
    background = AppBackground,
    onBackground = AppOnSurface,
    surface = AppSurface,
    onSurface = AppOnSurface,
    surfaceVariant = AppChipUnselected,
    onSurfaceVariant = AppOnSurfaceMuted,
    outline = AppOutline,
    error = Color(0xFFD9483B),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, color = AppOnSurfaceMuted),
)

@Composable
fun DemandMapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content,
    )
}
