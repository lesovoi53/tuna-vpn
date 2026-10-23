package com.wireturn.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

val LocalCompactSettings = compositionLocalOf { false }

/** Compact spacing for editors, inheriting the application's common Orbit theme. */
@Composable
fun CompactSettingsTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCompactSettings provides true) {
        content()
    }
}
