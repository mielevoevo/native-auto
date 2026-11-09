package com.nativeauto.ui.components

import androidx.compose.ui.unit.IntSize
import com.nativeauto.thermalName
import com.nativeauto.ui.map.heldMapSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 8d: the map's held size during moves, the refresh-mode choice, thermal names. */
class SmoothnessTest {

    private val window = IntSize(2400, 1080)

    @Test fun `a still map is laid out at its box`() {
        assertEquals(IntSize(1200, 1080), heldMapSize(moving = false, box = IntSize(1200, 1080), window = window))
    }

    @Test fun `a moving map keeps the window size whatever its box does`() {
        // Every frame of a maximize gives a different box; the map's own size must not change.
        val sizes = listOf(IntSize(1200, 1080), IntSize(1500, 1080), IntSize(1900, 700), IntSize(2400, 1080))
            .map { heldMapSize(moving = true, box = it, window = window) }
        assertEquals(setOf(window), sizes.toSet())
    }

    @Test fun `a box bigger than the window (rounding) is never shrunk`() {
        assertEquals(IntSize(2401, 1080), heldMapSize(moving = true, box = IntSize(2401, 1000), window = window))
    }

    private val hz60 = ModeSpec(1, 1080, 2400, 60f)
    private val hz90 = ModeSpec(2, 1080, 2400, 90f)
    private val hz120 = ModeSpec(3, 1080, 2400, 120f)
    private val lowRes120 = ModeSpec(4, 720, 1600, 144f)

    @Test fun `picks the fastest mode at the current resolution`() {
        assertEquals(hz120, highRefreshMode(listOf(hz60, hz90, hz120, lowRes120), current = hz60))
    }

    @Test fun `no hint when already at the fastest rate`() {
        assertNull(highRefreshMode(listOf(hz60, hz120), current = hz120))
    }

    @Test fun `no hint on a 60 Hz-only display (the emulator)`() {
        assertNull(highRefreshMode(listOf(hz60), current = hz60))
    }

    @Test fun `thermal status names`() {
        assertEquals("none", thermalName(0))
        assertEquals("severe", thermalName(3))
        assertEquals("shutdown", thermalName(6))
        assertEquals("unknown", thermalName(42))
    }
}
