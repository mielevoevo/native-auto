package com.nativeauto.ui.speed

import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripStats
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedometerUiMappingTest {

    private val sec = 1_000_000_000L

    private fun sampleAt(t: Long) = SpeedSample(t, 27.78f, 0.3f, 4f, 51.0, 0.0)

    private val trip = TripStats(
        startedAtNanos = 0L,
        lastSample = sampleAt(600 * sec),
        currentSpeedMps = 27.78f,      // 100 km/h, 62 mph
        maxSpeedMps = 33.33f,          // 120 km/h, 75 mph
        distanceM = 10_000.0,          // 10 km over 600 s → 60 km/h average
        movingNanos = 450 * sec,       // 10 km / 450 s → 80 km/h moving average
        gpsStatus = GpsStatus.Fix,
    )

    @Test fun `location off outranks the stale rule and blanks the speed`() {
        val off = trip.copy(gpsStatus = GpsStatus.Disabled)
        // Fresh fix: still no speed, because the source has stopped.
        assertNull(off.toUiState(nowNanos = 600 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running).speed)
        // Old fix: says why ("Off"), not just "Lost".
        val stale = off.toUiState(nowNanos = 700 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertEquals(GpsStatus.Disabled, stale.gps)
        assertNull(stale.speed)
        // Trip stats stay visible.
        assertEquals(120, stale.maxSpeed)
    }

    @Test fun `missing permission shows Denied whatever the tracker last saw`() {
        val ui = trip.toUiState(nowNanos = 600 * sec, unit = SpeedUnit.KMH, tripState = TripState.Idle, locationGranted = false)
        assertEquals(GpsStatus.PermissionDenied, ui.gps)
        assertNull(ui.speed)
    }

    @Test fun `paused shows no live speed, a frozen clock and Paused in the GPS tile`() {
        // Paused at 600 s; it's now 900 s.
        val paused = trip.copy(pausedAtNanos = 600 * sec)
        val ui = paused.toUiState(nowNanos = 900 * sec, unit = SpeedUnit.KMH, tripState = TripState.Paused)
        assertNull(ui.speed)
        assertEquals(10.minutes, ui.elapsed)
        assertEquals(60, ui.avgSpeed) // 10 km / 600 s: the pause doesn't drag it down
        assertEquals("Paused", ui.statusLabel)
        assertEquals(false, ui.isTracking)
    }

    @Test fun `paused time is excluded once resumed`() {
        // 600 s of trip, of which 120 s were paused: 480 s of trip time, 75 km/h average.
        val resumed = trip.copy(pausedNanos = 120 * sec)
        val ui = resumed.toUiState(nowNanos = 600 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertEquals(8.minutes, ui.elapsed)
        assertEquals(75, ui.avgSpeed)
    }

    @Test fun `an ended trip is frozen at its end`() {
        val ended = trip.copy(endedAtNanos = 600 * sec)
        val ui = ended.toUiState(nowNanos = 3_600 * sec, unit = SpeedUnit.KMH, tripState = TripState.Ended)
        assertNull(ui.speed)
        assertEquals(10.minutes, ui.elapsed)
        assertEquals(60, ui.avgSpeed)
        assertEquals("Ended", ui.statusLabel)
    }

    @Test fun `converts to km per hour`() {
        val ui = trip.toUiState(nowNanos = 600 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertEquals(100, ui.speed)
        assertEquals(120, ui.maxSpeed)
        assertEquals(60, ui.avgSpeed)
        assertEquals(80, ui.movingAvgSpeed)
        assertEquals(10.0, ui.distance, 1e-9)
        assertEquals(10.minutes, ui.elapsed)
        assertEquals(GpsStatus.Fix, ui.gps)
        assertEquals(true, ui.isTracking)
    }

    @Test fun `converts to miles per hour`() {
        val ui = trip.toUiState(nowNanos = 600 * sec, unit = SpeedUnit.MPH, tripState = TripState.Running)
        assertEquals(62, ui.speed)
        assertEquals(75, ui.maxSpeed)
        assertEquals(37, ui.avgSpeed)
        assertEquals(6.2137, ui.distance, 1e-4)
        assertEquals(SpeedUnit.MPH, ui.unit)
    }

    @Test fun `a fix exactly 3 s old is still live`() {
        val ui = trip.toUiState(nowNanos = 603 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertEquals(100, ui.speed)
        assertEquals(GpsStatus.Fix, ui.gps)
    }

    @Test fun `a fix more than 3 s old is stale and shows dashes`() {
        val ui = trip.toUiState(nowNanos = 603 * sec + 1, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertNull("null renders as --", ui.speed)
        assertEquals(GpsStatus.Stale, ui.gps)
        // The trip stats themselves keep showing.
        assertEquals(120, ui.maxSpeed)
        assertEquals(10.0, ui.distance, 1e-9)
    }

    @Test fun `no fix yet shows dashes and keeps the source status`() {
        val ui = TripStats(startedAtNanos = 0L).toUiState(nowNanos = 5 * sec, unit = SpeedUnit.KMH, tripState = TripState.Running)
        assertNull(ui.speed)
        assertEquals(GpsStatus.Searching, ui.gps)
        assertEquals(5.seconds, ui.elapsed)
        assertEquals(0, ui.avgSpeed)
    }

    @Test fun `elapsed is zero before the trip starts`() {
        val ui = TripStats().toUiState(nowNanos = 99 * sec, unit = SpeedUnit.KMH, tripState = TripState.Idle)
        assertEquals(0.seconds, ui.elapsed)
        assertEquals(false, ui.isTracking)
    }
}
