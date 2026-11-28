package com.nativeauto.garage

import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RidingLoadTest {

    /** Fixes 1 s apart going north; [speeds] per fix, so the distance between fixes is the speed. */
    private fun drive(speeds: List<Double>, altitudes: List<Double?>? = null, gapAt: Int? = null): LoadAccumulator {
        val acc = LoadAccumulator()
        var north = 0.0
        var t = 0L
        speeds.forEachIndexed { i, v ->
            if (i > 0 && i != gapAt) north += v
            if (i == gapAt) north += 500.0
            t += if (i == gapAt) 30_000_000_000L else 1_000_000_000L
            acc.add(
                SpeedSample(
                    timestampNanos = t, speedMps = v.toFloat(), speedAccuracyMps = 0.5f, horizontalAccuracyM = 4f,
                    latitude = north / METERS_PER_DEGREE_LAT, longitude = 0.0, altitudeM = altitudes?.get(i),
                ),
            )
        }
        return acc
    }

    @Test fun steadySpeedIsDragAndRollingOnly() {
        val s = drive(List(101) { 10.0 }).sums
        assertEquals(1000.0, s.distanceM, 1.0)
        assertEquals(100.0 * 1000.0, s.aero, 200.0) // v² · d
        assertEquals(0.0, s.accel, 1e-9)
        assertEquals(0, s.stops)
        // J/kg/km = 1000 · (g·Crr + k·v²) = 1000 · (0.14715 + 0.0018·100)
        assertEquals(327.15, s.energyPerKm!!, 1.0)
    }

    @Test fun stopAndGoCostsMoreThanSteadyAtTheSameAverage() {
        val steady = drive(List(201) { 8.0 }).sums
        val cycle = List(3) { 0.0 } + List(10) { it * 1.6 } + List(10) { 16.0 - it * 1.6 } // a 3 s stop, then go
        val stopGo = drive(List(10) { cycle }.flatten() + 0.0).sums
        assertTrue("stops counted: ${stopGo.stops}", stopGo.stops >= 5)
        assertTrue(stopGo.accel > 0)
        assertTrue(stopGo.energyPerKm!! > steady.energyPerKm!!)
    }

    /** 4.7: crawling is under 15 km/h and not stopped; standing and riding don't count. */
    @Test fun crawlingIsTimedAndMeasured() {
        assertEquals(0.0, drive(List(101) { 10.0 }).sums.crawlS, 0.0)
        assertEquals(0.0, drive(List(101) { 0.0 }).sums.crawlS, 0.0)
        val s = drive(List(101) { 3.0 }).sums
        assertEquals(100.0, s.crawlS, 0.01)
        assertEquals(300.0, s.crawlM, 1.0)
        // A gap (a pause) isn't crawling.
        assertEquals(19.0, drive(List(21) { 3.0 }, gapAt = 10).sums.crawlS, 0.01)
        // Uphill at 3 m/s counts as climbed while crawling; at 10 m/s it doesn't.
        val up = List(120) { 100.0 + it * 0.3 }
        assertTrue(drive(List(120) { 3.0 }, up).sums.let { it.crawlClimbM > 20 && it.crawlClimbM == it.climbM })
        assertEquals(0.0, drive(List(120) { 10.0 }, up).sums.crawlClimbM, 0.0)
        // Two stretches add up; the energy figure is what it was.
        assertEquals(200.0, (s + s).crawlS, 0.02)
        assertEquals(s.copy(crawlS = 0.0, crawlM = 0.0).energyPerKm, s.energyPerKm)
    }

    @Test fun speedJitterIsSmoothedNotCountedAsAcceleration() {
        val jitter = drive(List(201) { if (it % 2 == 0) 10.3 else 9.7 }).sums
        // Raw positive Δv·v would be ~0.6 · 10 · 100 = 600; smoothed, the swings are halved.
        assertTrue("accel ${jitter.accel}", jitter.accel < 350)
    }

    @Test fun aGapAddsNoDistanceAndRestartsTheSmoothing() {
        val s = drive(List(21) { 10.0 }, gapAt = 10).sums
        // 20 steps of 10 m, minus the one across the gap (its 500 m jump isn't counted either).
        assertEquals(190.0, s.distanceM, 1.0)
        assertEquals(0.0, s.accel, 1e-9)
    }

    @Test fun climbIsSmoothedWithADeadBandAndDescentsDontCount() {
        // 60 s flat, 60 s up 0.5 m/s (about 30 m), 60 s down again.
        val alt = List(60) { 100.0 } + List(60) { 100.0 + it * 0.5 } + List(60) { 130.0 - it * 0.5 }
        val s = drive(List(180) { 10.0 }, alt).sums
        assertEquals(30.0, s.climbM, 4.0)
        // ±1.5 m noise around a flat road stays inside the dead band once averaged.
        val noisy = drive(List(120) { 10.0 }, List(120) { 100.0 + if (it % 2 == 0) 1.5 else -1.5 }).sums
        assertEquals(0.0, noisy.climbM, 1e-9)
        // No altitude: no climb.
        assertEquals(0.0, drive(List(50) { 10.0 }).sums.climbM, 0.0)
    }

    @Test fun shortStretchesHaveNoLoad() {
        assertNull(LoadSums(distanceM = 400.0, aero = 1.0).energyPerKm)
        assertNotNull(LoadSums(distanceM = 600.0).energyPerKm)
    }
}
