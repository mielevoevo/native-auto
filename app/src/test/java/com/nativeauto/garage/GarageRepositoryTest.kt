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

    @Test fun `riding harder than usual lowers the range once four spans have taught it`() = runTest {
        val rig = Rig(this)
        rig.garage.rangeFollowsRiding = true // off in the app until 4.7
        val gen1 = rig.garage.addBike(rig.nmax, odometerKm = 0.0)
        val t0 = rig.now()
        val day = 86_400_000L
        // Five full fills 200 km apart; between each pair a 200 km trip ridden at a different load.
        // km/L follows the load: 48 - 0.015 * (J/kg/km).
        val v2s = listOf(100.0, 200.0, 300.0, 400.0)
        fun energy(v2: Double) = LoadSums(1000.0, v2 * 1000).energyPerKm!!
        fun loadTrip(id: Long, end: Long, km: Double, v2: Double) = TripRecord(
            id = id, startedAtMs = end - 3_600_000, endedAtMs = end, durationMs = 3_600_000, movingMs = 3_600_000,
            distanceM = km * 1000, maxSpeedMps = 20f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "",
            bikeId = gen1, loadIndex = energy(v2), loadDistanceM = km * 1000, loadAero = v2 * km * 1000, loadAccel = 0.0,
            loadClimbM = 0.0, loadStops = 0,
        )
        fun fill(i: Int, litres: Double) = RefuelRecord(
            id = i + 1L, bikeId = gen1, timeMs = t0 + i * day, odometerKm = i * 200.0, litres = litres,
            pricePerL = 12_500.0, totalRp = litres * 12_500, full = true, fuelType = FuelType.PERTAMAX.name,
        )
        rig.dao.refuels.value = listOf(fill(0, 5.0))
        v2s.forEachIndexed { i, v2 ->
            rig.dao.trips.value += loadTrip(i + 1L, t0 + i * day + day / 2, 200.0, v2)
            if (i < 3) rig.dao.refuels.value += fill(i + 1, 200.0 / (48 - 0.015 * energy(v2)))
        }
        // Three spans: nothing learned yet, the range is the plain one.
        var f = rig.garage.fuel(gen1).first()
        assertEquals(1.0, f.load.factor, 0.0)
        assertNull(f.load.note)
        rig.dao.refuels.value += fill(4, 200.0 / (48 - 0.015 * energy(400.0)))
        // The last 20 km ridden hard (like the hardest span): the range drops below the plain figure.
        rig.dao.trips.value += loadTrip(9, t0 + 4 * day + 1_000, 20.0, 400.0)
        f = rig.garage.fuel(gen1).first()
        assertTrue("factor ${f.load.factor}", f.load.factor < 0.95)
        assertEquals(f.level.litres!! * f.level.kmPerL * f.load.factor, f.level.rangeKm!!, 1e-9)
        assertTrue(f.load.note!!.startsWith("Riding hard"))
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
