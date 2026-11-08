package com.nativeauto.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class LayoutModelTest {

    /** Every shown pane, unzoomed: together they tile the screen exactly, with no overlap. */
    @Test fun `every layout tiles the screen`() {
        for (preset in LayoutPreset.entries) for (bigOnLeft in listOf(true, false)) for (split in listOf(0.3f, 0.5f, 0.7f)) {
            val panes = DefaultAssignments.getValue(preset)
            val rects = computeRects(LayoutView(preset, panes), bigOnLeft, split)
            val shown = panes.map { rects.getValue(it) }
            val label = "$preset left=$bigOnLeft split=$split"
            assertEquals(label, 1f, shown.sumOf { (it.width * it.height).toDouble() }.toFloat(), 1e-5f)
            shown.forEach { r -> assertTrue(label, r.left >= 0f && r.top >= 0f && r.right <= 1f + 1e-6f && r.bottom <= 1f + 1e-6f) }
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                val a = shown[i]; val b = shown[j]
                val overlap = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
                assertEquals("$label: ${panes[i]} overlaps ${panes[j]}", 0f, overlap, 1e-6f)
            }
            (Pane.entries - panes.toSet()).forEach { assertEquals("$label: $it parked", ParkedRect, rects.getValue(it)) }
        }
    }

    @Test fun `every layout has a fullscreen and every shared column a maximize`() {
        for (preset in LayoutPreset.entries) {
            val panes = DefaultAssignments.getValue(preset)
            for (p in panes) {
                val full = computeRects(LayoutView(preset, panes, fullscreen = p), true, 0.5f)
                assertEquals("$preset $p fullscreen", androidx.compose.ui.geometry.Rect(0f, 0f, 1f, 1f), full.getValue(p))
                (panes - p).forEach { assertEquals(0f, full.getValue(it).width * full.getValue(it).height, 0f) }
            }
        }
    }

    @Test fun `natural modes`() {
        assertEquals(CardMode.Expanded, naturalMode(LayoutPreset.DUO, 0))
        assertEquals(CardMode.Compact, naturalMode(LayoutPreset.DUO, 1))
        assertEquals(CardMode.Expanded, naturalMode(LayoutPreset.SIDE_BY_SIDE, 1))
        assertEquals(CardMode.Wide, naturalMode(LayoutPreset.ROWS, 0))
        assertEquals(CardMode.Compact, naturalMode(LayoutPreset.QUAD, 3))
    }

    @Test fun `defaults hold distinct panes, one per slot`() {
        for (preset in LayoutPreset.entries) {
            val panes = DefaultAssignments.getValue(preset)
            assertEquals(preset.slots, panes.size)
            assertEquals(panes.size, panes.toSet().size)
        }
        assertEquals(4, NavQuad.toSet().size)
    }

    // ---- codec ----

    @Test fun `codec round-trips`() {
        val custom = DefaultAssignments + (LayoutPreset.QUAD to listOf(Pane.NAV, Pane.MAP, Pane.CLOCK, Pane.SPEED))
        assertEquals(custom, LayoutCodec.decode(LayoutCodec.encode(custom)))
        assertEquals(
            "DUO=MAP,MEDIA,SPEED;SIDE_BY_SIDE=SPEED,MEDIA;ROWS=SPEED,MEDIA;QUAD=MAP,SPEED,MEDIA,CLOCK",
            LayoutCodec.encode(DefaultAssignments),
        )
    }

    @Test fun `blank or partial text fills in the defaults`() {
        assertEquals(DefaultAssignments, LayoutCodec.decode(""))
        val partial = LayoutCodec.decode("DUO=MAP,SPEED,MEDIA")
        assertEquals(listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA), partial.getValue(LayoutPreset.DUO))
        assertEquals(DefaultAssignments.getValue(LayoutPreset.QUAD), partial.getValue(LayoutPreset.QUAD))
    }

    @Test fun `damaged entries fall back to that layout's default`() {
        val decoded = LayoutCodec.decode(
            "DUO=MAP,MAP,SPEED;" +        // duplicate
                "ROWS=SPEED;" +           // too short
                "QUAD=MAP,SPEED,RADIO,CLOCK;" + // unknown pane
                "SIDE_BY_SIDE=CLOCK,NAV;" +   // fine
                "TRIPLE=MAP,SPEED,MEDIA;" + // unknown layout
                "garbage",
        )
        assertEquals(DefaultAssignments.getValue(LayoutPreset.DUO), decoded.getValue(LayoutPreset.DUO))
        assertEquals(DefaultAssignments.getValue(LayoutPreset.ROWS), decoded.getValue(LayoutPreset.ROWS))
        assertEquals(DefaultAssignments.getValue(LayoutPreset.QUAD), decoded.getValue(LayoutPreset.QUAD))
        assertEquals(listOf(Pane.CLOCK, Pane.NAV), decoded.getValue(LayoutPreset.SIDE_BY_SIDE))
    }

    @Test fun `unknown preset names mean Duo`() {
        assertEquals(LayoutPreset.QUAD, LayoutCodec.preset("QUAD"))
        assertEquals(LayoutPreset.DUO, LayoutCodec.preset(""))
        assertEquals(LayoutPreset.DUO, LayoutCodec.preset("TRIPLE"))
    }
}
