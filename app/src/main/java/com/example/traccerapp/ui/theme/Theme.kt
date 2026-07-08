package com.example.traccerapp.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun TraccerAppTheme(isDarkTheme: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIsDarkTheme provides isDarkTheme) {
        val colorScheme = if (isDarkTheme) {
            darkColorScheme(
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
        } else {
            lightColorScheme(
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
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography(),
            content     = content
        )
    }
}
