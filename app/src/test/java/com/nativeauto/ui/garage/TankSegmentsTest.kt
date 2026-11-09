package com.nativeauto.ui.garage

import org.junit.Assert.assertEquals
import org.junit.Test

/** 11c: the Garage card's 5-segment tank (Rider-Feedback §6). */
class TankSegmentsTest {

    @Test fun `each segment is a fifth of the tank`() {
        assertEquals(5, tankSegments(1.0, null))
        assertEquals(5, tankSegments(0.81, null))
        assertEquals(4, tankSegments(0.8, null))
        assertEquals(1, tankSegments(0.2, null))
        assertEquals(1, tankSegments(0.01, null))
        assertEquals(0, tankSegments(0.0, null))
        // Out of range is clamped.
        assertEquals(5, tankSegments(1.3, null))
        assertEquals(0, tankSegments(-0.1, null))
    }

    @Test fun `a boundary must be crossed by the hysteresis before the count changes`() {
        // Going down from 3: 0.39 is just under 0.4, still within 2%.
        assertEquals(3, tankSegments(0.39, previous = 3))
        assertEquals(2, tankSegments(0.379, previous = 3))
        // Coming back up from 2: needs more than 0.42.
        assertEquals(2, tankSegments(0.41, previous = 2))
        assertEquals(3, tankSegments(0.43, previous = 2))
    }

    @Test fun `a jump of more than one segment is taken at once`() {
        // A fill-up: 1 segment to full.
        assertEquals(5, tankSegments(1.0, previous = 1))
        assertEquals(1, tankSegments(0.1, previous = 4))
    }
}
