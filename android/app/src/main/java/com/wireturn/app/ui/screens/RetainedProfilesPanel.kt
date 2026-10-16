package com.wireturn.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Keeps the profile tree in the activity window instead of recreating a dialog window. */
@Composable
internal fun RetainedProfilesPanel(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    containerColor: Color,
    dragHandle: @Composable () -> Unit,
    embedded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    if (embedded) {
        Column(Modifier.fillMaxSize().statusBarsPadding().background(containerColor)) {
            dragHandle()
            Column(Modifier.weight(1f)) { content() }
        }
        return
    }
    val progress = animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "profiles_panel")
    val dismiss by rememberUpdatedState(onDismissRequest)
    BackHandler(visible) { dismiss() }
    Box(Modifier.fillMaxSize()) {
        if (visible) Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = progress.value }
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClickLabel = "Закрыть профили") { dismiss() })
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.9f)
                .graphicsLayer { translationY = size.height * (1f - progress.value) }
                .then(if (visible) Modifier.semantics { paneTitle = "Профили" } else Modifier.clearAndSetSemantics {}),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = containerColor
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Box(Modifier.pointerInput(Unit) {
                    var distance = 0f
                    detectVerticalDragGestures(
                        onDragStart = { distance = 0f },
                        onDragEnd = { if (distance > 64.dp.toPx()) dismiss() },
                        onVerticalDrag = { change, amount -> distance += amount; change.consume() }
                    )
                }) { dragHandle() }
                Column(Modifier.weight(1f)) { content() }
            }
        }
    }
}
