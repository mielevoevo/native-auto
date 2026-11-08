package com.nativeauto.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLayoutTest {

    private var now = 10_000_000L

    /** Arranged long ago, so the hold doesn't apply unless a test arranges something. */
    private fun state(preset: LayoutPreset = LayoutPreset.DUO, assignments: Map<LayoutPreset, List<Pane>> = DefaultAssignments) =
        DashboardLayoutState(preset = preset, assignments = assignments, clock = { now })

    @Test fun `quad switches to its navigation set and back`() {
        val s = state(LayoutPreset.QUAD)
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertEquals(LayoutView(LayoutPreset.QUAD, NavQuad), s.view)
        assertTrue(s.isOnScreen(Pane.NAV))
        assertFalse(s.isOnScreen(Pane.CLOCK))
        AutoLayout.onNavigationEnded(s)
        assertEquals(DefaultAssignments.getValue(LayoutPreset.QUAD), s.view.panes)
    }

    @Test fun `a layout without the map switches to duo`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertEquals(LayoutView(LayoutPreset.DUO, DefaultAssignments.getValue(LayoutPreset.DUO)), s.view)
        AutoLayout.onNavigationEnded(s)
        assertEquals(LayoutPreset.SIDE_BY_SIDE, s.view.preset)
    }

    @Test fun `a duo without the map gets it in the tall slot`() {
        val s = state(LayoutPreset.ROWS, DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.SPEED, Pane.MEDIA, Pane.CLOCK)))
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertEquals(listOf(Pane.MAP, Pane.MEDIA, Pane.CLOCK), s.view.panes)
    }

    @Test fun `a duo with the map in a small slot keeps it there`() {
        val custom = DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA))
        val s = state(LayoutPreset.DUO, custom)
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertNull("map already on screen", s.override)
    }

    @Test fun `a visible map is left alone`() {
        val s = state()
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertNull(s.override)
        // Maximizing a card in the other column doesn't hide the map either.
        s.toggleMaximized(Pane.SPEED)
        assertNull(AutoLayout.navigationView(s.userView, s.assignments))
    }

    @Test fun `a map hidden by zoom is brought back, unzoomed`() {
        val fullscreen = state().apply { toggleFullscreen(Pane.SPEED) }
        AutoLayout.onNavigationStarted(fullscreen, enabled = true)
        assertEquals(LayoutView(LayoutPreset.DUO, DefaultAssignments.getValue(LayoutPreset.DUO)), fullscreen.view)
        AutoLayout.onNavigationEnded(fullscreen)
        assertEquals("the user's zoom comes back", Pane.SPEED, fullscreen.fullscreen)

        // The map in a small slot, its neighbour maximized: hidden.
        val custom = DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA))
        val maximized = state(LayoutPreset.DUO, custom).apply { toggleMaximized(Pane.MEDIA) }
        AutoLayout.onNavigationStarted(maximized, enabled = true)
        assertNull(maximized.maximized)
        assertTrue(maximized.isOnScreen(Pane.MAP))
    }

    @Test fun `map fullscreen counts as shown`() {
        val s = state().apply { toggleFullscreen(Pane.MAP) }
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertNull(s.override)
    }

    @Test fun `turned off, it does nothing`() {
        val s = state(LayoutPreset.SIDE_BY_SIDE)
        AutoLayout.onNavigationStarted(s, enabled = false)
        assertNull(s.override)
        assertFalse(s.isOnScreen(Pane.MAP))
    }

    @Test fun `it holds off for a while after the user arranges the layout`() {
        val s = state()
        s.setPreset(LayoutPreset.SIDE_BY_SIDE) // by hand, now
        now += AutoLayout.HOLD_MS - 1
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertNull("manual wins", s.override)

        now += 1
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertEquals(LayoutPreset.DUO, s.view.preset)
    }

    @Test fun `a change by hand during navigation is kept when it ends`() {
        val s = state(LayoutPreset.QUAD)
        AutoLayout.onNavigationStarted(s, enabled = true)
        s.assign(Pane.MEDIA, Pane.CLOCK) // the rider swaps media for the clock mid-route
        AutoLayout.onNavigationEnded(s)
        assertEquals(listOf(Pane.MAP, Pane.NAV, Pane.SPEED, Pane.CLOCK), s.view.panes)
        assertEquals("saved as the user's own", s.view.panes, s.assignments.getValue(LayoutPreset.QUAD))
    }

    @Test fun `zooming during navigation doesn't stop the layout coming back`() {
        val s = state(LayoutPreset.QUAD)
        AutoLayout.onNavigationStarted(s, enabled = true)
        s.toggleFullscreen(Pane.NAV)
        AutoLayout.onNavigationEnded(s)
        assertEquals(DefaultAssignments.getValue(LayoutPreset.QUAD), s.view.panes)
        assertNull(s.fullscreen)
    }

    @Test fun `ending without an override changes nothing`() {
        val s = state(LayoutPreset.ROWS).apply { toggleMaximized(Pane.SPEED) }
        AutoLayout.onNavigationEnded(s)
        assertEquals(LayoutPreset.ROWS, s.view.preset)
        assertEquals(Pane.SPEED, s.maximized)
    }

    @Test fun `a quad already in its navigation set isn't overridden`() {
        val s = state(LayoutPreset.QUAD, DefaultAssignments + (LayoutPreset.QUAD to NavQuad))
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertNull(s.override)
    }
}
