package com.supplices.kemimotowear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * OpenKEMI Color Palette and Wear OS Material Theme.
 *
 * Matching physical Kemimoto garment hardware controller:
 * - Level 1: Vibrant Emerald Green (#00E676) - Low Heat (Faible)
 * - Level 2: Vibrant Cyan Blue (#00B0FF) - Mid Heat (Moyen)
 * - Level 3: Intense Scarlet Red (#FF1744) - High Heat (Élevé)
 */
val HeatOffColor = Color(0xFF2A2A2E)
val HeatLowGreen = Color(0xFF00E676)     // Level 1: Green (Faible)
val HeatMidBlue = Color(0xFF00B0FF)      // Level 2: Blue (Moyen)
val HeatHighRed = Color(0xFFFF1744)      // Level 3: Red (Élevé)
val HeatBatteryGreen = Color(0xFF00E676) // Healthy battery state
val ProximityWarningColor = Color(0xFFFF9100) // Proximity or Bluetooth alert banner

val WearColors = Colors(
    primary = Color(0xFFFF5252),
    primaryVariant = HeatHighRed,
    secondary = HeatMidBlue,
    secondaryVariant = Color(0xFF0091EA),
    background = Color.Black,
    surface = Color(0xFF18181A),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun KemimotoWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearColors,
        content = content
    )
}
