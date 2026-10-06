package com.thunder.agi.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always dark — AMOLED-friendly, reduces battery and eye strain
private val ThunderColorScheme = darkColorScheme(
    primary           = ThunderElectric,
    onPrimary         = ThunderBlack,
    primaryContainer  = Color(0xFF003E52),
    onPrimaryContainer = ThunderElectric,
    secondary         = ThunderPurple,
    onSecondary       = ThunderWhite,
    secondaryContainer = Color(0xFF2D1060),
    onSecondaryContainer = ThunderGlow,
    tertiary          = ThunderGlow,
    onTertiary        = ThunderBlack,
    background        = ThunderBlack,
    onBackground      = ThunderWhite,
    surface           = ThunderDeepBlue,
    onSurface         = ThunderWhite,
    surfaceVariant    = ThunderMidBlue,
    onSurfaceVariant  = ThunderGray,
    error             = ThunderRed,
    onError           = ThunderWhite,
    outline           = Color(0xFF2A3F55),
    outlineVariant    = Color(0xFF1A2B3C),
    inverseSurface    = ThunderWhite,
    inverseOnSurface  = ThunderBlack,
    scrim             = Color(0xCC000000)
)

@Composable
fun ThunderAGITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ThunderColorScheme,
        typography  = androidx.compose.material3.Typography()
        content     = content
    )
}
