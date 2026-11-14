package com.nativeauto.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 14b: Auto orientation turns only after a steady, generous lean. */
class OrientationPolicyTest {
    private val g = 9.8f

    /** Gravity as the phone reports it when leaned [deg] degrees from upright in the screen's plane. */
    private fun lean(deg: Float): Pair<Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        return (g * sin(r)).toFloat() to (g * cos(r)).toFloat()
    }

    private fun wanted(deg: Float) = lean(deg).let { (x, y) -> OrientationPolicy().wantedPortrait(x, y) }

    @Test fun `upright is portrait, on its side is landscape, either way up`() {
        assertEquals(true, wanted(0f))
        assertEquals(true, wanted(180f))
        assertEquals(false, wanted(90f))
        assertEquals(false, wanted(-90f))
    }

    @Test fun `a mount propped on its side and tilted a few degrees stays landscape`() {
        for (d in listOf(75f, 80f, 85f, 90f, 95f, 100f, 105f)) assertEquals("lean $d", false, wanted(d))
        // ...and a propped-upright mount tilted 10 degrees stays portrait.
        for (d in listOf(-10f, -5f, 5f, 10f)) assertEquals("lean $d", true, wanted(d))
    }

    @Test fun `the band between is undecided`() {
        for (d in listOf(35f, 45f, 55f, 125f, 135f, 145f)) assertNull("lean $d", wanted(d))
    }

    @Test fun `lying flat decides nothing`() {
        assertNull(OrientationPolicy().wantedPortrait(0.5f, 0.5f))
    }

    @Test fun `it turns only after the lean has held steady`() {
        val p = OrientationPolicy(steadyMs = 1_500)
        val (x, y) = lean(90f)
        assertNull(p.onSample(x, y, 0, currentPortrait = true))
        assertNull(p.onSample(x, y, 1_000, currentPortrait = true))
        assertEquals(false, p.onSample(x, y, 1_500, currentPortrait = true))
    }

    @Test fun `a break in the lean starts the wait again`() {
        val p = OrientationPolicy(steadyMs = 1_500)
        val (sx, sy) = lean(90f)
        val (ux, uy) = lean(0f)
        assertNull(p.onSample(sx, sy, 0, true))
        assertNull(p.onSample(ux, uy, 1_000, true)) // upright again: the pending turn is dropped
        assertNull(p.onSample(sx, sy, 1_200, true))
        assertNull(p.onSample(sx, sy, 2_600, true)) // only 1.4 s since it restarted
        assertEquals(false, p.onSample(sx, sy, 2_700, true))
    }

    @Test fun `the undecided band also breaks the wait`() {
        val p = OrientationPolicy(steadyMs = 1_500)
        val (sx, sy) = lean(90f)
        val (mx, my) = lean(45f)
        assertNull(p.onSample(sx, sy, 0, true))
        assertNull(p.onSample(mx, my, 1_000, true))
        assertNull(p.onSample(sx, sy, 1_100, true))
        assertNull(p.onSample(sx, sy, 2_000, true))
    }

    @Test fun `matching what is shown never turns`() {
        val p = OrientationPolicy(steadyMs = 0)
        val (x, y) = lean(0f)
        assertNull(p.onSample(x, y, 0, currentPortrait = true))
        assertNull(p.onSample(x, y, 10_000, currentPortrait = true))
    }

    @Test fun `the first guesses are generous`() {
        // The rider tunes these on the bike: pin the defaults so a change is deliberate.
        assertEquals(30f, OrientationTuning.ZONE_DEG, 0f)
        assertEquals(1_500L, OrientationTuning.STEADY_MS)
    }
}
