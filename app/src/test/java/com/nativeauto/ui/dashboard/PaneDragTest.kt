package com.nativeauto.ui.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 12a: which card a dragged one is over (the drop target), from the layout's own rectangles. */
class PaneDragTest {
    private val drag = PaneDragState(TestScope()).apply { screen = Size(2400f, 1080f) }

    @Test fun `duo - the tall slot on the left, two stacked on the right`() {
        val layout = DashboardLayoutState() // Duo: map tall left, media top right, speed bottom right
        assertEquals(Pane.MAP, drag.paneAt(Offset(300f, 900f), layout))
        assertEquals(Pane.MEDIA, drag.paneAt(Offset(2000f, 200f), layout))
        assertEquals(Pane.SPEED, drag.paneAt(Offset(2000f, 900f), layout))
    }

    @Test fun `quad - each corner`() {
        val layout = DashboardLayoutState(preset = LayoutPreset.QUAD)
        val panes = listOf(Offset(300f, 200f), Offset(2100f, 200f), Offset(300f, 900f), Offset(2100f, 900f)).map { drag.paneAt(it, layout) }
        assertEquals(layout.view.panes.toSet(), panes.toSet())
    }

    @Test fun `a hidden pane is never a target`() {
        val layout = DashboardLayoutState().apply { toggleFullscreen(Pane.SPEED) }
        // Everything is speed now; the collapsed map can't be dropped on.
        assertEquals(Pane.SPEED, drag.paneAt(Offset(300f, 900f), layout))
    }

    @Test fun `no screen size yet, no target`() {
        assertNull(PaneDragState(TestScope()).paneAt(Offset(10f, 10f), DashboardLayoutState()))
    }
}
