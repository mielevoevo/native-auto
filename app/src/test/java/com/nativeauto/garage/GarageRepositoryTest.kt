package com.nativeauto.garage

import com.nativeauto.TestClock
import com.nativeauto.core.WallClock
import com.nativeauto.history.TripRecord
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Garage against an in-memory DAO (same SQL semantics) and a real [TripTracker]. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarageRepositoryTest {

    private class FakeGarageDao : GarageDao {
        val bikes = MutableStateFlow<List<BikeRecord>>(emptyList())
        val readings = MutableStateFlow<List<OdometerReading>>(emptyList())
        val trips = MutableStateFlow<List<TripRecord>>(emptyList())

        override fun observeBikes() = bikes.map { l -> l.sortedBy { it.createdAtMs } }
        override fun observeActive() = bikes.map { l -> l.firstOrNull { it.active } }
        override suspend fun activeBike() = bikes.value.firstOrNull { it.active }
        override suspend fun bike(id: String) = bikes.value.firstOrNull { it.id == id }
        override suspend fun bikesByUse() = bikes.value.sortedByDescending { it.lastUsedAtMs }
        override suspend fun insertBike(bike: BikeRecord) { bikes.value += bike }
        override suspend fun updateBike(bike: BikeRecord) { bikes.value = bikes.value.map { if (it.id == bike.id) bike else it } }
        override suspend fun setFactor(id: String, factor: Double) {
            bikes.value = bikes.value.map { if (it.id == id) it.copy(odoFactor = factor) else it }
        }
        override suspend fun deactivateAll(nowMs: Long) {
            bikes.value = bikes.value.map { if (it.active) it.copy(active = false, lastUsedAtMs = nowMs) else it }
        }
        override suspend fun markActive(id: String) { bikes.value = bikes.value.map { if (it.id == id) it.copy(active = true) else it } }
        override suspend fun deleteBikeRow(id: String) { bikes.value = bikes.value.filterNot { it.id == id } }
        override suspend fun deleteReadings(id: String) { readings.value = readings.value.filterNot { it.bikeId == id } }
        override suspend fun untagTrips(id: String) { trips.value = trips.value.map { if (it.bikeId == id) it.copy(bikeId = null) else it } }
        override suspend fun tagUntaggedTrips(id: String) { trips.value = trips.value.map { if (it.bikeId == null) it.copy(bikeId = id) else it } }
        override suspend fun insertReading(reading: OdometerReading): Long {
            val id = readings.value.size + 1L
            readings.value += reading.copy(id = id)
            return id
        }
        private fun sorted(bikeId: String) = readings.value.filter { it.bikeId == bikeId }.sortedWith(compareByDescending<OdometerReading> { it.timeMs }.thenByDescending { it.id })
        override fun observeReadings(bikeId: String): Flow<List<OdometerReading>> = readings.map { sorted(bikeId) }
        override fun observeLastReading(bikeId: String) = readings.map { sorted(bikeId).firstOrNull() }
        override suspend fun lastReading(bikeId: String) = sorted(bikeId).firstOrNull()
        private fun meters(list: List<TripRecord>, bikeId: String, sinceMs: Long, offsetM: Double) =
            list.filter { it.bikeId == bikeId && it.endedAtMs > sinceMs }
                .sumOf { if (it.startedAtMs < sinceMs) maxOf(it.distanceM - offsetM, 0.0) else it.distanceM }
        override fun observeMetersSince(bikeId: String, sinceMs: Long, offsetM: Double) = trips.map { meters(it, bikeId, sinceMs, offsetM) }
        override suspend fun metersSince(bikeId: String, sinceMs: Long, offsetM: Double) = meters(trips.value, bikeId, sinceMs, offsetM)
        override suspend fun lastTrip(bikeId: String) = trips.value.filter { it.bikeId == bikeId }.maxByOrNull { it.endedAtMs }
        override suspend fun deleteAllBikes() { bikes.value = emptyList() }
        override suspend fun deleteAllReadings() { readings.value = emptyList() }
    }

    private class Source : SpeedSampleSource {
        val fixes = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 64)
        override val samples: Flow<SpeedSample> = fixes
    }

    private inner class Rig(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val source = Source()
        val dao = FakeGarageDao()
        val tracker = TripTracker(source, clock, scope.backgroundScope)
        val garage = GarageRepository(dao, tracker, clock, WallClock { WALL_BASE_MS + scope.testScheduler.currentTime }, scope.backgroundScope)
        val nmax = BikeDraft("Gen 1", BikeModel.NMAX_GEN1, 6.6, 1.3, FuelType.PERTAMAX, DEFAULT_KM_PER_L)

        fun now() = WALL_BASE_MS + scope.testScheduler.currentTime

        /** A saved trip of [km] for [bikeId], ending now. */
        fun savedTrip(bikeId: String?, km: Double, startedAgoMs: Long = 600_000) {
            val end = now()
            dao.trips.value += TripRecord(
                id = dao.trips.value.size + 1L, startedAtMs = end - startedAgoMs, endedAtMs = end, durationMs = startedAgoMs,
                movingMs = startedAgoMs, distanceM = km * 1000, maxSpeedMps = 20f, startLat = 0.0, startLon = 0.0,
                endLat = -7.8, endLon = 110.37, track = "", bikeId = bikeId,
            )
        }

        var north = 0.0
        fun fix() = SpeedSample(
            timestampNanos = clock.nowNanos(), speedMps = 20f, speedAccuracyMps = 0.5f,
            horizontalAccuracyM = 4f, latitude = north / METERS_PER_DEGREE_LAT, longitude = 0.0,
        )

        private var moved = false

        /** Drives the live trip [seconds] at 20 m/s (the first call starts with a fix where it stands). */
        fun drive(seconds: Int) {
            if (!moved) { moved = true; source.fixes.tryEmit(fix()); scope.runCurrent() }
            repeat(seconds) {
                scope.advanceTimeBy(1_000)
                north += 20.0
                source.fixes.tryEmit(fix()); scope.runCurrent()
            }
        }
    }

    @Test fun `the first bike is active and adopts the old trips`() = runTest {
        val rig = Rig(this)
        rig.savedTrip(null, 5.0)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2", model = BikeModel.NMAX_GEN2), odometerKm = null)
        assertEquals(gen1, rig.garage.id())
        assertEquals(gen1, rig.dao.trips.value.single().bikeId)
        assertEquals(listOf(true, false), rig.dao.bikes.value.map { it.active })
        assertEquals(12_000.0, rig.garage.estimateNow(gen1)!!, 0.0)
        assertNull(rig.garage.estimateNow(gen2))
    }

    @Test fun `saved trips of the bike advance its odometer, times the factor`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        advanceTimeBy(60_000)
        rig.savedTrip(gen1, 10.0, startedAgoMs = 30_000)
        rig.savedTrip("other", 99.0, startedAgoMs = 30_000)
        assertEquals(12_010.0, rig.garage.estimateNow(gen1)!!, 1e-6)
        assertEquals(12_010.0, rig.garage.odometer(gen1).first().estimateKm!!, 1e-6)
    }

    @Test fun `a correction re-anchors and teaches the factor`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 3_000_000)
        val d = rig.garage.enterReading(gen1, 1_104.0)
        assertEquals(ReadingDecision.Store(ReadingKind.CORRECTION), d)
        val factor = rig.dao.bike(gen1)!!.odoFactor
        assertEquals(1.02, factor, 1e-9)
        assertEquals(1_104.0, rig.garage.estimateNow(gen1)!!, 1e-9)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 50.0, startedAgoMs = 3_000_000)
        assertEquals(1_104.0 + 51.0, rig.garage.estimateNow(gen1)!!, 1e-6)
    }

    @Test fun `a reading taken mid-trip counts only the rest of that trip`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.tracker.start(); runCurrent()
        rig.drive(50) // 1 km
        // At the pump, the rider reads the real odometer.
        rig.garage.enterReading(gen1, 2_000.0)
        assertEquals(2_000.0, rig.garage.estimateNow(gen1)!!, 1e-6)
        rig.drive(25) // 500 m more
        assertEquals(2_000.5, rig.garage.estimateNow(gen1)!!, 1e-6)
        assertEquals(2_000.5, rig.garage.odometer(gen1).first().estimateKm!!, 0.011) // live flow rounds to 10 m
        // The trip ends and is saved with its full 1.5 km; still only the last 500 m count.
        val started = rig.now() - 76_000
        rig.tracker.end(); runCurrent()
        rig.dao.trips.value += TripRecord(
            id = 1, startedAtMs = started, endedAtMs = rig.now(), durationMs = 76_000, movingMs = 76_000,
            distanceM = rig.tracker.stats.value.distanceM, maxSpeedMps = 20f, startLat = 0.0, startLon = 0.0,
            endLat = 0.0, endLon = 0.0, track = "", bikeId = gen1,
        )
        assertEquals(2_000.5, rig.garage.estimateNow(gen1)!!, 1e-6)
    }

    @Test fun `switching to a bike idle for days asks for its odometer, and away km is stored`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        // A quick switch doesn't ask.
        rig.garage.activate(gen2)
        assertNull(rig.garage.prompt.value)
        rig.garage.activate(gen1)
        advanceTimeBy(AWAY_AFTER_MS)
        rig.garage.activate(gen2)
        val p = rig.garage.prompt.value as GaragePrompt.OdometerNow
        assertEquals(gen2, p.bike.id)
        assertEquals(30_000.0, p.estimateKm!!, 0.0)

        rig.garage.answerOdometerPrompt(30_230.0)
        assertNull(rig.garage.prompt.value)
        val r = rig.dao.lastReading(gen2)!!
        assertEquals(ReadingKind.AWAY, r.readingKind)
        assertEquals(230.0, r.awayKm, 1e-9)
        assertEquals(30_230.0, rig.garage.estimateNow(gen2)!!, 0.0)
        // Away km don't teach the factor.
        assertEquals(1.0, rig.dao.bike(gen2)!!.odoFactor, 0.0)
    }

    @Test fun `skipping the away prompt stores nothing`() = runTest {
        val rig = Rig(this)
        rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        advanceTimeBy(AWAY_AFTER_MS)
        rig.garage.activate(gen2)
        rig.garage.answerOdometerPrompt(null)
        assertNull(rig.garage.prompt.value)
        assertEquals(1, rig.dao.readings.value.count { it.bikeId == gen2 })
    }

    @Test fun `deleting the active bike hands over to the most recently used one`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = null)
        rig.savedTrip(gen1, 5.0)
        rig.garage.deleteBike(gen1)
        assertEquals(gen2, rig.garage.id())
        assertNull(rig.dao.trips.value.single().bikeId)
        assertTrue(rig.dao.readings.value.none { it.bikeId == gen1 })
    }

    @Test fun `which bike is asked once per trip when it starts far from the last ride`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        rig.savedTrip(gen1, 5.0) // ended far away; the rig drives near 0,0
        rig.garage.startWhichBikeCheck(); runCurrent()
        rig.tracker.start(); runCurrent()
        rig.drive(2)
        val p = rig.garage.prompt.value as GaragePrompt.WhichBike
        assertEquals(gen1, p.activeId)
        assertEquals(listOf(gen1, gen2), p.bikes.map { it.id })
        rig.garage.answerWhichBike(gen2)
        assertEquals(gen2, rig.garage.id())
        // More fixes of the same trip don't ask again.
        rig.drive(2)
        assertNull(rig.garage.prompt.value)
    }

    private companion object {
        const val WALL_BASE_MS = 1_700_000_000_000L
    }
}
