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

    // ---- 4.7.1: the call card is never stored ----

    @Test fun `in Quad a card dropped on the call card trades places with the pane under it`() {
        val s = state(LayoutPreset.QUAD)
        s.setCallActive(true)
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.CALL, Pane.CLOCK), s.view.panes)
        s.assign(Pane.MAP, Pane.CALL)
        assertEquals(listOf(Pane.CALL, Pane.SPEED, Pane.MAP, Pane.CLOCK), s.view.panes)
        s.setCallActive(false)
        assertEquals(listOf(Pane.MEDIA, Pane.SPEED, Pane.MAP, Pane.CLOCK), s.view.panes)
    }

    @Test fun `every card and the call card swap in every layout, and no layout ever holds the call card`() {
        LayoutPreset.entries.forEach { preset ->
            DefaultAssignments.getValue(preset).forEach { other ->
                listOf(true, false).forEach { callFirst ->
                    val s = DashboardLayoutState(preset = preset, portrait = preset.portrait)
                    s.setCallActive(true)
                    if (callFirst) s.assign(Pane.CALL, other) else s.assign(other, Pane.CALL)
                    s.swap(Pane.CALL)
                    s.setCallActive(false)
                    assertEquals("$preset $other", DefaultAssignments.getValue(preset).toSet(), s.view.panes.toSet())
                }
            }
        }
    }

    @Test fun `a call that ended before the drop changes nothing`() {
        val s = state()
        val before = s.view.panes
        s.assign(Pane.CALL, Pane.MAP)
        s.assign(Pane.MAP, Pane.CALL)
        s.swap(Pane.CALL)
        assertEquals(before, s.view.panes)
    }

    @Test fun `a drop during an auto layout keeps all its cards`() {
        val s = state(LayoutPreset.QUAD)
        s.applyAuto(LayoutView(LayoutPreset.QUAD, NavQuad))
        s.setCallActive(true)
        s.assign(Pane.MAP, Pane.CALL)
        s.setCallActive(false)
        assertEquals(listOf(Pane.MEDIA, Pane.NAV, Pane.SPEED, Pane.MAP), s.view.panes)
    }

    @Test fun `layouts damaged by 4_7 are repaired with the card they lack`() {
        val fixed = LayoutCodec.decode("QUAD=CALL,SPEED,MEDIA,CLOCK;SIDE_BY_SIDE=SPEED,CALL;STACK4=MAP,CALL,SPEED,MEDIA")
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK), fixed[LayoutPreset.QUAD])
        assertEquals(listOf(Pane.SPEED, Pane.MEDIA), fixed[LayoutPreset.SIDE_BY_SIDE])
        assertEquals(listOf(Pane.MAP, Pane.CLOCK, Pane.SPEED, Pane.MEDIA), fixed[LayoutPreset.STACK4])
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
