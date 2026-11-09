package com.nativeauto.history

import com.nativeauto.TestClock
import com.nativeauto.core.WallClock
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        override suspend fun delete(id: Long) {
            trips.value = trips.value.filterNot { it.id == id }
        }
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

    private inner class Rig(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val source = Source()
        val dao = FakeTripDao()
        val addresses = FakeAddresses()
        val tracker = TripTracker(source, clock, scope.backgroundScope)
        val recorder = TripRecorder(tracker, dao, addresses, clock, WallClock { WALL_BASE_MS + scope.testScheduler.currentTime }, scope.backgroundScope)
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

    private companion object {
        const val SPEED = 20f
        const val WALL_BASE_MS = 1_762_000_000_000L
    }
}
