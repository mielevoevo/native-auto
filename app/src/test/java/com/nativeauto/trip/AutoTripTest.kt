package com.nativeauto.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoTripTest {
    private val min = 60_000_000_000L

    @Test fun `running without movement for the limit pauses`() {
        val s = TripStats(startedAtNanos = 0, lastMovingAtNanos = 2 * min)
        assertNull(autoAction(TripState.Running, s, 2 * min + 5 * min - 1, pauseMin = 5, endMin = 30))
        assertEquals(AutoAction.Pause, autoAction(TripState.Running, s, 7 * min, pauseMin = 5, endMin = 30))
    }

    @Test fun `no moving fix since the start counts from the start`() {
        val s = TripStats(startedAtNanos = 10 * min)
        assertNull(autoAction(TripState.Running, s, 14 * min, 5, 30))
        assertEquals(AutoAction.Pause, autoAction(TripState.Running, s, 15 * min, 5, 30))
    }

    @Test fun `paused for the limit ends`() {
        val s = TripStats(startedAtNanos = 0, pausedAtNanos = 10 * min)
        assertNull(autoAction(TripState.Paused, s, 39 * min, 5, 30))
        assertEquals(AutoAction.End, autoAction(TripState.Paused, s, 40 * min, 5, 30))
    }

    @Test fun `zero turns each rule off`() {
        val running = TripStats(startedAtNanos = 0)
        val paused = TripStats(startedAtNanos = 0, pausedAtNanos = 0)
        assertNull(autoAction(TripState.Running, running, 600 * min, pauseMin = 0, endMin = 30))
        assertNull(autoAction(TripState.Paused, paused, 600 * min, pauseMin = 5, endMin = 0))
    }

    @Test fun `idle and ended trips are left alone`() {
        val s = TripStats(startedAtNanos = 0, pausedAtNanos = 0)
        assertNull(autoAction(TripState.Idle, s, 600 * min, 5, 30))
        assertNull(autoAction(TripState.Ended, s, 600 * min, 5, 30))
    }
}
