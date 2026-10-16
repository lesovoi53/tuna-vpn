package com.wireturn.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState

/** A fixed toolbar has no collapse range, including after restoring an older saved state. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun TopAppBarState.useFixedToolbar() {
    heightOffsetLimit = 0f
    heightOffset = 0f
    contentOffset = 0f
}
