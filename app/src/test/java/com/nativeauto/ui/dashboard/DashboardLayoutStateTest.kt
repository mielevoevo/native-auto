package com.nativeauto.ui.dashboard

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutStateTest {

    private var now = 1_000_000L
    private fun state(
        preset: LayoutPreset = LayoutPreset.DUO,
        assignments: Map<LayoutPreset, List<Pane>> = DefaultAssignments,
        bigOnLeft: Boolean = true,
        split: Float = 0.5f,
    ) = DashboardLayoutState(preset = preset, assignments = assignments, bigOnLeft = bigOnLeft, split = split, clock = { now })

    private fun assertRect(message: String, expected: Rect, actual: Rect) {
        val eps = 1e-6f
        assertEquals("$message left", expected.left, actual.left, eps)
        assertEquals("$message top", expected.top, actual.top, eps)
        assertEquals("$message right", expected.right, actual.right, eps)
        assertEquals("$message bottom", expected.bottom, actual.bottom, eps)
    }

    private fun DashboardLayoutState.assertRects(vararg expected: Pair<Pane, Rect>) =
        expected.forEach { (p, r) -> assertRect(p.name, r, rectOf(p)) }

    private fun DashboardLayoutState.assertHidden(vararg panes: Pane) = panes.forEach { assertFalse("$it hidden", isOnScreen(it)) }

    // ---- Duo: the handoff's weight table, as rectangles ----

    @Test fun `duo default splits the screen in half`() {
        val s = state()
        s.assertRects(
            Pane.MAP to Rect(0f, 0f, 0.5f, 1f),
            Pane.MEDIA to Rect(0.5f, 0f, 1f, 0.5f),
            Pane.SPEED to Rect(0.5f, 0.5f, 1f, 1f),
        )
        s.assertHidden(Pane.NAV, Pane.CLOCK)
        assertEquals(CardMode.Expanded, s.modeOf(Pane.MAP)) // the tall slot has a maximized card's room
        assertEquals(CardMode.Compact, s.modeOf(Pane.MEDIA))
        assertEquals(CardMode.Compact, s.modeOf(Pane.SPEED))
    }

    @Test fun `duo honours the split`() {
        state(split = 0.4f).assertRects(Pane.MAP to Rect(0f, 0f, 0.4f, 1f), Pane.MEDIA to Rect(0.4f, 0f, 1f, 0.5f))
    }

    @Test fun `duo maximized card fills its column`() {
        val s = state(split = 0.6f).apply { toggleMaximized(Pane.MEDIA) }
        s.assertRects(Pane.MAP to Rect(0f, 0f, 0.6f, 1f), Pane.MEDIA to Rect(0.6f, 0f, 1f, 1f))
        assertEquals(0f, s.rectOf(Pane.SPEED).height, 0f)
        assertEquals(CardMode.Expanded, s.modeOf(Pane.MEDIA))
        assertEquals(CardMode.Compact, s.modeOf(Pane.SPEED))
    }

    @Test fun `map fullscreen collapses the card column and keeps a maximized card`() {
        val s = state().apply { toggleMaximized(Pane.SPEED); toggleFullscreen(Pane.MAP) }
        s.assertRects(Pane.MAP to Rect(0f, 0f, 1f, 1f))
        s.assertHidden(Pane.MEDIA, Pane.SPEED)
        assertEquals(CardMode.Fullscreen, s.modeOf(Pane.MAP))
        // Hidden while the map is fullscreen, back to Expanded after.
        assertEquals(CardMode.Compact, s.modeOf(Pane.SPEED))
        s.back()
        assertEquals(CardMode.Expanded, s.modeOf(Pane.SPEED))
    }

    @Test fun `card fullscreen takes the screen`() {
        for (pane in listOf(Pane.MEDIA, Pane.SPEED)) {
            val s = state().apply { toggleFullscreen(pane) }
            s.assertRects(pane to Rect(0f, 0f, 1f, 1f))
            s.assertHidden(*(listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED) - pane).toTypedArray())
            assertEquals(CardMode.Fullscreen, s.modeOf(pane))
        }
    }

    @Test fun `fullscreen on another pane switches directly`() {
        val s = state().apply { toggleFullscreen(Pane.MEDIA); toggleFullscreen(Pane.SPEED) }
        assertEquals(Pane.SPEED, s.fullscreen)
    }

    @Test fun `the tall pane can't be maximized`() {
        val s = state()
        assertFalse(s.canMaximize(Pane.MAP))
        s.toggleMaximized(Pane.MAP)
        assertNull(s.maximized)
        assertTrue(s.canMaximize(Pane.MEDIA))
    }

    @Test fun `duo swaps move the tall pane across and trade the stacked ones`() {
        val s = state()
        assertFalse(s.swapsVertically(Pane.MAP))
        s.swap(Pane.MAP)
        assertFalse(s.bigOnLeft)
        s.assertRects(Pane.MAP to Rect(0.5f, 0f, 1f, 1f), Pane.MEDIA to Rect(0f, 0f, 0.5f, 0.5f))

        assertTrue(s.swapsVertically(Pane.MEDIA))
        s.swap(Pane.MEDIA)
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA), s.assignments.getValue(LayoutPreset.DUO))
        s.assertRects(Pane.SPEED to Rect(0f, 0f, 0.5f, 0.5f), Pane.MEDIA to Rect(0f, 0.5f, 0.5f, 1f))
    }

    @Test fun `split is clamped to 0_3 to 0_7`() {
        val s = state()
        s.setSplit(0.1f); assertEquals(0.3f, s.split, 0f)
        s.setSplit(0.95f); assertEquals(0.7f, s.split, 0f)
    }

    // ---- the other layouts ----

    @Test fun `side by side shows two tall expanded panes`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        s.assertRects(Pane.SPEED to Rect(0f, 0f, 0.5f, 1f), Pane.MEDIA to Rect(0.5f, 0f, 1f, 1f))
        s.assertHidden(Pane.MAP, Pane.NAV, Pane.CLOCK)
        assertEquals(CardMode.Expanded, s.modeOf(Pane.SPEED))
        assertEquals(CardMode.Expanded, s.modeOf(Pane.MEDIA))
        assertFalse(s.canMaximize(Pane.SPEED))
        s.swap(Pane.SPEED)
        assertEquals(listOf(Pane.MEDIA, Pane.SPEED), s.assignments.getValue(LayoutPreset.SIDE_BY_SIDE))
    }

    @Test fun `rows are full width and wide, and maximize to the whole height`() {
        val s = state(LayoutPreset.ROWS)
        s.assertRects(Pane.SPEED to Rect(0f, 0f, 1f, 0.5f), Pane.MEDIA to Rect(0f, 0.5f, 1f, 1f))
        assertEquals(CardMode.Wide, s.modeOf(Pane.SPEED))
        assertEquals(CardMode.Wide, s.modeOf(Pane.MEDIA))
        s.toggleMaximized(Pane.SPEED)
        s.assertRects(Pane.SPEED to Rect(0f, 0f, 1f, 1f))
        assertEquals(CardMode.Expanded, s.modeOf(Pane.SPEED))
        s.swap(Pane.SPEED)
        assertEquals(listOf(Pane.MEDIA, Pane.SPEED), s.assignments.getValue(LayoutPreset.ROWS))
    }

    @Test fun `quad is two by two, read like a page`() {
        val s = state(LayoutPreset.QUAD)
        s.assertRects(
            Pane.MAP to Rect(0f, 0f, 0.5f, 0.5f),
            Pane.SPEED to Rect(0.5f, 0f, 1f, 0.5f),
            Pane.MEDIA to Rect(0f, 0.5f, 0.5f, 1f),
            Pane.CLOCK to Rect(0.5f, 0.5f, 1f, 1f),
        )
        s.assertHidden(Pane.NAV)
        listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK).forEach { assertEquals(CardMode.Compact, s.modeOf(it)) }
        // Here even the map shares a column, so it can be maximized.
        assertTrue(s.canMaximize(Pane.MAP))
        s.toggleMaximized(Pane.MAP)
        s.assertRects(Pane.MAP to Rect(0f, 0f, 0.5f, 1f), Pane.SPEED to Rect(0.5f, 0f, 1f, 0.5f))
        assertFalse(s.isOnScreen(Pane.MEDIA))
        assertEquals(CardMode.Expanded, s.modeOf(Pane.MAP))
    }

    @Test fun `quad swap trades with the pane below`() {
        val s = state(LayoutPreset.QUAD)
        s.swap(Pane.SPEED)
        assertEquals(listOf(Pane.MAP, Pane.CLOCK, Pane.MEDIA, Pane.SPEED), s.assignments.getValue(LayoutPreset.QUAD))
    }

    @Test fun `switching layout starts unzoomed and parks what it doesn't show`() {
        val s = state().apply { toggleMaximized(Pane.SPEED); toggleFullscreen(Pane.MEDIA) }
        s.setPreset(LayoutPreset.SIDE_BY_SIDE)
        assertNull(s.fullscreen)
        assertNull(s.maximized)
        assertEquals(ParkedRect, s.rectOf(Pane.MAP))
        // Zooming a pane the layout doesn't show does nothing.
        s.toggleFullscreen(Pane.MAP)
        assertNull(s.fullscreen)
    }

    @Test fun `each layout remembers its own panes`() {
        val s = state()
        s.setPreset(LayoutPreset.QUAD)
        s.assign(Pane.CLOCK, Pane.NAV)
        s.setPreset(LayoutPreset.DUO)
        assertEquals(DefaultAssignments.getValue(LayoutPreset.DUO), s.view.panes)
        s.setPreset(LayoutPreset.QUAD)
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.NAV), s.view.panes)
    }

    // ---- pane picker ----

    @Test fun `picking a pane already shown trades places`() {
        val s = state()
        s.assign(Pane.MAP, Pane.SPEED)
        assertEquals(listOf(Pane.SPEED, Pane.MEDIA, Pane.MAP), s.view.panes)
    }

    @Test fun `picking a pane not shown replaces the slot's pane`() {
        val s = state()
        s.assign(Pane.MEDIA, Pane.CLOCK)
        assertEquals(listOf(Pane.MAP, Pane.CLOCK, Pane.SPEED), s.view.panes)
        assertFalse(s.isOnScreen(Pane.MEDIA))
    }

    @Test fun `a replaced pane takes its zoom with it`() {
        val s = state().apply { toggleMaximized(Pane.MEDIA) }
        s.assign(Pane.MEDIA, Pane.NAV)
        assertNull(s.maximized)
        val t = state().apply { toggleFullscreen(Pane.SPEED) }
        t.assign(Pane.SPEED, Pane.CLOCK)
        assertNull(t.fullscreen)
    }

    @Test fun `picking the same pane changes nothing`() {
        val s = state()
        val before = s.lastArrangedAtMs
        s.assign(Pane.MAP, Pane.MAP)
        assertEquals(DefaultAssignments.getValue(LayoutPreset.DUO), s.view.panes)
        assertEquals(before, s.lastArrangedAtMs)
    }

    // ---- status corner ----

    @Test fun `status corner sits in the top-right pane`() {
        assertEquals(Pane.MEDIA, state().statusPane)
        assertEquals(Pane.MAP, state(bigOnLeft = false).statusPane)
        assertEquals(Pane.SPEED, state().apply { swap(Pane.MEDIA) }.statusPane)
        assertEquals("maximized card fills the column", Pane.SPEED, state().apply { toggleMaximized(Pane.SPEED) }.statusPane)
        assertEquals("fullscreen pane is the whole screen", Pane.MAP, state().apply { toggleFullscreen(Pane.MAP) }.statusPane)
        assertEquals(Pane.MEDIA, state(LayoutPreset.SIDE_BY_SIDE).statusPane)
        assertEquals(Pane.SPEED, state(LayoutPreset.ROWS).statusPane)
        assertEquals(Pane.SPEED, state(LayoutPreset.QUAD).statusPane)
        assertEquals(Pane.CLOCK, state(LayoutPreset.QUAD).apply { toggleMaximized(Pane.CLOCK) }.statusPane)
    }

    // ---- back ----

    @Test fun `back unwinds fullscreen then maximized then falls through`() {
        val s = state().apply { toggleMaximized(Pane.MEDIA); toggleFullscreen(Pane.MAP) }
        assertTrue(s.back())
        assertNull(s.fullscreen)
        assertEquals(Pane.MEDIA, s.maximized)
        assertTrue(s.back())
        assertNull(s.maximized)
        assertFalse("default layout must hand back to the system", s.back())
    }

    // ---- auto layout override ----

    private val navDuo = LayoutView(LayoutPreset.DUO, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA))

    @Test fun `an override shows on top of the user's layout and goes away cleanly`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        s.applyAuto(navDuo)
        assertEquals(navDuo, s.view)
        assertTrue(s.isOnScreen(Pane.MAP))
        assertEquals(LayoutPreset.SIDE_BY_SIDE, s.userView.preset)
        s.clearAuto()
        assertEquals(LayoutPreset.SIDE_BY_SIDE, s.view.preset)
        assertFalse(s.isOnScreen(Pane.MAP))
    }

    @Test fun `zooming during an override keeps it`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        val before = s.lastArrangedAtMs
        s.applyAuto(navDuo)
        s.toggleMaximized(Pane.SPEED)
        assertEquals(Pane.SPEED, s.maximized)
        assertEquals(navDuo.copy(maximized = Pane.SPEED), s.override)
        assertEquals("zooming isn't arranging", before, s.lastArrangedAtMs)
        s.clearAuto()
        assertNull(s.maximized)
    }

    @Test fun `arranging by hand during an override makes it the user's own`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        s.applyAuto(navDuo)
        now += 5_000
        s.swap(Pane.SPEED)
        assertNull(s.override)
        assertEquals(LayoutPreset.DUO, s.preset)
        assertEquals(listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED), s.assignments.getValue(LayoutPreset.DUO))
        assertEquals(now, s.lastArrangedAtMs)
        s.clearAuto() // navigation ends: nothing to put back
        assertEquals(LayoutPreset.DUO, s.view.preset)
    }

    // ---- Saver ----

    @Test fun `saver round-trips every field`() {
        val original = DashboardLayoutState(
            preset = LayoutPreset.QUAD,
            assignments = DefaultAssignments + (LayoutPreset.ROWS to listOf(Pane.CLOCK, Pane.NAV)),
            fullscreen = Pane.SPEED,
            maximized = Pane.MEDIA,
            bigOnLeft = false,
            split = 0.6f,
            override = LayoutView(LayoutPreset.QUAD, NavQuad, maximized = Pane.NAV),
            lastArrangedAtMs = 1234L,
        )
        val restored = roundTrip(original)
        assertEquals(original.preset, restored.preset)
        assertEquals(original.assignments, restored.assignments)
        assertEquals(original.userView, restored.userView)
        assertEquals(original.override, restored.override)
        assertEquals(original.view, restored.view)
        assertFalse(restored.bigOnLeft)
        assertEquals(0.6f, restored.split, 0f)
        assertEquals(1234L, restored.lastArrangedAtMs)
    }

    @Test fun `saver round-trips the default state`() {
        val restored = roundTrip(state())
        assertEquals(state().view, restored.view)
        assertNull(restored.override)
        assertTrue(restored.bigOnLeft)
    }

    private fun roundTrip(state: DashboardLayoutState): DashboardLayoutState {
        val saver = DashboardLayoutState.Saver
        val saved = with(saver) { SaverScope { true }.save(state) }
        requireNotNull(saved) { "Saver produced nothing" }
        return requireNotNull(saver.restore(saved)) { "Saver failed to restore" }
    }
}
