package com.nativeauto.history

import com.nativeauto.TestClock
import com.nativeauto.core.WallClock
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderTest {

    private class Source : SpeedSampleSource {
        val fixes = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
        override val samples: Flow<SpeedSample> = fixes
    }

    private class FakeTripDao : TripDao {
        val trips = MutableStateFlow<List<TripRecord>>(emptyList())
        override fun observeAll() = trips
        override fun observe(id: Long) = trips.map { list -> list.find { it.id == id } }
        override suspend fun insert(trip: TripRecord): Long {
            val id = trips.value.size + 1L
            trips.value += trip.copy(id = id)
            return id
        }
        override suspend fun setAddresses(id: Long, start: String?, end: String?) {
            trips.value = trips.value.map { if (it.id == id) it.copy(startAddress = start, endAddress = end) else it }
        }
        override suspend fun missingAddresses() = trips.value.filter { it.startAddress == null || it.endAddress == null }
        val track = mutableListOf<TrackPointRecord>()
        override suspend fun insertPoints(points: List<TrackPointRecord>) { track += points }
        override suspend fun points(tripId: Long) = track.filter { it.tripId == tripId }
        override suspend fun deleteTrip(id: Long) {
            trips.value = trips.value.filterNot { it.id == id }
        }
        override suspend fun deletePoints(tripId: Long) { track.removeAll { it.tripId == tripId } }
    }

    /** Names a point by its rounded northing; [online] off answers null, like a failed lookup. */
    private class FakeAddresses : AddressResolver {
        var online = true
        val asked = mutableListOf<GeoPoint>()
        override suspend fun addressOf(point: GeoPoint): String? {
            asked += point
            return if (online) "N ${Math.round(point.latitude * METERS_PER_DEGREE_LAT)} m" else null
        }
    }

    /** 11b: the trip checkpoint, kept across a simulated process death. */
    private class FakeCheckpoints : TripCheckpoints {
        var trip: LiveTripRecord? = null
        val fixes = mutableListOf<SpeedSample>()
        var writes = 0
        override suspend fun write(trip: LiveTripRecord, newFixes: List<SpeedSample>, replace: Boolean) {
            writes++
            if (replace) fixes.clear()
            this.trip = trip
            fixes += newFixes
        }
        override suspend fun read() = trip?.let { Checkpoint(it, fixes.toList()) }
        override suspend fun clear() { trip = null; fixes.clear() }
    }

    /**
     * One app process. [after] shares the previous process's storage and GPS (a relaunch after
     * a kill); [kill] cancels everything the process was running.
     */
    private inner class Rig(val scope: TestScope, after: Rig? = null) {
        val clock = TestClock(scope.testScheduler)
        val source: Source = after?.source ?: Source()
        val dao: FakeTripDao = after?.dao ?: FakeTripDao()
        val addresses: FakeAddresses = after?.addresses ?: FakeAddresses()
        val checkpoints: FakeCheckpoints = after?.checkpoints ?: FakeCheckpoints()
        private val process = CoroutineScope(scope.backgroundScope.coroutineContext + Job())
        /** The Garage's active bike, as the recorder reads it at save time (11a). */
        var bikeId: String? = null
        val tracker = TripTracker(source, clock, process)
        val recorder = TripRecorder(tracker, dao, addresses, { bikeId }, checkpoints, clock, WallClock { WALL_BASE_MS + scope.testScheduler.currentTime }, process)
            .also {
                // The geometry tests drive a few hundred metres; the real limits have their own tests.
                it.minDistanceM = 100.0
                it.minMovingMs = 0
                it.start()
            }

        /** Metres north of the equator; fixes run due north. */
        var north = 0.0

        fun fix(speed: Float = SPEED, accuracy: Float = 4f, east: Double = 0.0) = SpeedSample(
            clock.nowNanos(), speed, 0.3f, accuracy, north / METERS_PER_DEGREE_LAT, east / METERS_PER_DEGREE_LAT,
        )

        fun emit(sample: SpeedSample) {
            source.fixes.tryEmit(sample)
            scope.runCurrent()
        }

        /** A first fix here, then [seconds] of 1 Hz fixes at [SPEED] due north. */
        fun drive(seconds: Int) {
            emit(fix())
            repeat(seconds) {
                scope.advanceTimeBy(1_000)
                north += SPEED
                emit(fix())
            }
        }

        fun startTrip() {
            tracker.start()
            scope.runCurrent()
        }

        fun kill() {
            process.cancel()
            scope.runCurrent()
        }
    }

    @Test fun `an ended trip is saved once, with its figures, path and addresses`() = runTest {
        val rig = Rig(this)
        advanceTimeBy(5_000)
        rig.startTrip()
        rig.drive(30) // 600 m in 30 s
        rig.tracker.end()
        runCurrent()
        // More state changes after the end don't save it again.
        advanceTimeBy(10_000); runCurrent()
        rig.tracker.discard(); runCurrent()

        val t = rig.dao.trips.value.single()
        assertEquals(WALL_BASE_MS + 5_000, t.startedAtMs)
        assertEquals(WALL_BASE_MS + 35_000, t.endedAtMs)
        assertEquals(30_000L, t.durationMs)
        assertEquals(30_000L, t.movingMs)
        assertEquals(600.0, t.distanceM, 1e-6)
        assertEquals(SPEED, t.maxSpeedMps, 0f)
        assertEquals(0.0, t.startLat, 1e-9)
        assertEquals(600.0, t.endLat * METERS_PER_DEGREE_LAT, 1e-6)
        // A straight line is simplified to its two ends.
        val track = Polyline.decode(t.track)
        assertEquals(2, track.size)
        assertEquals(600.0, track.last().latitude * METERS_PER_DEGREE_LAT, 1.2)
        assertEquals("N 0 m", t.startAddress)
        assertEquals("N 600 m", t.endAddress)
        // 4.3d: the riding load's sums go with it (steady speed: drag and rolling, no speeding up).
        assertEquals(600.0, t.loadDistanceM!!, 1e-6)
        assertEquals(SPEED.toDouble() * SPEED * 600.0, t.loadAero!!, 1.0)
        assertEquals(0.0, t.loadAccel!!, 1e-9)
        assertTrue(t.loadIndex!! > 0)
    }

    @Test fun `a trip under 100 m isn't kept`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(4) // 80 m
        rig.tracker.end(); runCurrent()
        assertEquals(emptyList<TripRecord>(), rig.dao.trips.value)
    }

    /** At the real limits (9a): at least 300 m and 2 min of moving time. */
    private fun Rig.realLimits() = apply {
        recorder.minDistanceM = TripRecorder.MIN_DISTANCE_M
        recorder.minMovingMs = TripRecorder.MIN_MOVING_MS
    }

    @Test fun `a fast trip under 2 min of moving isn't kept`() = runTest {
        val rig = Rig(this).realLimits()
        rig.startTrip()
        rig.drive(100) // 2 km, but only 100 s
        rig.tracker.end(); runCurrent()
        assertEquals(emptyList<TripRecord>(), rig.dao.trips.value)
    }

    @Test fun `a long slow trip under 300 m isn't kept`() = runTest {
        val rig = Rig(this).realLimits()
        rig.startTrip()
        rig.emit(rig.fix(speed = 1f))
        repeat(200) { // 200 m in 200 s
            advanceTimeBy(1_000)
            rig.north += 1.0
            rig.emit(rig.fix(speed = 1f))
        }
        rig.tracker.end(); runCurrent()
        assertEquals(emptyList<TripRecord>(), rig.dao.trips.value)
    }

    @Test fun `a trip over both limits is kept`() = runTest {
        val rig = Rig(this).realLimits()
        rig.startTrip()
        rig.drive(121) // 2.42 km in 121 s
        rig.tracker.end(); runCurrent()
        assertEquals(2420.0, rig.dao.trips.value.single().distanceM, 1e-6)
    }

    @Test fun `the full track keeps every accepted fix with its time, speed and accuracy`() = runTest {
        val rig = Rig(this)
        advanceTimeBy(5_000)
        rig.startTrip()
        rig.drive(30) // a first fix + 30 more, straight north
        rig.tracker.end(); runCurrent()

        val t = rig.dao.trips.value.single()
        val points = rig.dao.points(t.id)
        assertEquals(31, points.size) // unsimplified, unlike t.track
        assertEquals(WALL_BASE_MS + 5_000, points.first().timeMs)
        assertEquals(WALL_BASE_MS + 35_000, points.last().timeMs)
        assertTrue(points.all { it.speedMps == SPEED && it.accuracyM == 4f })
        assertEquals(600.0, points.last().latitude * METERS_PER_DEGREE_LAT, 1e-6)
        assertEquals(0L, t.stoppedMs)
        assertNull(t.bikeId)

        rig.dao.delete(t.id)
        assertEquals(emptyList<TrackPointRecord>(), rig.dao.points(t.id))
    }

    @Test fun `each point keeps its altitude to a tenth of a metre, or none`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.emit(rig.fix().copy(altitudeM = 113.449, altitudeAccuracyM = 2.26f))
        repeat(10) { i ->
            advanceTimeBy(1_000)
            rig.north += SPEED
            // A fix without altitude, and one that isn't a number.
            rig.emit(rig.fix().copy(altitudeM = if (i == 0) null else if (i == 1) Double.NaN else 120.0 + i, altitudeAccuracyM = if (i == 2) Float.NaN else null))
        }
        rig.tracker.end(); runCurrent()
        val points = rig.dao.points(rig.dao.trips.value.single().id)
        assertEquals(113.4f, points[0].altitudeM)
        assertEquals(2.3f, points[0].altitudeAccuracyM)
        assertEquals(listOf(null, null, 122f, 123f), points.subList(1, 5).map { it.altitudeM })
        assertNull(points[3].altitudeAccuracyM)
    }

    @Test fun `a trip is tagged with the bike active when it is saved`() = runTest {
        val rig = Rig(this)
        rig.bikeId = "Gen 1"
        rig.startTrip()
        rig.drive(30)
        // Switched mid-trip: the whole trip goes to the bike selected last.
        rig.bikeId = "Gen 2"
        rig.tracker.end(); runCurrent()
        assertEquals("Gen 2", rig.dao.trips.value.single().bikeId)
    }

    @Test fun `stopped time is trip time below walking pace, pauses excluded`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(10)
        repeat(20) { // 20 s at a light
            advanceTimeBy(1_000)
            rig.emit(rig.fix(speed = 0f))
        }
        rig.tracker.pause(); runCurrent()
        advanceTimeBy(60_000)
        rig.tracker.end(); runCurrent()
        val t = rig.dao.trips.value.single()
        assertEquals(30_000L, t.durationMs)
        assertEquals(10_000L, t.movingMs)
        assertEquals(20_000L, t.stoppedMs)
    }

    @Test fun `the outcome says saved or too short, and clears on the next start`() = runTest {
        val rig = Rig(this).realLimits()
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end(); runCurrent()
        assertEquals(TripOutcome.TooShort, rig.recorder.lastOutcome.value)

        rig.tracker.discard(); runCurrent()
        rig.startTrip()
        advanceTimeBy(1_000); rig.north += SPEED
        rig.emit(rig.fix())
        assertNull(rig.recorder.lastOutcome.value)
        rig.drive(130)
        rig.tracker.end(); runCurrent()
        val saved = rig.recorder.lastOutcome.value as TripOutcome.Saved
        assertEquals(rig.dao.trips.value.single().id, saved.trip.id)
    }

    @Test fun `a trip that never got a fix isn't kept`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        advanceTimeBy(60_000); runCurrent()
        rig.tracker.end(); runCurrent()
        assertEquals(emptyList<TripRecord>(), rig.dao.trips.value)
    }

    @Test fun `reset discards the trip and the next start records a new one`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(10) // 200 m
        rig.tracker.discard(); runCurrent()
        assertEquals(emptyList<TripRecord>(), rig.dao.trips.value)

        // The new trip's path starts where it was started, not where the discarded one began.
        advanceTimeBy(1_000)
        rig.north += SPEED
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end(); runCurrent()
        val second = rig.dao.trips.value.single()
        assertEquals(220.0, second.startLat * METERS_PER_DEGREE_LAT, 1e-6)
        assertEquals(420.0, second.endLat * METERS_PER_DEGREE_LAT, 1e-6)
        assertEquals(200.0, second.distanceM, 1e-6)
    }

    @Test fun `rejected fixes never reach the path`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(5)
        // A poor fix 500 m east, then a spike 300 m east: both ignored by the reducer.
        advanceTimeBy(500); rig.emit(rig.fix(accuracy = 60f, east = 500.0))
        advanceTimeBy(250); rig.emit(rig.fix(speed = 90f, east = 300.0))
        advanceTimeBy(250)
        rig.north += SPEED
        rig.emit(rig.fix())
        rig.drive(5)
        rig.tracker.end(); runCurrent()

        val t = rig.dao.trips.value.single()
        Polyline.decode(t.track).forEach { assertEquals(0.0, it.longitude, 1e-9) }
        assertEquals(0.0, t.endLon, 1e-9)
    }

    @Test fun `a corner is kept in the simplified path`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(10) // 200 m north
        var east = 0.0
        repeat(10) { // then 200 m east
            advanceTimeBy(1_000)
            east += SPEED
            rig.emit(rig.fix(east = east))
        }
        rig.tracker.end(); runCurrent()

        val track = Polyline.decode(rig.dao.trips.value.single().track)
        assertEquals(3, track.size)
        assertEquals(200.0, track[1].latitude * METERS_PER_DEGREE_LAT, 1.2)
        assertEquals(0.0, track[1].longitude * METERS_PER_DEGREE_LAT, 1.2)
        assertEquals(200.0, track[2].longitude * METERS_PER_DEGREE_LAT, 1.2)
    }

    @Test fun `addresses missed offline are filled in later`() = runTest {
        val rig = Rig(this)
        rig.addresses.online = false
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end(); runCurrent()
        assertNull(rig.dao.trips.value.single().startAddress)

        rig.addresses.online = true
        rig.recorder.fillMissingAddresses(); runCurrent()
        val t = rig.dao.trips.value.single()
        assertEquals("N 0 m", t.startAddress)
        assertEquals("N 200 m", t.endAddress)

        // Nothing left to fill: no more lookups.
        val asked = rig.addresses.asked.size
        rig.recorder.fillMissingAddresses(); runCurrent()
        assertEquals(asked, rig.addresses.asked.size)
    }

    @Test fun `a paused trip ended later is saved with the pause excluded`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(10)
        rig.tracker.pause(); runCurrent()
        advanceTimeBy(60_000)
        rig.tracker.end(); runCurrent()

        val t = rig.dao.trips.value.single()
        assertEquals(10_000L, t.durationMs)
        // Ended at the pause: the clock froze there.
        assertEquals(WALL_BASE_MS + 10_000, t.endedAtMs)
        assertTrue(t.averageSpeedMps > 19f)
    }

    // ---- 11b: checkpoints and recovery ----

    @Test fun `a running trip is checkpointed about once a minute, with only the new fixes appended`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(59)
        assertNull(rig.checkpoints.trip)
        rig.drive(5) // past a minute
        val first = rig.checkpoints.trip!!
        assertEquals(rig.checkpoints.fixes.size, rig.checkpoints.fixes.distinct().size)
        assertTrue(first.distanceM > 1_000)
        rig.drive(61)
        assertEquals(2, rig.checkpoints.writes)
        // Every accepted fix is there once, in order.
        assertEquals(rig.checkpoints.fixes.sortedBy { it.timestampNanos }, rig.checkpoints.fixes)
        // A pause is written at once.
        rig.tracker.pause(); runCurrent()
        assertTrue(rig.checkpoints.trip!!.paused)
        // Ending clears it.
        rig.tracker.end(); runCurrent()
        assertNull(rig.checkpoints.trip)
        assertEquals(1, rig.dao.trips.value.size)
    }

    @Test fun `a discarded trip clears its checkpoint`() = runTest {
        val rig = Rig(this)
        rig.startTrip()
        rig.drive(70)
        assertTrue(rig.checkpoints.trip != null)
        rig.tracker.discard(); runCurrent()
        assertNull(rig.checkpoints.trip)
        assertTrue(rig.dao.trips.value.isEmpty())
    }

    @Test fun `a trip killed mid-ride resumes within ten minutes, joining the gap in a straight line`() = runTest {
        val first = Rig(this)
        first.startTrip()
        first.drive(70) // 1.4 km; checkpoint at 60 s holds ~1.2 km
        val saved = first.checkpoints.trip!!
        first.kill()

        advanceTimeBy(3 * 60_000L)
        val second = Rig(this, after = first)
        second.north = first.north + 2_000.0 // rode on 2 km while the app was dead
        assertEquals(Recovery.Resumed, second.recorder.recover())
        runCurrent()
        assertEquals(com.nativeauto.trip.TripState.Running, second.tracker.state.value)
        assertEquals(saved.distanceM, second.tracker.stats.value.distanceM, 1e-6)
        // Only once per process.
        assertEquals(Recovery.None, second.recorder.recover())

        second.emit(second.fix())
        val gapStart = first.checkpoints.fixes.last().latitude * METERS_PER_DEGREE_LAT
        val joined = second.tracker.stats.value.distanceM
        assertEquals(saved.distanceM + (second.north - gapStart), joined, 1.0)
        second.drive(10)
        second.tracker.end(); runCurrent()
        val t = second.dao.trips.value.single()
        assertEquals(WALL_BASE_MS, t.startedAtMs)
        assertEquals(joined + 10 * SPEED, t.distanceM, 1.0)
        // The path runs from the first process's start to the end of the second.
        assertEquals(0.0, t.startLat, 1e-9)
        assertEquals(second.north, t.endLat * METERS_PER_DEGREE_LAT, 1e-6)
        assertNull(second.checkpoints.trip)
    }

    @Test fun `a trip lost longer ago is saved as ended at its last checkpoint`() = runTest {
        val first = Rig(this)
        advanceTimeBy(1_000)
        first.startTrip()
        first.drive(70)
        val saved = first.checkpoints.trip!!
        val lastFix = first.checkpoints.fixes.last()
        first.kill()

        advanceTimeBy(TripRecorder.RESUME_WITHIN_MS)
        val second = Rig(this, after = first)
        assertEquals(Recovery.SavedEnded, second.recorder.recover())
        runCurrent()
        assertEquals(com.nativeauto.trip.TripState.Idle, second.tracker.state.value)
        val t = second.dao.trips.value.single()
        assertEquals(WALL_BASE_MS + 1_000, t.startedAtMs)
        assertEquals(WALL_BASE_MS + lastFix.timestampNanos / 1_000_000, t.endedAtMs)
        assertEquals(saved.distanceM, t.distanceM, 1e-6)
        assertNull(second.checkpoints.trip)
        // The end-of-trip summary isn't raised for it.
        assertNull(second.recorder.lastOutcome.value)
    }

    @Test fun `a recovered trip keeps its altitudes and its climb`() = runTest {
        val first = Rig(this)
        first.startTrip()
        first.emit(first.fix().copy(altitudeM = 100.0, altitudeAccuracyM = 3f))
        repeat(70) { i ->
            advanceTimeBy(1_000)
            first.north += SPEED
            first.emit(first.fix().copy(altitudeM = 101.0 + i, altitudeAccuracyM = 3f))
        }
        first.kill()
        // As it is stored: every field makes it through the checkpoint's row.
        val stored = first.checkpoints.fixes.toList()
        first.checkpoints.fixes.clear()
        first.checkpoints.fixes += stored.map { it.toRecord().toSample() }
        assertEquals(stored, first.checkpoints.fixes.toList())

        advanceTimeBy(TripRecorder.RESUME_WITHIN_MS)
        val second = Rig(this, after = first)
        assertEquals(Recovery.SavedEnded, second.recorder.recover())
        runCurrent()
        val t = second.dao.trips.value.single()
        val points = second.dao.points(t.id)
        assertEquals(100f, points.first().altitudeM)
        assertTrue(points.all { it.altitudeM != null && it.altitudeAccuracyM == 3f })
        assertTrue("climb ${t.loadClimbM}", t.loadClimbM!! > 40)
    }

    @Test fun `no checkpoint means nothing to recover`() = runTest {
        val rig = Rig(this)
        assertEquals(Recovery.None, rig.recorder.recover())
        assertEquals(com.nativeauto.trip.TripState.Idle, rig.tracker.state.value)
    }

    private companion object {
        const val SPEED = 20f
        const val WALL_BASE_MS = 1_762_000_000_000L
    }
}
