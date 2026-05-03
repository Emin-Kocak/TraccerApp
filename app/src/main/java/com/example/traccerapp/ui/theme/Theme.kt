package com.example.traccerapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = PurplePrimary,
    onPrimary        = Color.White,
    primaryContainer = PurpleDim,
    secondary        = PurpleLight,
    background       = DarkBg,
    surface          = DarkSurface,
    surfaceVariant   = DarkElevated,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline          = DarkBorder,
    error            = StatusRed
)

@Composable
fun TraccerAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography(),
        content     = content
    )
}