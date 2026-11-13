package com.nativeauto.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 14a: the call card takes the lowest-priority slot, never a fullscreen one, and gives it back. */
class CallTakeoverTest {
    private fun state(preset: LayoutPreset = LayoutPreset.DUO) = DashboardLayoutState(preset = preset)

    @Test fun `in Duo the call replaces media, the first to go`() {
        val s = state()
        s.setCallActive(true)
        assertEquals(listOf(Pane.MAP, Pane.CALL, Pane.SPEED), s.view.panes)
        assertTrue(s.isOnScreen(Pane.CALL))
        assertFalse(s.isOnScreen(Pane.MEDIA))
        assertEquals(CardMode.Compact, s.modeOf(Pane.CALL))
        s.setCallActive(false)
        assertEquals(listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED), s.view.panes)
        assertFalse(s.isOnScreen(Pane.CALL))
    }

    @Test fun `without media the clock goes next, then the Garage, then speed, then navigation, the map last`() {
        val cases = listOf(
            listOf(Pane.MAP, Pane.CLOCK, Pane.SPEED) to listOf(Pane.MAP, Pane.CALL, Pane.SPEED),
            listOf(Pane.MAP, Pane.GARAGE, Pane.SPEED) to listOf(Pane.MAP, Pane.CALL, Pane.SPEED),
            listOf(Pane.MAP, Pane.NAV, Pane.SPEED) to listOf(Pane.MAP, Pane.NAV, Pane.CALL),
            listOf(Pane.MAP, Pane.NAV, Pane.CLOCK) to listOf(Pane.MAP, Pane.NAV, Pane.CALL),
            listOf(Pane.MAP, Pane.NAV, Pane.GARAGE) to listOf(Pane.MAP, Pane.NAV, Pane.CALL),
        )
        cases.forEach { (panes, expected) ->
            val s = DashboardLayoutState(assignments = DefaultAssignments + (LayoutPreset.DUO to panes))
            s.setCallActive(true)
            assertEquals("$panes", expected, s.view.panes)
        }
        val mapAndNav = DashboardLayoutState(
            preset = LayoutPreset.SIDE_BY_SIDE,
            assignments = DefaultAssignments + (LayoutPreset.SIDE_BY_SIDE to listOf(Pane.MAP, Pane.NAV)),
        )
        mapAndNav.setCallActive(true)
        assertEquals(listOf(Pane.MAP, Pane.CALL), mapAndNav.view.panes)
    }

    @Test fun `a fullscreen pane is never taken over, the card comes when it ends`() {
        val s = state()
        s.toggleFullscreen(Pane.SPEED)
        s.setCallActive(true)
        assertFalse(Pane.CALL in s.view.panes)
        assertEquals(CardMode.Fullscreen, s.modeOf(Pane.SPEED))
        s.back()
        assertTrue(Pane.CALL in s.view.panes)
    }

    @Test fun `only panes with room count when one is maximized`() {
        val s = state()
        s.toggleMaximized(Pane.SPEED) // fills the right column, media folds away
        s.setCallActive(true)
        // Media has no room; speed is the next to go.
        assertEquals(listOf(Pane.MAP, Pane.MEDIA, Pane.CALL), s.view.panes)
        assertEquals(Pane.CALL, s.view.maximized)
        assertEquals(CardMode.Expanded, s.modeOf(Pane.CALL))
    }

    @Test fun `a zoom on the call card is a zoom on the pane under it`() {
        val s = state()
        s.setCallActive(true)
        s.toggleMaximized(Pane.CALL)
        assertEquals(CardMode.Expanded, s.modeOf(Pane.CALL))
        s.setCallActive(false)
        assertEquals(Pane.MEDIA, s.view.maximized)
    }

    @Test fun `the call card has no swap and the rider's arrangement is untouched`() {
        val s = state()
        s.setCallActive(true)
        assertFalse(s.canSwap(Pane.CALL))
        assertEquals(DefaultAssignments.getValue(LayoutPreset.DUO), s.assignments.getValue(LayoutPreset.DUO))
    }

    @Test fun `quad gives up the lowest-priority of four`() {
        val s = state(LayoutPreset.QUAD)
        s.setCallActive(true)
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.CALL, Pane.CLOCK), s.view.panes)
    }
}
