package com.nativeauto.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/**
 * A weighted, clipped slot. The gutter is this slot's own padding (rule #5), so a slot that
 * has collapsed to ~0 px takes its gutter with it — no stray gap beside a fullscreen pane.
 */
@Composable
internal fun PaneSlot(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds().padding(4.dp)) { content() }
}
