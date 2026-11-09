package com.nativeauto.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * Phase 8d (Rider-Feedback §12.2): while its pane is animating (maximize, swap, layout switch),
 * the map view keeps one size, the whole window, centred and clipped to the space it really has.
 * Without this the map's TextureView/SurfaceView was resized on every animation frame, which
 * reallocates its buffer and re-renders the tiles each time. Now it resizes twice per move (when it
 * starts and when it ends), and the map's centre stays where the rider sees it.
 */
@Composable
internal fun Modifier.holdSizeWhile(moving: () -> Boolean): Modifier {
    val window = LocalWindowInfo.current.containerSize
    return clipToBounds().layout { measurable, constraints ->
        val box = IntSize(constraints.maxWidth, constraints.maxHeight)
        val size = heldMapSize(moving(), box, window)
        val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
        layout(box.width, box.height) {
            placeable.place((box.width - size.width) / 2, (box.height - size.height) / 2)
        }
    }
}

/** The size to lay the map out at: its box, or while moving at least the [window]. */
internal fun heldMapSize(moving: Boolean, box: IntSize, window: IntSize): IntSize =
    if (moving) IntSize(max(box.width, window.width), max(box.height, window.height)) else box
