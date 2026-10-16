package com.wireturn.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal fun orbitColorScheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFB6A8FF), onPrimary = Color(0xFF251B53),
    primaryContainer = Color(0xFF36305B), onPrimaryContainer = Color(0xFFEAE3FF),
    secondary = Color(0xFFC5BDE9), onSecondary = Color(0xFF2E2944),
    secondaryContainer = Color(0xFF36305B), onSecondaryContainer = Color(0xFFEAE3FF),
    tertiary = Color(0xFF9ADBC3), onTertiary = Color(0xFF003829),
    background = Color(0xFF111126), onBackground = Color(0xFFF5F4FF),
    surface = Color(0xFF20203C), onSurface = Color(0xFFF5F4FF),
    surfaceVariant = Color(0xFF292947), onSurfaceVariant = Color(0xFFB3B3CC),
    surfaceContainerLowest = Color(0xFF15152C), surfaceContainerLow = Color(0xFF1B1B34),
    surfaceContainer = Color(0xFF20203C), surfaceContainerHigh = Color(0xFF292947),
    surfaceContainerHighest = Color(0xFF343453), outline = Color(0xFF77758F),
    outlineVariant = Color(0xFF373753), surfaceTint = Color.Transparent,
) else lightColorScheme(
    primary = Color(0xFF6246BE), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF251B53),
    secondary = Color(0xFF635A80), secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF251B53),
    background = Color(0xFFF6F3FE), onBackground = Color(0xFF211D31),
    surface = Color(0xFFFFFBFF), onSurface = Color(0xFF211D31),
    onSurfaceVariant = Color(0xFF625E73), surfaceVariant = Color(0xFFEAE4F5),
    surfaceContainer = Color(0xFFEFEBF7), surfaceContainerHigh = Color(0xFFEAE4F5),
    outlineVariant = Color(0xFFD6CFE6), surfaceTint = Color.Transparent,
)
