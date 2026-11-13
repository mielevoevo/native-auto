package com.nativeauto.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartTest {
    private val sec = 1_000_000_000L
    private val hold = 30 * sec
    private val ride = 5.5f // ~20 km/h, over the 15 km/h line (4.17 m/s)

    private fun fix(t: Int, speed: Float, hAcc: Float = 5f, speedAcc: Float? = 0.3f, hasSpeed: Boolean = true, lonStep: Double = 0.00005) =
        SpeedSample(t * sec, speed, speedAcc, hAcc, -7.8, 110.0 + t * lonStep, hasSpeed = hasSpeed)

    /** Feeds one fix a second at [speed] from [from] to [to]; returns the first trigger time and the state. */
    private fun run(from: Int, to: Int, speed: Float, start: AutoStartState = AutoStartState()): Pair<Int?, AutoStartState> {
        var s = start
        for (t in from..to) {
            val step = stepAutoStart(s, fix(t, speed), hold)
            s = step.state
            if (step.triggered) return t to s
        }
        return null to s
    }

    @Test fun `thirty seconds at riding speed trigger, not before`() {
        val (at, _) = run(0, 60, ride)
        assertEquals(30, at)
    }

    @Test fun `the threshold is 15 km per hour`() {
        assertEquals(4.1667f, AUTO_START_MPS, 1e-3f)
        assertNull(run(0, 90, 4.1f).first)
        assertEquals(30, run(0, 90, 4.2f).first)
    }

    @Test fun `dropping below the line starts the hold over`() {
        var (_, s) = run(0, 20, ride)
        s = stepAutoStart(s, fix(21, 1f), hold).state
        assertNull(s.heldSinceNanos)
        val (at, _) = run(22, 80, ride, s)
        assertEquals(52, at) // 22 + 30
    }

    @Test fun `fixes without speed or with poor accuracy neither help nor reset`() {
        var (_, s) = run(0, 10, ride)
        s = stepAutoStart(s, fix(11, 0f, hasSpeed = false), hold).state
        s = stepAutoStart(s, fix(12, 0f, hAcc = 80f), hold).state
        s = stepAutoStart(s, fix(13, 0f, speedAcc = 4f), hold).state
        assertEquals(0L, s.heldSinceNanos)
        val (at, _) = run(14, 60, ride, s)
        assertEquals(30, at)
    }

    @Test fun `a gap in the fixes breaks the hold`() {
        var (_, s) = run(0, 20, ride)
        // Nothing for 15 s, then riding again: the 20 s already held don't count.
        val (at, _) = run(36, 100, ride, s)
        assertEquals(66, at)
    }

    @Test fun `the km ridden during the hold are counted`() {
        val (at, s) = run(0, 60, ride)
        assertEquals(30, at)
        // 30 s at 5.5 m/s is 165 m (30 intervals from the first fix to the trigger).
        assertEquals(30 * ride.toDouble(), s.stats.distanceM, 1.0)
    }

    @Test fun `allowed only from a clean state`() {
        assertTrue(autoStartAllowed(AutoStartMode.TRIP, TripState.Idle, endedThisSession = false, cleanAtOpen = true))
        assertFalse(autoStartAllowed(AutoStartMode.OFF, TripState.Idle, false, true))
        assertFalse("a trip already exists", autoStartAllowed(AutoStartMode.TRIP, TripState.Running, false, true))
        assertFalse("paused", autoStartAllowed(AutoStartMode.TRIP, TripState.Paused, false, true))
        assertFalse("ended this session", autoStartAllowed(AutoStartMode.TRIP, TripState.Idle, endedThisSession = true, cleanAtOpen = true))
        assertFalse("app opened with a trip", autoStartAllowed(AutoStartMode.ODOMETER_ONLY, TripState.Idle, false, cleanAtOpen = false))
        assertTrue(autoStartAllowed(AutoStartMode.ODOMETER_ONLY, TripState.Idle, false, true))
    }

    @Test fun `hold choices and the default`() {
        assertEquals(listOf(20, 30, 45, 60), AUTO_START_HOLD_CHOICES_S)
        assertTrue(DEFAULT_AUTO_START_HOLD_S in AUTO_START_HOLD_CHOICES_S)
    }
}
