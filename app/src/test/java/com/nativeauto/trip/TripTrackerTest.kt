package com.nativeauto.trip

import com.nativeauto.TestClock
import com.nativeauto.location.FakeDrive
import com.nativeauto.location.FakeSpeedSampleSource
import com.nativeauto.location.LocationDisabledException
import com.nativeauto.location.LocationPermissionException
import com.nativeauto.location.SpeedSampleSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real [FakeSpeedSampleSource] script through [TripTracker] in virtual time —
 * the same pipeline the emulator shows, so these numbers are what the card should display.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripTrackerTest {

    private val loopMs = FakeDrive.loopSeconds * 1_000L

    /** Ground-truth distance of one loop, integrated from the script's *true* speeds. */
    private fun trueLoopDistance(): Double {
        var prev = 0f
        var d = 0.0
        for (step in FakeDrive.script) when (step) {
            is FakeDrive.Step.Fix -> { d += (prev + step.trueSpeedMps) / 2.0; prev = step.trueSpeedMps }
            is FakeDrive.Step.Gap -> d += prev.toDouble() * step.seconds
        }
        return d
    }

    private val TripTracker.running get() = state.value == TripState.Running

    @Test fun `one loop of the fake drive gives the expected trip`() = runTest {
        val tracker = TripTracker(FakeSpeedSampleSource(TestClock(testScheduler)), TestClock(testScheduler), backgroundScope)
        tracker.start()
        advanceTimeBy(loopMs); runCurrent()

        val s = tracker.stats.value
        assertEquals(0L, s.startedAtNanos)
        // Max is the 100 km/h cruise, not either of the back-to-back spikes (198, 202 km/h).
        assertEquals(FakeDrive.CRUISE_MPS, s.maxSpeedMps, 1e-4f)
        // Parked at the end: jitter reads as zero.
        assertEquals(0f, s.currentSpeedMps, 0f)
        assertEquals(GpsStatus.Fix, s.gpsStatus)
        // Spikes are dropped, and the next good fix catches up, so distance matches the truth.
        assertEquals(trueLoopDistance(), s.distanceM, 0.05)
        // Moving time excludes the parked start and the stop at the end.
        val movingSeconds = s.movingNanos / 1e9
        assertTrue("moving $movingSeconds s", movingSeconds in 70.0..78.0)
        assertTrue(s.movingAverageMps() > s.averageSpeedMps(loopMs * 1_000_000L))
    }

    @Test fun `the tunnel leaves the last fix stale and then haversine catches up`() = runTest {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(FakeSpeedSampleSource(clock), clock, backgroundScope)
        tracker.start()

        // Index of the gap in the script = seconds of fixes before it.
        val secondsBeforeGap = FakeDrive.script.indexOfFirst { it is FakeDrive.Step.Gap }
        advanceTimeBy(secondsBeforeGap * 1_000L + 4_000L); runCurrent()
        val inTunnel = tracker.stats.value
        val age = clock.nowNanos() - inTunnel.lastSample!!.timestampNanos
        assertTrue("fix is ${age / 1e9} s old", age > 3_000_000_000L)

        advanceTimeBy((FakeDrive.TUNNEL_SECONDS - 4 + 1) * 1_000L); runCurrent()
        val out = tracker.stats.value
        val jump = out.distanceM - inTunnel.distanceM
        // 9 s at cruise speed (8 s gap + 1 s cadence), measured straight-line.
        assertEquals(FakeDrive.CRUISE_MPS * 9.0, jump, 1.0)
    }

    @Test fun `end halts collection and reset zeroes the trip`() = runTest {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(FakeSpeedSampleSource(clock), clock, backgroundScope)
        tracker.start()
        assertTrue(tracker.running)
        advanceTimeBy(30_000); runCurrent()

        tracker.end()
        assertEquals(TripState.Ended, tracker.state.value)
        val frozen = tracker.stats.value
        advanceTimeBy(10_000); runCurrent()
        assertEquals(frozen, tracker.stats.value)
        assertEquals(30_000_000_000L, frozen.elapsedNanos(clock.nowNanos()))

        tracker.reset()
        assertEquals(TripStats(), tracker.stats.value)
        assertNull(tracker.stats.value.startedAtNanos)
        assertEquals(TripState.Idle, tracker.state.value)
    }

    @Test fun `pause freezes the trip and resume excludes the paused time and distance`() = runTest {
        val clock = TestClock(testScheduler)
        val source = FlakySource()
        val tracker = TripTracker(source, clock, backgroundScope)
        // Fixes at 1 Hz, 20 m/s due north, from wherever we are.
        var lat = 51.0
        suspend fun drive(seconds: Int) = repeat(seconds) {
            advanceTimeBy(1_000)
            lat += 20.0 / METERS_PER_DEGREE_LAT
            source.fixes.emit(SpeedSample(clock.nowNanos(), 20f, 0.3f, 4f, lat, 0.0)); runCurrent()
        }
        tracker.start(); runCurrent()
        source.fixes.emit(SpeedSample(clock.nowNanos(), 20f, 0.3f, 4f, lat, 0.0)); runCurrent()
        drive(10) // 200 m in 10 s
        assertEquals(200.0, tracker.stats.value.distanceM, 1e-6)

        tracker.pause()
        assertEquals(TripState.Paused, tracker.state.value)
        // 60 s paused while the car keeps going 1.2 km: none of it may count.
        repeat(60) { advanceTimeBy(1_000); lat += 20.0 / METERS_PER_DEGREE_LAT }
        runCurrent()
        assertEquals(10_000_000_000L, tracker.stats.value.elapsedNanos(clock.nowNanos()))

        tracker.resume(); runCurrent()
        assertEquals(TripState.Running, tracker.state.value)
        assertNull("resume starts a new segment", tracker.stats.value.lastSample)
        source.fixes.emit(SpeedSample(clock.nowNanos(), 20f, 0.3f, 4f, lat, 0.0)); runCurrent()
        drive(10)

        val s = tracker.stats.value
        assertEquals(400.0, s.distanceM, 1e-6)
        assertEquals(20_000_000_000L, s.movingNanos)
        assertEquals(20_000_000_000L, s.elapsedNanos(clock.nowNanos()))
        assertEquals(20f, s.averageSpeedMps(clock.nowNanos()), 1e-4f)
    }

    @Test fun `opening the app keeps a pause, end freezes at the pause, and the next start is a new trip`() = runTest {
        val clock = TestClock(testScheduler)
        val source = FlakySource()
        val tracker = TripTracker(source, clock, backgroundScope)
        tracker.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        tracker.pause()
        advanceTimeBy(5_000); runCurrent()

        tracker.start() // what the service does each time the app comes to the foreground
        assertEquals(TripState.Paused, tracker.state.value)
        assertEquals(1, source.subscriptions)

        advanceTimeBy(5_000); runCurrent()
        tracker.end()
        assertEquals(TripState.Ended, tracker.state.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(30_000_000_000L, tracker.stats.value.elapsedNanos(clock.nowNanos()))

        tracker.start(); runCurrent()
        assertEquals(TripState.Running, tracker.state.value)
        assertEquals(clock.nowNanos(), tracker.stats.value.startedAtNanos)
        assertEquals(0L, tracker.stats.value.elapsedNanos(clock.nowNanos()))
        assertEquals(2, source.subscriptions)
    }

    @Test fun `pause, resume and end are ignored when they don't apply`() = runTest {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(FlakySource(), clock, backgroundScope)
        tracker.resume(); tracker.pause(); tracker.end()
        assertEquals(TripState.Idle, tracker.state.value)
        assertEquals(TripStats(), tracker.stats.value)
        tracker.start(); runCurrent()
        tracker.resume() // not paused
        assertEquals(TripState.Running, tracker.state.value)
    }

    @Test fun `reset while tracking restarts the trip clock now`() = runTest {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(FakeSpeedSampleSource(clock), clock, backgroundScope)
        tracker.start()
        advanceTimeBy(20_000); runCurrent()
        tracker.reset()
        assertEquals(clock.nowNanos(), tracker.stats.value.startedAtNanos)
        assertEquals(0.0, tracker.stats.value.distanceM, 0.0)
    }

    /** Fails like the fused source would, as scripted by the test. */
    private class FlakySource : SpeedSampleSource {
        var failure: Throwable? = null
        var enabled = true
        var subscriptions = 0
        val fixes = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 16)
        override val samples: Flow<SpeedSample> = flow {
            subscriptions++
            failure?.let { throw it }
            emitAll(fixes)
        }
        override fun isLocationEnabled() = enabled
    }

    @Test fun `location switched off shows Off and recovers when switched back on`() = runTest {
        val clock = TestClock(testScheduler)
        val source = FlakySource().apply { failure = LocationDisabledException(); enabled = false }
        val tracker = TripTracker(source, clock, backgroundScope)
        tracker.start(); runCurrent()
        assertEquals(GpsStatus.Disabled, tracker.stats.value.gpsStatus)
        assertTrue(tracker.running)

        // Still off: retries keep the status at Off, without a flicker to "searching".
        advanceTimeBy(TripTracker.RETRY_DISABLED_MS * 3); runCurrent()
        assertEquals(GpsStatus.Disabled, tracker.stats.value.gpsStatus)
        assertTrue(source.subscriptions >= 3)

        // Switched back on: the next retry subscribes for real and waits for a fix.
        source.failure = null; source.enabled = true
        advanceTimeBy(TripTracker.RETRY_DISABLED_MS); runCurrent()
        assertEquals(GpsStatus.Searching, tracker.stats.value.gpsStatus)
        source.fixes.emit(SpeedSample(clock.nowNanos(), 10f, 0.3f, 4f, 51.0, 0.0)); runCurrent()
        assertEquals(GpsStatus.Fix, tracker.stats.value.gpsStatus)
        assertEquals(10f, tracker.stats.value.currentSpeedMps, 0f)
    }

    @Test fun `missing permission ends tracking with Denied and a later start recovers`() = runTest {
        val clock = TestClock(testScheduler)
        val source = FlakySource().apply { failure = LocationPermissionException() }
        val tracker = TripTracker(source, clock, backgroundScope)
        tracker.start(); runCurrent()
        assertEquals(GpsStatus.PermissionDenied, tracker.stats.value.gpsStatus)
        assertEquals(TripState.Ended, tracker.state.value)
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no retry loop without access", 1, source.subscriptions)

        source.failure = null
        tracker.start(); runCurrent()
        assertTrue(tracker.running)
        assertEquals(GpsStatus.Searching, tracker.stats.value.gpsStatus)
        assertEquals(2, source.subscriptions)
    }

    @Test fun `other source errors are retried instead of crashing the app`() = runTest {
        val clock = TestClock(testScheduler)
        val source = FlakySource().apply { failure = IllegalStateException("Play services hiccup") }
        val tracker = TripTracker(source, clock, backgroundScope)
        tracker.start(); runCurrent()
        assertTrue(tracker.running)
        source.failure = null
        advanceTimeBy(TripTracker.RETRY_ERROR_MS); runCurrent()
        source.fixes.emit(SpeedSample(clock.nowNanos(), 10f, 0.3f, 4f, 51.0, 0.0)); runCurrent()
        assertEquals(GpsStatus.Fix, tracker.stats.value.gpsStatus)
        assertEquals(2, source.subscriptions)
    }

    @Test fun `start is idempotent`() = runTest {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(FakeSpeedSampleSource(clock), clock, backgroundScope)
        tracker.start()
        advanceTimeBy(10_000); runCurrent()
        tracker.start() // must not reset the start time or double-collect
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0L, tracker.stats.value.startedAtNanos)
        // 20 fixes in 20 s: a second collector would have doubled the accumulated moving time.
        assertTrue(tracker.stats.value.movingNanos <= 20_000_000_000L)
    }
}
