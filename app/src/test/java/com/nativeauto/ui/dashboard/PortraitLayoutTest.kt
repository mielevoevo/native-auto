package com.nativeauto.ui.dashboard

import androidx.compose.ui.geometry.Rect
import com.nativeauto.settings.SavedLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 14b: the portrait presets, rotation between orientations, and what is saved per orientation. */
class PortraitLayoutTest {

    @Test fun `portrait presets are the stacks and big + two`() {
        val portrait = LayoutPreset.entries.filter { it.portrait }
        assertEquals(
            listOf(LayoutPreset.STACK1, LayoutPreset.STACK2, LayoutPreset.STACK3, LayoutPreset.BIG_TWO, LayoutPreset.STACK4),
            portrait,
        )
        assertEquals(listOf(1, 2, 3, 3, 4), portrait.map { it.slots })
        assertTrue(LayoutPreset.entries.filter { !it.portrait }.all { it in listOf(LayoutPreset.DUO, LayoutPreset.SIDE_BY_SIDE, LayoutPreset.ROWS, LayoutPreset.QUAD) })
    }

    @Test fun `stacks split the height evenly, full width`() {
        for (n in 1..4) {
            val preset = LayoutPreset.entries.first { it.portrait && it.slots == n && it != LayoutPreset.BIG_TWO }
            val panes = DefaultAssignments.getValue(preset)
            val rects = computeRects(LayoutView(preset, panes), true, 0.5f)
            panes.forEachIndexed { i, p ->
                assertEquals(Rect(0f, i.toFloat() / n, 1f, (i + 1).toFloat() / n), rects.getValue(p))
            }
        }
    }

    @Test fun `big + two is the big pane over two equal halves`() {
        val panes = DefaultAssignments.getValue(LayoutPreset.BIG_TWO)
        val r = computeRects(LayoutView(LayoutPreset.BIG_TWO, panes), true, 0.5f)
        assertEquals(Rect(0f, 0f, 1f, BIG_TWO_TOP), r.getValue(panes[0]))
        assertEquals(Rect(0f, BIG_TWO_TOP, 0.5f, 1f), r.getValue(panes[1]))
        assertEquals(Rect(0.5f, BIG_TWO_TOP, 1f, 1f), r.getValue(panes[2]))
        // The lower pair is exactly 50/50: same width and the same height.
        assertEquals(r.getValue(panes[1]).width, r.getValue(panes[2]).width, 0f)
        assertEquals(r.getValue(panes[1]).height, r.getValue(panes[2]).height, 0f)
    }

    @Test fun `a fullscreen portrait pane takes everything and the rest fold into an edge`() {
        for (preset in LayoutPreset.entries.filter { it.portrait }) {
            val panes = DefaultAssignments.getValue(preset)
            for (p in panes) {
                val r = computeRects(LayoutView(preset, panes, fullscreen = p), true, 0.5f)
                assertEquals("$preset $p", Rect(0f, 0f, 1f, 1f), r.getValue(p))
                (panes - p).forEach { other ->
                    val o = r.getValue(other)
                    assertEquals("$preset $other has no height", 0f, o.height, 0f)
                    assertTrue("$preset $other sits on an edge", o.top == 0f || o.top == 1f)
                }
            }
        }
    }

    @Test fun `natural modes in portrait`() {
        assertEquals(CardMode.Expanded, naturalMode(LayoutPreset.STACK1, 0))
        assertEquals(CardMode.Expanded, naturalMode(LayoutPreset.STACK2, 1))
        assertEquals(CardMode.Wide, naturalMode(LayoutPreset.STACK3, 2))
        assertEquals(CardMode.Expanded, naturalMode(LayoutPreset.BIG_TWO, 0))
        assertEquals(CardMode.Narrow, naturalMode(LayoutPreset.BIG_TWO, 1))
        assertEquals(CardMode.Narrow, naturalMode(LayoutPreset.BIG_TWO, 2))
        assertEquals(CardMode.Mini, naturalMode(LayoutPreset.STACK4, 3))
    }

    // ---- rotation keeps count and order ----

    private fun landscape(preset: LayoutPreset, panes: List<Pane>) = LayoutView(preset, panes)

    @Test fun `landscape to portrait keeps the panes and their order`() {
        val duo = rotatedView(landscape(LayoutPreset.DUO, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA)), toPortrait = true)
        assertEquals(LayoutView(LayoutPreset.STACK3, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA)), duo)
        val two = rotatedView(landscape(LayoutPreset.SIDE_BY_SIDE, listOf(Pane.SPEED, Pane.MEDIA)), toPortrait = true)
        assertEquals(LayoutView(LayoutPreset.STACK2, listOf(Pane.SPEED, Pane.MEDIA)), two)
        val rows = rotatedView(landscape(LayoutPreset.ROWS, listOf(Pane.MAP, Pane.NAV)), toPortrait = true)
        assertEquals(LayoutPreset.STACK2, rows.preset)
        val quad = rotatedView(landscape(LayoutPreset.QUAD, listOf(Pane.MAP, Pane.NAV, Pane.SPEED, Pane.MEDIA)), toPortrait = true)
        assertEquals(LayoutView(LayoutPreset.STACK4, listOf(Pane.MAP, Pane.NAV, Pane.SPEED, Pane.MEDIA)), quad)
    }

    @Test fun `three panes use the portrait shape the rider last used, else the plain stack`() {
        val panes = listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA)
        assertEquals(LayoutPreset.STACK3, rotatedView(landscape(LayoutPreset.DUO, panes), true).preset)
        assertEquals(LayoutPreset.BIG_TWO, rotatedView(landscape(LayoutPreset.DUO, panes), true, lastPortrait = LayoutPreset.BIG_TWO).preset)
        // A remembered preset with another pane count is not used.
        assertEquals(LayoutPreset.STACK3, rotatedView(landscape(LayoutPreset.DUO, panes), true, lastPortrait = LayoutPreset.STACK4).preset)
    }

    @Test fun `portrait to landscape, one pane gets a neighbour`() {
        assertEquals(LayoutPreset.DUO, rotatedView(LayoutView(LayoutPreset.STACK3, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA)), false).preset)
        assertEquals(LayoutPreset.DUO, rotatedView(LayoutView(LayoutPreset.BIG_TWO, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA)), false).preset)
        assertEquals(LayoutPreset.QUAD, rotatedView(LayoutView(LayoutPreset.STACK4, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK)), false).preset)
        val one = rotatedView(LayoutView(LayoutPreset.STACK1, listOf(Pane.SPEED)), false)
        assertEquals(LayoutPreset.SIDE_BY_SIDE, one.preset)
        assertEquals(Pane.SPEED, one.panes.first())
        assertEquals(2, one.panes.toSet().size)
        assertEquals(LayoutPreset.ROWS, rotatedView(LayoutView(LayoutPreset.STACK2, listOf(Pane.SPEED, Pane.MAP)), false, lastLandscape = LayoutPreset.ROWS).preset)
    }

    @Test fun `a fullscreen pane stays, a maximized one is dropped`() {
        val v = rotatedView(LayoutView(LayoutPreset.DUO, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA), fullscreen = Pane.MAP), true)
        assertEquals(Pane.MAP, v.fullscreen)
        val m = rotatedView(LayoutView(LayoutPreset.DUO, listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA), maximized = Pane.MEDIA), true)
        assertNull(m.maximized)
    }

    @Test fun `the layout state turns with the phone and turns back`() {
        val s = DashboardLayoutState(preset = LayoutPreset.DUO, assignments = DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA)))
        s.setPortrait(true)
        assertTrue(s.portrait)
        assertEquals(LayoutPreset.STACK3, s.preset)
        assertEquals(listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA), s.view.panes)
        s.setPortrait(false)
        assertFalse(s.portrait)
        assertEquals(LayoutPreset.DUO, s.preset)
        assertEquals(listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA), s.view.panes)
    }

    @Test fun `each orientation remembers its own preset`() {
        val s = DashboardLayoutState(preset = LayoutPreset.ROWS)
        s.setPortrait(true)
        assertEquals(LayoutPreset.STACK2, s.preset)
        s.setPreset(LayoutPreset.BIG_TWO)
        s.setPortrait(false)
        assertEquals(LayoutPreset.DUO, s.preset)
        assertEquals(LayoutPreset.BIG_TWO, s.lastPortraitPreset)
        assertEquals(LayoutPreset.ROWS, s.lastLandscapePreset)
        // Back to portrait: three panes, and "big + two" is the shape last used there.
        s.setPortrait(true)
        assertEquals(LayoutPreset.BIG_TWO, s.preset)
    }

    @Test fun `rotating is not an arrangement by hand`() {
        val s = DashboardLayoutState()
        val before = s.lastArrangedAtMs
        s.setPortrait(true)
        assertEquals(before, s.lastArrangedAtMs)
    }

    @Test fun `a preset of the other orientation is ignored`() {
        val s = DashboardLayoutState()
        s.setPreset(LayoutPreset.STACK3)
        assertEquals(LayoutPreset.DUO, s.preset)
        s.setPortrait(true)
        s.setPreset(LayoutPreset.QUAD)
        assertEquals(LayoutPreset.STACK3, s.preset)
    }

    @Test fun `portrait has no maximize, a swap only between two stacked panes`() {
        val s = DashboardLayoutState().apply { setPortrait(true) }
        assertEquals(LayoutPreset.STACK3, s.preset)
        s.view.panes.forEach { assertFalse(s.canMaximize(it)) }
        s.view.panes.forEach { assertFalse(s.canSwap(it)) }
        s.setPreset(LayoutPreset.STACK2)
        s.view.panes.forEach { assertTrue(s.canSwap(it)) }
        val top = s.view.panes[0]
        s.swap(top)
        assertEquals(top, s.view.panes[1])
    }

    @Test fun `an auto-layout override turns with the phone`() {
        val s = DashboardLayoutState(preset = LayoutPreset.SIDE_BY_SIDE)
        s.applyAuto(LayoutView(LayoutPreset.DUO, listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED)))
        s.setPortrait(true)
        assertEquals(LayoutPreset.STACK3, s.view.preset)
        assertEquals(listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED), s.view.panes)
        assertEquals(LayoutPreset.STACK2, s.userView.preset)
    }

    // ---- what is saved ----

    @Test fun `a portrait start with nothing saved derives from the landscape layout`() {
        val saved = SavedLayout(bigOnLeft = true, split = 0.5f, preset = "DUO",
            slots = LayoutCodec.encode(DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA))))
        val s = saved.toLayoutState(portrait = true)
        assertTrue(s.portrait)
        assertEquals(LayoutPreset.STACK3, s.preset)
        assertEquals(listOf(Pane.SPEED, Pane.MAP, Pane.MEDIA), s.view.panes)
        assertEquals(LayoutPreset.DUO, s.lastLandscapePreset)
    }

    @Test fun `a portrait start uses the preset saved for portrait`() {
        val saved = SavedLayout(bigOnLeft = true, split = 0.5f, preset = "DUO", portraitPreset = "BIG_TWO",
            slots = LayoutCodec.encode(DefaultAssignments + (LayoutPreset.BIG_TWO to listOf(Pane.NAV, Pane.SPEED, Pane.MAP))))
        val s = saved.toLayoutState(portrait = true)
        assertEquals(LayoutPreset.BIG_TWO, s.preset)
        assertEquals(listOf(Pane.NAV, Pane.SPEED, Pane.MAP), s.view.panes)
    }

    @Test fun `saving keeps the landscape preset where older versions read it`() {
        val s = DashboardLayoutState(preset = LayoutPreset.QUAD).apply { setPortrait(true); setPreset(LayoutPreset.STACK4) }
        val saved = s.toSaved()
        assertEquals("QUAD", saved.preset)
        assertEquals("STACK4", saved.portraitPreset)
        // Landscape again: both are remembered.
        s.setPortrait(false)
        val back = s.toSaved()
        assertEquals("QUAD", back.preset)
        assertEquals("STACK4", back.portraitPreset)
    }

    @Test fun `a damaged saved portrait preset is ignored`() {
        assertNull(LayoutCodec.portraitPreset("DUO"))
        assertNull(LayoutCodec.portraitPreset("nonsense"))
        assertNull(LayoutCodec.portraitPreset(""))
        assertEquals(LayoutPreset.STACK2, LayoutCodec.portraitPreset("STACK2"))
        // A landscape name saved as the landscape preset is kept, a portrait one there falls back to Duo.
        assertEquals("DUO", SavedLayout(true, 0.5f, preset = "STACK3").sanitized().preset)
    }

    @Test fun `the codec round-trips the portrait layouts too`() {
        val custom = DefaultAssignments + (LayoutPreset.STACK4 to listOf(Pane.NAV, Pane.MAP, Pane.CLOCK, Pane.SPEED))
        assertEquals(custom, LayoutCodec.decode(LayoutCodec.encode(custom)))
        // An older preferences string with only landscape layouts still decodes, portrait ones take defaults.
        val old = LayoutCodec.decode("DUO=MAP,MEDIA,SPEED")
        assertEquals(DefaultAssignments.getValue(LayoutPreset.BIG_TWO), old.getValue(LayoutPreset.BIG_TWO))
    }

    // ---- auto layout, call takeover ----

    @Test fun `navigation in the portrait four-stack takes the navigation set`() {
        val s = DashboardLayoutState(preset = LayoutPreset.STACK4, portrait = true)
        AutoLayout.onNavigationStarted(s, enabled = true)
        assertEquals(LayoutView(LayoutPreset.STACK4, NavQuad), s.view)
        AutoLayout.onNavigationEnded(s)
        assertEquals(LayoutPreset.STACK4, s.view.preset)
    }

    @Test fun `navigation with no map on screen in portrait shows big + two with the map`() {
        val s = DashboardLayoutState().apply {
            setPortrait(true)
            setPreset(LayoutPreset.STACK2)
            assign(Pane.MAP, Pane.MEDIA)
        }
        assertFalse(Pane.MAP in s.view.panes)
        val v = AutoLayout.navigationView(s.userView, s.assignments)
        assertEquals(LayoutPreset.BIG_TWO, v?.preset)
        assertEquals(Pane.MAP, v?.panes?.first())
    }

    @Test fun `a call takes media's slot in a portrait stack and gives it back`() {
        val s = DashboardLayoutState().apply { setPortrait(true) } // STACK3: map, media, speed (Duo's order, turned)
        val before = s.view.panes
        s.setCallActive(true)
        assertEquals(before.map { if (it == Pane.MEDIA) Pane.CALL else it }, s.view.panes)
        s.setCallActive(false)
        assertEquals(before, s.view.panes)
    }

    @Test fun `a call never takes a fullscreen portrait pane`() {
        val s = DashboardLayoutState().apply { setPortrait(true); toggleFullscreen(Pane.MEDIA) }
        s.setCallActive(true)
        assertFalse(Pane.CALL in s.view.panes)
    }
}
