package com.wireturn.app

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test
import com.wireturn.app.ui.useFixedToolbar

@OptIn(ExperimentalMaterial3Api::class)
class FixedToolbarScrollTest {
    @Test fun fixedToolbarLeavesUpwardDragForScreenContent() {
        val state = TopAppBarState(-Float.MAX_VALUE, 0f, 0f)
        val constructor = Class.forName("androidx.compose.material3.ExitUntilCollapsedScrollBehavior")
            .declaredConstructors.first { it.parameterCount == 4 }.apply { isAccessible = true }
        val behavior = constructor.newInstance(state, null, null, { true }) as TopAppBarScrollBehavior
        state.useFixedToolbar()
        repeat(20) {
            val consumed = behavior.nestedScrollConnection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
            assertEquals("A fixed toolbar must not consume the screen's upward drag", 0f, consumed.y, 0f)
        }
    }

    @Test fun restoredCollapseOffsetIsCleared() {
        val state = TopAppBarState(-200f, -150f, -500f)
        state.useFixedToolbar()
        assertEquals(0f, state.heightOffsetLimit, 0f)
        assertEquals(0f, state.heightOffset, 0f)
        assertEquals(0f, state.contentOffset, 0f)
    }
}
