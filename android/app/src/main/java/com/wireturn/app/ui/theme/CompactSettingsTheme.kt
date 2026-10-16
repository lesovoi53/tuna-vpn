package com.wireturn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val LocalCompactSettings = compositionLocalOf { false }

/** NekoBox-style settings lists; scoped to the routing and proxy-chain editors. */
@Composable
fun CompactSettingsTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = if (colors.background.luminance() < 0.5f) colors.copy(
        background = Color(0xFF101116), surface = Color(0xFF101116),
        surfaceContainer = Color(0xFF191A21), surfaceContainerHigh = Color(0xFF22232B),
        primary = Color(0xFFC4B7EF), onSurface = Color(0xFFE4E1E9),
        onSurfaceVariant = Color(0xFFB8B5C1),
    ) else colors
    CompositionLocalProvider(LocalCompactSettings provides true) {
        MaterialTheme(colorScheme = palette, content = content)
    }
}
