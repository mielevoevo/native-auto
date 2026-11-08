package com.nativeauto.ui.clock

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EdgeGeometryTest {

    // A wide pane, like the clock in Quad: 400 × 200 with a 12 px corner.
    private val halfW = 200f
    private val halfH = 100f
    private val r = 12f
    private val eps = 0.01f

    private fun at(f: Float) = edgeAt(f, halfW, halfH, r)

    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    /** Distance from p to the rounded rectangle's outline (0 when on it). */
    private fun offOutline(p: Offset): Float {
        val qx = abs(p.x) - (halfW - r)
        val qy = abs(p.y) - (halfH - r)
        val outside = Offset(max(qx, 0f), max(qy, 0f)).getDistance()
        return abs(outside + min(max(qx, qy), 0f) - r)
    }

    @Test fun `quarter turns land on the middle of each side`() {
        assertNear(Offset(0f, -halfH), at(0f).point)
        assertNear(Offset(halfW, 0f), at(0.25f).point)
        assertNear(Offset(0f, halfH), at(0.5f).point)
        assertNear(Offset(-halfW, 0f), at(0.75f).point)
        assertNear(Offset(0f, -1f), at(0f).normal)
        assertNear(Offset(1f, 0f), at(0.25f).normal)
    }

    @Test fun `a full turn wraps`() {
        assertNear(at(0.1f).point, at(1.1f).point)
        assertNear(at(0.9f).point, at(-0.1f).point)
    }

    @Test fun `every tick lies on the outline with an outward unit normal`() {
        for (i in 0 until 60) {
            val hit = at(i / 60f)
            assertEquals("tick $i off the outline", 0f, offOutline(hit.point), eps)
            assertEquals("tick $i normal length", 1f, hit.normal.getDistance(), eps)
            // Outward: the normal never points back towards the centre.
            assertTrue("tick $i normal points inward", hit.point.x * hit.normal.x + hit.point.y * hit.normal.y > 0f)
        }
    }

    @Test fun `ticks are evenly spaced along the edge, not crowded at 12`() {
        val gaps = (0 until 60).map { (at((it + 1) / 60f).point - at(it / 60f).point).getDistance() }
        // Chords are a hair shorter than arc steps only where a step crosses a corner.
        assertTrue(gaps.max() - gaps.min() < 1.5f)
    }

    @Test fun `the corner normal is diagonal halfway round it`() {
        val sx = halfW - r
        val sy = halfH - r
        val arc = (Math.PI / 2 * r).toFloat()
        val perimeter = 4 * sx + 4 * sy + 4 * arc
        val hit = at((sx + arc / 2) / perimeter)
        val d = (1 / Math.sqrt(2.0)).toFloat()
        assertNear(Offset(d, -d), hit.normal)
        assertNear(Offset(sx + r * d, -sy - r * d), hit.point)
    }

    @Test fun `a square corner (no radius) still works`() {
        val hit = edgeAt(0.125f, 100f, 100f, 0f)
        assertNear(Offset(100f, -100f), hit.point)
    }

    @Test fun `hand turns`() {
        assertEquals(HandTurns(0f, 0f, 0f), handTurns(0f, 0f, 0f))
        assertEquals(HandTurns(0.25f, 0.5f, 0.75f), handTurns(3f, 30f, 45f))
        // 12 o'clock and 60 minutes wrap to the top.
        assertEquals(HandTurns(0f, 0f, 0f), handTurns(12f, 60f, 60f))
        assertEquals(0.5f + 1f / 24f, handTurns(6.5f, 0f, 0f).hour, 1e-5f)
    }
}
