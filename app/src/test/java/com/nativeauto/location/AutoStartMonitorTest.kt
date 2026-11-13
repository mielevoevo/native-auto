package com.nativeauto.location

import com.nativeauto.FakeSettingsRepository
import com.nativeauto.TestClock
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoStartMonitorTest {
    private val sec = 1_000_000_000L

    private class Source(val fixes: List<SpeedSample>) : SpeedSampleSource {
        override val samples: Flow<SpeedSample> = emptyFlow()
        override val watchSamples: Flow<SpeedSample> = flow {
            fixes.forEach { emit(it) }
            awaitCancellation()
        }
    }

    private class Actions : AutoStartActions {
        var started = 0
        var km = 0.0
        override fun startTrip() { started++ }
        override suspend fun addOdometerKm(km: Double) { this.km += km }
    }

    /** One fix a second at [speed] m/s from [from] to [to] seconds, moving east. */
    private fun ride(from: Int, to: Int, speed: Float) = (from..to).map { t ->
        SpeedSample(t * sec, speed, 0.3f, 5f, -7.8, 110.0 + t * speed / 111_000.0, hasSpeed = true)
    }

    private class Rig(val monitor: AutoStartMonitor, val tracker: TripTracker, val settings: FakeSettingsRepository, val actions: Actions)

    private fun TestScope.rig(fixes: List<SpeedSample>, mode: AutoStartMode, holdS: Int = 30): Rig {
        val settings = FakeSettingsRepository().also { it.autoStartMode.value = mode; it.autoStartHoldS.value = holdS }
        val tracker = TripTracker(object : SpeedSampleSource { override val samples: Flow<SpeedSample> = emptyFlow() }, TestClock(testScheduler), backgroundScope)
        val actions = Actions()
        return Rig(AutoStartMonitor(Source(fixes), tracker, settings, actions), tracker, settings, actions)
    }

    @Test fun `trip mode starts a trip once after the hold and announces it`() = runTest {
        val r = rig(ride(0, 60, 6f), AutoStartMode.TRIP)
        var announced = 0
        backgroundScope.launch { r.monitor.announcements.collect { announced++ } }
        r.monitor.onAppOpened()
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        assertEquals(1, r.actions.started)
        assertEquals(1, announced)
    }

    @Test fun `nothing happens with auto-start off`() = runTest {
        val r = rig(ride(0, 120, 6f), AutoStartMode.OFF)
        r.monitor.onAppOpened()
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        assertEquals(0, r.actions.started)
        assertEquals(0.0, r.actions.km, 0.0)
    }

    @Test fun `too slow for the hold does nothing`() = runTest {
        val r = rig(ride(0, 25, 6f), AutoStartMode.TRIP)
        r.monitor.onAppOpened()
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        assertEquals(0, r.actions.started)
    }

    @Test fun `a trip that already exists when the app opened is never joined by auto-start`() = runTest {
        val r = rig(ride(0, 60, 6f), AutoStartMode.TRIP)
        r.tracker.start()
        r.monitor.onAppOpened() // not clean
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        assertEquals(0, r.actions.started)
    }

    @Test fun `after a trip was ended auto-start stays quiet until the app is opened again`() = runTest {
        val r = rig(ride(0, 60, 6f), AutoStartMode.TRIP)
        r.monitor.onAppOpened()
        r.tracker.start()
        // The watch is up while the rider's trip runs and ends (it sees the Ended state go by).
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        r.tracker.end()
        runCurrent()
        r.tracker.discard() // the summary was dismissed: Idle again
        runCurrent()
        assertEquals("ended this session", 0, r.actions.started)

        r.monitor.onAppOpened() // opened afresh
        // The running watch only re-plans on a state or setting change; nudge it as a new session's watch would start.
        r.settings.autoStartHoldS.value = 20
        runCurrent()
        assertEquals(1, r.actions.started)
    }

    @Test fun `odometer only adds the km ridden, from the start of the hold, without a trip`() = runTest {
        // 40 s at 6 m/s ≈ 240 m... use a long ride: 200 s ≈ 1.2 km.
        val r = rig(ride(0, 200, 6f), AutoStartMode.ODOMETER_ONLY)
        r.monitor.onAppOpened()
        val job = backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        assertEquals(0, r.actions.started)
        assertTrue("committed in chunks while riding: ${r.actions.km}", r.actions.km > 0.5)
        job.cancel() // the app goes to the background: the rest is committed
        runCurrent()
        assertEquals(6.0 * 200 / 1000, r.actions.km, 0.05)
    }

    @Test fun `odometer only stops counting after a long stop and a new ride must earn its hold`() = runTest {
        val first = ride(0, 60, 6f)
        val stopped = (61..260).map { t -> SpeedSample(t * sec, 0f, 0.3f, 5f, -7.8, 110.0 + 60 * 6f / 111_000.0, hasSpeed = true) }
        val r = rig(first + stopped, AutoStartMode.ODOMETER_ONLY)
        r.monitor.onAppOpened()
        backgroundScope.launch { r.monitor.watch() }
        runCurrent()
        val km = r.actions.km
        assertEquals(6.0 * 60 / 1000, km, 0.05)
    }
}
