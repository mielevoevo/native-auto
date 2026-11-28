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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Garage against an in-memory DAO (same SQL semantics) and a real [TripTracker]. */
@OptIn(ExperimentalCoroutinesApi::class)
class GarageRepositoryTest {

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

    /** 4.7: the rider's own odometer correction counts from when it was set; learning carries on behind it. */
    @Test fun `an own correction counts from now on and the learned one comes back`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 3_000_000)
        rig.garage.enterReading(gen1, 1_104.0) // learned: 1.02
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 50.0, startedAgoMs = 3_000_000)
        val before = rig.garage.estimateNow(gen1)!!
        rig.garage.updateBike(gen1, rig.nmax.copy(odoFactorManual = 0.95))
        assertEquals("nothing jumps", before, rig.garage.estimateNow(gen1)!!, 1e-6)
        assertTrue(rig.garage.odometer(gen1).first().manualFactor)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 3_000_000)
        assertEquals(before + 95.0, rig.garage.estimateNow(gen1)!!, 1e-6)
        // A correction right after the change measures from a kept estimate: it teaches nothing.
        rig.garage.enterReading(gen1, before + 98.0)
        assertEquals(1.02, rig.dao.bike(gen1)!!.odoFactor, 1e-9)
        // The next one, from a real reading, does; the own one stays in use.
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 3_000_000)
        rig.garage.enterReading(gen1, before + 98.0 + 104.0)
        assertEquals(1.03, rig.dao.bike(gen1)!!.odoFactor, 1e-9)
        assertEquals(0.95, rig.dao.bike(gen1)!!.factor, 0.0)
        // Back to what the app learned.
        val now = rig.garage.estimateNow(gen1)!!
        rig.garage.updateBike(gen1, rig.nmax)
        assertEquals(now, rig.garage.estimateNow(gen1)!!, 1e-6)
        assertEquals(1.03, rig.garage.odometer(gen1).first().factor, 1e-9)
        assertFalse(rig.garage.odometer(gen1).first().manualFactor)
    }

    /** 4.7: saving the editor without touching the correction stores no reading. */
    @Test fun `an edit that leaves the correction alone keeps the readings`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.garage.updateBike(gen1, rig.nmax.copy(gaugeBars = 10, startKmPerL = 40.0))
        assertEquals(1, rig.dao.readings.value.size)
        assertEquals(10, rig.dao.bike(gen1)!!.gaugeBars)
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

    // ---- 11b ----

    private fun refuel(full: Boolean, litres: Double, odometerKm: Double, price: Double = 12_500.0) =
        RefuelDraft(full, litres, price, litres * price, FuelType.PERTAMAX, odometerKm)

    @Test fun `untracked km re-anchor the odometer and teach nothing`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 3_000_000)
        assertTrue(rig.garage.addUntrackedKm(gen1, 42.0))
        val r = rig.dao.lastReading(gen1)!!
        assertEquals(ReadingKind.UNTRACKED, r.readingKind)
        assertEquals(42.0, r.awayKm, 0.0)
        assertEquals(1_142.0, r.km, 1e-9)
        assertEquals(1_142.0, rig.garage.estimateNow(gen1)!!, 1e-9)
        assertEquals(1.0, rig.dao.bike(gen1)!!.odoFactor, 0.0)
        // Nothing to add to before the odometer is set, and no zero or negative km.
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = null)
        assertEquals(false, rig.garage.addUntrackedKm(gen2, 10.0))
        assertEquals(false, rig.garage.addUntrackedKm(gen1, 0.0))
    }

    @Test fun `a refuel at the estimate keeps it exactly, a different reading is stored as a correction`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 10.25, startedAgoMs = 3_000_000)
        // The prefilled, rounded estimate (1010) accepted: no reading logged, the exact estimate kept.
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_010.0, price = 12_900.0))
        assertEquals(1, rig.dao.readings.value.size)
        val first = rig.dao.refuels.value.single()
        assertEquals(1_010.25, first.odometerKm, 1e-9)
        assertEquals(12_900.0, rig.dao.prices.value.single().pricePerL, 0.0)
        // The pump shows 1,050: that's the truth, logged as a correction.
        advanceTimeBy(60_000)
        rig.garage.logRefuel(gen1, refuel(full = false, litres = 1.0, odometerKm = 1_050.0))
        assertEquals(ReadingKind.CORRECTION, rig.dao.lastReading(gen1)!!.readingKind)
        assertEquals(1_050.0, rig.dao.refuels.value.last().odometerKm, 0.0)
        assertEquals(12_500.0, rig.dao.prices.value.single().pricePerL, 0.0)
    }

    @Test fun `a gauge reading sets the level until a full fill, dash km per litre trains`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.garage.setFuelLevel(gen1, bars = 3, ofBars = 6, odometerKm = 1_000.4)
        var f = rig.garage.fuel(gen1).first()
        assertEquals(gaugeLitres(3, 6, 6.6), f.level.litres!!, 1e-9)
        assertTrue(f.level.fromGauge)
        // Within a km of the estimate: no correction logged.
        assertEquals(1, rig.dao.readings.value.size)
        assertTrue(rig.garage.addDashKmPerL(gen1, 42.0, 20.0))
        assertFalse(rig.garage.addDashKmPerL(gen1, 500.0, 20.0))
        f = rig.garage.fuel(gen1).first()
        assertEquals(1, f.consumption.dashUsed)
        advanceTimeBy(60_000)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 4.0, odometerKm = 1_000.0))
        f = rig.garage.fuel(gen1).first()
        assertFalse(f.level.fromGauge)
        assertEquals(6.6, f.level.litres!!, 1e-9)
    }

    @Test fun `fuel state follows fill-ups, rides and untracked km`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        // No full fill yet: the level is unknown.
        assertNull(rig.garage.fuel(gen1).first().level.litres)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_000.0))
        var f = rig.garage.fuel(gen1).first()
        assertEquals(6.6, f.level.litres!!, 1e-9)
        assertEquals(6.6 * DEFAULT_KM_PER_L, f.level.rangeKm!!, 1e-9)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 72.0, startedAgoMs = 3_000_000)
        rig.garage.addUntrackedKm(gen1, 36.0)
        f = rig.garage.fuel(gen1).first()
        assertEquals(6.6 - 3.0, f.level.litres!!, 1e-9)
        assertEquals(108.0, f.level.sinceRefuelKm!!, 1e-9)
        // A full fill: 108 km on 3.0 L = 36 km/L, the starting value, so nothing moves.
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 3.0, odometerKm = 1_108.0))
        f = rig.garage.fuel(gen1).first()
        assertEquals(36.0, f.consumption.kmPerL, 1e-9)
        assertEquals(6.6, f.level.litres!!, 1e-9)
        assertEquals(2, f.refuels.size)
        // The pump price for the bike's fuel.
        assertEquals(12_500.0, f.pricePerL, 0.0)
    }

    /** 4.8: a bike with two measured tanks and [n] rides, each with its dash figure after it. */
    private suspend fun Rig.rides(bikeId: String, n: Int, tanks: Int = 2): Double {
        val t0 = now()
        var dashL = 1 / 40.0
        var odo = 0.0
        fun fill(i: Int, km: Double, litres: Double) = RefuelRecord(
            id = 100L + i, bikeId = bikeId, timeMs = t0 + i * 10L, odometerKm = km, litres = litres,
            pricePerL = 12_500.0, totalRp = litres * 12_500, full = true, fuelType = FuelType.PERTAMAX.key,
        )
        dao.refuels.value = (0..tanks).map { fill(it, it * 200.0, 5.0) }
        dao.dash.value = listOf(DashKmPerL(1, bikeId, t0 + 1_000, 40.0, 5.0))
        repeat(n) { i ->
            val km = 6.0 + i % 5
            val effort = 3.0 + (i * 7 % 5) * 0.8
            val slow = (i * 3 % 4) * 0.5
            val used = 0.018 + 0.0012 * effort + 0.002 * slow
            dashL += km / (km + DASH_MEMORY_KM) * (used - dashL)
            odo += km
            val end = t0 + 2_000 + i * 1_000L
            dao.trips.value += ride(bikeId, 1_000L + i, end - 500, km, effort, slow)
            dao.dash.value += DashKmPerL(2L + i, bikeId, end, Math.round(10 / dashL) / 10.0, km)
        }
        return odo
    }

    private fun ride(bikeId: String, id: Long, endMs: Long, km: Double, effort: Double, slow: Double) = TripRecord(
        id = id, startedAtMs = endMs - 100, endedAtMs = endMs, durationMs = 100, movingMs = 100, distanceM = km * 1000, maxSpeedMps = 20f,
        startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "", bikeId = bikeId,
        effortM = km * 1000, effortFlatJ = effort * 100 * km, effortSlopeJ = effort * 100 * km, effortSlopeM = 0.0, effortSlowS = slow * 60 * km,
    )

    @Test fun `riding harder than usual lowers the range once the bike's own figures have shown it helps`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 0.0)
        rig.rides(gen1, 30, tanks = 1)
        // One measured tank: the plain range.
        var f = rig.garage.fuel(gen1).first()
        assertEquals(1.0, f.load.factor, 0.0)
        assertFalse(f.load.on)
        assertEquals(1, f.learning.tanks)
        assertEquals(30, f.learning.steps)
        rig.rides(gen1, 30, tanks = 2)
        f = rig.garage.fuel(gen1).first()
        assertTrue("learning ${f.learning}", f.load.on)
        // 25 km of hard riding in heavy traffic.
        rig.dao.trips.value += ride(gen1, 5_000, rig.now() + 900_000, 25.0, 7.5, 2.5)
        f = rig.garage.fuel(gen1).first()
        assertTrue("factor ${f.load.factor}", f.load.factor < 0.95 && f.load.factor >= 0.8)
        assertEquals(f.level.litres!! * f.level.kmPerL * f.load.factor, f.level.rangeKm!!, 1e-9)
        assertTrue(f.load.note!!.startsWith("Riding hard"))
        // And easy riding raises it, by 10 % at most.
        rig.dao.trips.value += ride(gen1, 5_001, rig.now() + 950_000, 25.0, 2.5, 0.0)
        f = rig.garage.fuel(gen1).first()
        assertTrue("factor ${f.load.factor}", f.load.factor > 1.0 && f.load.factor <= 1.1)
    }

    @Test fun `the range is stepped, held and cautious`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax.copy(caution = RangeCaution.CAUTIOUS), odometerKm = 1_000.0)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_000.0))
        // 6.6 × 36 × 0.9 = 213.8: a guess, in 5 km steps.
        assertEquals(210L, rig.garage.fuel(gen1).first().level.cautiousRangeKm)
        advanceTimeBy(60_000)
        rig.garage.addDashKmPerL(gen1, 36.0, 10.0)
        assertEquals(212L, rig.garage.fuel(gen1).first().level.cautiousRangeKm)
        rig.garage.updateBike(gen1, rig.nmax)
        assertEquals(236L, rig.garage.fuel(gen1).first().level.cautiousRangeKm)
    }

    // ---- 4.8: entries corrected later ----

    @Test fun `a fill-up corrected later changes what it taught, not the odometer`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.garage.logRefuel(gen1, refuel(full = false, litres = 4.6, odometerKm = 1_000.0))
        advanceTimeBy(60_000)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_190.0, price = 13_000.0))
        assertTrue(rig.garage.fuel(gen1).first().consumption.measures.isEmpty())
        val readings = rig.dao.readings.value
        val first = rig.dao.refuels.value.first()
        rig.garage.updateRefuel(first.id, refuel(full = true, litres = 4.6, odometerKm = 1_000.0, price = 12_000.0))
        val f = rig.garage.fuel(gen1).first()
        assertEquals(38.0, f.consumption.measures.single().kmPerL, 1e-9)
        assertEquals(first.timeMs, f.refuels.last().timeMs)
        assertEquals(readings, rig.dao.readings.value)
        // The last price stays the later fill-up's.
        assertEquals(13_000.0, f.pricePerL, 0.0)
        val last = rig.dao.refuels.value.last()
        rig.garage.updateRefuel(last.id, refuel(full = true, litres = 5.0, odometerKm = 1_190.0, price = 13_500.0).copy(learn = false))
        assertEquals(13_500.0, rig.garage.fuel(gen1).first().pricePerL, 0.0)
        assertFalse(rig.garage.fuel(gen1).first().consumption.measures.single().used)
        // One that is gone by now.
        rig.garage.updateRefuel(99, refuel(full = true, litres = 1.0, odometerKm = 1.0))
        assertEquals(2, rig.dao.refuels.value.size)
    }

    @Test fun `dash figures can be corrected and deleted`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.garage.addDashKmPerL(gen1, 38.0, 10.0)
        advanceTimeBy(1_000)
        rig.garage.addDashKmPerL(gen1, 83.5, 10.0)
        val typo = rig.garage.fuel(gen1).first().dash.first()
        assertTrue(rig.garage.updateDashKmPerL(typo.id, 38.5))
        assertFalse(rig.garage.updateDashKmPerL(typo.id, 385.0))
        assertEquals(listOf(38.5, 38.0), rig.garage.fuel(gen1).first().dash.map { it.kmPerL })
        rig.garage.deleteDashKmPerL(typo.id)
        assertEquals(listOf(38.0), rig.garage.fuel(gen1).first().dash.map { it.kmPerL })
    }

    @Test fun `own fuels have a name and price, and fill-ups keep a deleted one`() = runTest {
        val rig = Rig(this)
        assertTrue(rig.garage.saveCustomFuel(null, " Shell V-Power ", 16_500.0))
        assertFalse("twice", rig.garage.saveCustomFuel(null, "shell v-power", 17_000.0))
        assertFalse("a pump fuel", rig.garage.saveCustomFuel(null, "Pertamax", 17_000.0))
        assertFalse(rig.garage.saveCustomFuel(null, "  ", 17_000.0))
        val shell = rig.dao.customFuels.value.single()
        assertEquals("Shell V-Power", shell.label)
        val gen1 = rig.garage.addBike(rig.nmax.copy(fuel = shell), odometerKm = 1_000.0)
        assertEquals(16_500.0, rig.garage.fuel(gen1).first().pricePerL, 0.0)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_000.0, price = 16_800.0).copy(fuel = shell))
        assertEquals(16_800.0, rig.garage.fuel(gen1).first().pricePerL, 0.0)
        rig.garage.deleteCustomFuel(shell.key)
        val custom = rig.dao.customFuels.value
        assertEquals("Shell V-Power", fuelOf(rig.dao.refuels.value.single().fuelType, custom).label)
        assertFalse(fuelChoices(custom).any { it.key == shell.key })
        // Still offered where it is in use.
        assertTrue(fuelChoices(custom, fuelOf(shell.key, custom)).any { it.key == shell.key })
        // Added again: the same fuel comes back.
        assertTrue(rig.garage.saveCustomFuel(null, "Shell V-Power", 17_000.0))
        assertEquals(listOf(false), rig.dao.customFuels.value.map { it.retired })
        assertEquals(shell.key, rig.dao.customFuels.value.single().key)
        // Renamed; a key nobody knows reads as the default fuel.
        assertTrue(rig.garage.saveCustomFuel(shell.key, "V-Power 95", 17_500.0))
        assertEquals("V-Power 95", rig.dao.customFuels.value.single().name)
    }

    @Test fun `a garage only backup carries the odometer as it stands`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 25.0, startedAgoMs = 3_000_000)
        advanceTimeBy(60_000)
        val anchor = rig.garage.odometerAnchors().single()
        assertEquals(1_025.0, anchor.km, 1e-9)
        assertEquals(ReadingKind.UNTRACKED, anchor.readingKind)
        assertEquals(0.0, anchor.awayKm, 0.0)
        // Another phone, without the trips.
        val other = Rig(this)
        GarageBackup.restore(other.dao, GarageBackup.collect(rig.dao).let { it.copy(readings = it.readings + anchor) })
        assertEquals(1_025.0, other.garage.estimateNow(gen1)!!, 1e-9)
        // Imported back here it changes nothing.
        GarageBackup.restore(rig.dao, GarageBackup.collect(rig.dao).let { it.copy(readings = it.readings + anchor) })
        assertEquals(1_025.0, rig.garage.estimateNow(gen1)!!, 1e-9)
    }

    @Test fun `away km make the fuel unknown until the next full fill`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        rig.garage.activate(gen2)
        rig.garage.logRefuel(gen2, refuel(full = true, litres = 5.0, odometerKm = 30_000.0))
        rig.garage.activate(gen1)
        advanceTimeBy(AWAY_AFTER_MS)
        rig.garage.activate(gen2)
        rig.garage.answerOdometerPrompt(30_200.0)
        val f = rig.garage.fuel(gen2).first()
        assertNull(f.level.litres)
        assertEquals(UnknownFuel.AWAY, f.level.unknownBecause)
        rig.garage.logRefuel(gen2, refuel(full = true, litres = 6.0, odometerKm = 30_200.0))
        assertEquals(rig.dao.bike(gen2)!!.tankLitres, rig.garage.fuel(gen2).first().level.litres!!, 1e-9)
        // The away span teaches no km/L.
        assertEquals(false, rig.garage.fuel(gen2).first().consumption.learned)
    }

    @Test fun `km recorded on another phone keep the fuel level and the tank measurable`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        rig.garage.activate(gen2)
        rig.garage.logRefuel(gen2, refuel(full = true, litres = 5.0, odometerKm = 30_000.0))
        rig.garage.activate(gen1)
        advanceTimeBy(AWAY_AFTER_MS)
        rig.garage.activate(gen2)
        rig.garage.answerOdometerPrompt(30_100.0, otherPhone = true)
        val r = rig.dao.lastReading(gen2)!!
        assertEquals(ReadingKind.UNTRACKED, r.readingKind)
        assertEquals(100.0, r.awayKm, 1e-9)
        val f = rig.garage.fuel(gen2).first()
        assertNull(f.level.unknownBecause)
        assertEquals(rig.dao.bike(gen2)!!.tankLitres - 100.0 / f.level.kmPerL, f.level.litres!!, 1e-9)
        // The next full fill measures the tank, the other phone's km included.
        rig.savedTrip(gen2, 100.0)
        advanceTimeBy(1_000)
        rig.garage.logRefuel(gen2, refuel(full = true, litres = 5.0, odometerKm = 30_200.0))
        val tank = rig.garage.fuel(gen2).first().consumption.measures.single()
        assertTrue(tank.used)
        assertEquals(40.0, tank.kmPerL, 1e-9)
    }

    @Test fun `a correction for another phone's km teaches no factor, up or down`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0)
        // 8 km more were ridden with the other phone: within the plausible band, so it would have been learned as drift.
        assertEquals(ReadingDecision.Store(ReadingKind.UNTRACKED, awayKm = 8.0), rig.garage.enterReading(gen1, 1_108.0, otherPhone = true))
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 100.0, startedAgoMs = 600_000)
        assertEquals(ReadingDecision.Store(ReadingKind.CORRECTION), rig.garage.enterReading(gen1, 1_205.0, otherPhone = true))
        assertEquals(1.0, rig.dao.bike(gen1)!!.odoFactor, 0.0)
        assertEquals(1_205.0, rig.garage.estimateNow(gen1)!!, 1e-9)
    }

    @Test fun `deleting a bike drops its fill-ups`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 1_000.0)
        rig.garage.logRefuel(gen1, refuel(full = true, litres = 5.0, odometerKm = 1_000.0))
        rig.garage.deleteBike(gen1)
        assertTrue(rig.dao.refuels.value.isEmpty())
    }

    // ---- 11c ----

    @Test fun `a service item counts from when it was added, and Mark done restarts it`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val oil = rig.garage.addService(gen1, ServiceDraft("Engine oil", ServiceKind.DISTANCE, 2_000.0, null, null, lastDoneKm = null))
        var due = rig.garage.services(gen1).first().single()
        assertEquals(2_000.0, due.dueInKm!!, 1e-9)
        advanceTimeBy(3_600_000)
        rig.savedTrip(gen1, 1_950.0, startedAgoMs = 3_000_000)
        due = rig.garage.services(gen1).first().single()
        assertEquals(DueLevel.SOON, due.level)
        assertEquals(due, rig.garage.activeCard.first()!!.reminder)

        // Done at the pump-side reading 13,960 (the estimate is 13,950): stored as a correction too.
        rig.garage.markServiceDone(oil, odometerKm = 13_960.0, costRp = 55_000.0, note = " Yamalube ")
        due = rig.garage.services(gen1).first().single()
        assertEquals(2_000.0, due.dueInKm!!, 1e-9)
        assertEquals(DueLevel.OK, due.level)
        assertEquals(ReadingKind.CORRECTION, rig.dao.lastReading(gen1)!!.readingKind)
        val record = rig.garage.serviceHistory(gen1).first().single()
        assertEquals(13_960.0, record.odometerKm!!, 0.0)
        assertEquals("Yamalube", record.note)
        assertEquals("Engine oil", record.itemName)
        assertNull(rig.garage.activeCard.first()!!.reminder)
    }

    @Test fun `a date item moves on a year when paid`() = runTest {
        val rig = Rig(this)
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = null)
        val due = rig.now() + 5 * 86_400_000L
        val tax = rig.garage.addService(gen1, ServiceDraft("Vehicle tax", ServiceKind.DATE, null, 12, due, null))
        assertEquals(DueLevel.SOON, rig.garage.services(gen1).first().single().level)
        rig.garage.markServiceDone(tax, odometerKm = null, costRp = null, note = null)
        val item = rig.dao.service(tax)!!
        assertTrue(item.dueDateMs!! > rig.now() + 360 * 86_400_000L)
        assertEquals(DueLevel.OK, rig.garage.services(gen1).first().single().level)
        // No odometer: the record has none, and nothing was stored as a reading.
        assertNull(rig.garage.serviceHistory(gen1).first().single().odometerKm)
        assertTrue(rig.dao.readings.value.isEmpty())
    }

    @Test fun `the card follows the active bike, and deleting a bike takes its services`() = runTest {
        val rig = Rig(this)
        assertNull(rig.garage.activeCard.first())
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 12_000.0)
        val gen2 = rig.garage.addBike(rig.nmax.copy(name = "Gen 2"), odometerKm = 30_000.0)
        rig.garage.addService(gen2, ServiceDraft("CVT belt", ServiceKind.DISTANCE, 20_000.0, null, null, null))
        val card = rig.garage.activeCard.first()!!
        assertEquals(gen1, card.bike.id)
        assertEquals(12_000.0, card.odometer.estimateKm!!, 0.0)
        assertNull(card.nextService)
        rig.garage.activate(gen2)
        assertEquals("CVT belt", rig.garage.activeCard.first()!!.nextService!!.item.name)
        rig.garage.deleteBike(gen2)
        assertTrue(rig.dao.services.value.isEmpty())
    }

    private companion object {
        const val WALL_BASE_MS = 1_700_000_000_000L
    }
}
