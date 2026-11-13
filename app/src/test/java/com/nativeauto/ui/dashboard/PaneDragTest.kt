package com.nativeauto.ui.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertNotNull
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

/** 13a: the drop target by overlap, then finger, then nearest centre. */
class DropTargetTest {
    private val home = Rect(0f, 0f, 0.5f, 1f)
    private val right = Rect(0.5f, 0f, 1f, 0.5f)
    private val rightBottom = Rect(0.5f, 0.5f, 1f, 1f)
    private val others = mapOf(Pane.MEDIA to right, Pane.SPEED to rightBottom)

    @Test fun `the card covered most wins, even with the finger elsewhere`() {
        // Lifted card moved right and down: mostly over the bottom card, finger is still in the top one.
        val lifted = home.translate(0.45f, 0.4f)
        assertEquals(Pane.SPEED, pickDropTarget(lifted, home, Offset(0.6f, 0.2f), others))
    }

    @Test fun `a card dropped straight on its neighbour swaps with it`() {
        val lifted = home.translate(0.5f, 0f)
        // Covers both right cards equally by area; the first wins deterministically (one of them).
        assertNotNull(pickDropTarget(lifted, home, Offset(0.75f, 0.25f), others))
    }

    @Test fun `a small overlap under the threshold falls back to the finger`() {
        // 10% of the smaller card covered (the card was barely moved); the finger sits inside the bottom card.
        val lifted = home.translate(0.05f, 0f)
        val finger = Offset(0.7f, 0.8f)
        assertEquals(Pane.SPEED, pickDropTarget(lifted, home, finger, others))
    }

    @Test fun `nothing under the lifted card or the finger takes the nearest centre once it travelled halfway`() {
        val gap = mapOf(Pane.MEDIA to Rect(0.7f, 0f, 1f, 1f))
        val farLeft = Rect(0f, 0f, 0.3f, 1f)
        // Barely moved: no target.
        assertNull(pickDropTarget(farLeft.translate(0.02f, 0f), farLeft, Offset(0.17f, 0.5f), gap))
        // Moved most of the way but not onto it (finger in the gap): the nearest card.
        assertEquals(Pane.MEDIA, pickDropTarget(farLeft.translate(0.4f, 0f), farLeft, Offset(0.55f, 0.5f), gap))
    }

    @Test fun `no other cards, no target`() {
        assertNull(pickDropTarget(home, home, Offset(0.1f, 0.1f), emptyMap()))
    }
}
