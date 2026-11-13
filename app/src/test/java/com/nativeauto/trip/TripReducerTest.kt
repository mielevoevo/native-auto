package com.nativeauto.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReducerTest {

    private val sec = 1_000_000_000L

    /** A fix at [t] seconds; position defaults to a fixed point so distance is speed-driven. */
    private fun fix(
        t: Double,
        speed: Float,
        lat: Double = 51.0,
        lon: Double = 0.0,
        hAcc: Float = 5f,
        speedAcc: Float? = 0.3f,
    ) = SpeedSample((t * sec).toLong(), speed, speedAcc, hAcc, lat, lon)

    private fun fold(vararg fixes: SpeedSample, from: TripStats = TripStats()) =
        fixes.fold(from, TripReducer::reduce)

    // ---- required cases (Phase 3 §2) ----

    @Test fun `stationary jitter reads as zero and adds no distance or moving time`() {
        val s = fold(fix(0.0, 0.5f), fix(1.0, 0.7f), fix(2.0, 0.3f), fix(3.0, 0.79f))
        assertEquals(0f, s.currentSpeedMps, 0f)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.movingNanos)
        assertEquals(0f, s.maxSpeedMps, 0f)
    }

    @Test fun `speed at the stationary threshold counts as moving`() {
        val s = fold(fix(0.0, 0.8f), fix(1.0, 0.8f))
        assertEquals(0.8f, s.currentSpeedMps, 0f)
        assertEquals(sec, s.movingNanos)
    }

    @Test fun `a speed spike does not change max`() {
        val s = fold(fix(0.0, 20f), fix(1.0, 20f), fix(2.0, 50f), fix(3.0, 20f))
        // 20 → 50 m/s in 1 s is 30 m/s², far over MAX_ACCEL_MPS2.
        assertEquals(20f, s.maxSpeedMps, 0f)
    }

    // ---- option B: implausible fixes are ignored completely ----

    @Test fun `a speed spike never reaches the current speed`() {
        val before = fold(fix(0.0, 20f), fix(1.0, 20f))
        val after = TripReducer.reduce(before, fix(2.0, 50f))
        assertSame("the spike is dropped outright", before, after)
        assertEquals(20f, after.currentSpeedMps, 0f)
    }

    @Test fun `a speed spike adds no distance and the next good fix catches up`() {
        // Truth: 20 m/s for 3 s = 60 m. A spike folded into the trapezoids would give 90 m.
        val s = fold(fix(0.0, 20f), fix(1.0, 20f), fix(2.0, 50f), fix(3.0, 20f))
        assertEquals(60.0, s.distanceM, 1e-9)
        assertEquals(3 * sec, s.movingNanos)
        assertEquals(3 * sec, s.lastSample!!.timestampNanos)
    }

    @Test fun `two spikes in a row cannot set a false max`() {
        // The handoff's reference compared 56 with the rejected 55, called it plausible, and
        // set max to 56. Judged against the last good 27.78 over 2 s it is still 14 m/s².
        val s = fold(fix(0.0, 27.78f), fix(1.0, 27.78f), fix(2.0, 55f), fix(3.0, 56f), fix(4.0, 27.78f))
        assertEquals(27.78f, s.maxSpeedMps, 0f)
        assertEquals(27.78f, s.currentSpeedMps, 0f)
        assertEquals(27.78 * 4, s.distanceM, 1e-3)
    }

    @Test fun `hard but real braking is never rejected`() {
        // 9 m/s² (~0.9 g, an emergency stop on good tyres) from 100 km/h.
        val speeds = listOf(27.78f, 18.78f, 9.78f, 0.78f)
        val s = fold(*speeds.mapIndexed { i, v -> fix(i.toDouble(), v) }.toTypedArray())
        assertEquals(0f, s.currentSpeedMps, 0f)  // 0.78 is under the stationary threshold
        assertEquals(3 * sec, s.lastSample!!.timestampNanos)
    }

    @Test fun `the filter cannot lock onto a bad value - a real change lands within 3 s`() {
        // Pretend the last accepted value was wrong (or the car really did stop instantly).
        // 27.78 → 0 needs 27.78 / 12 ≈ 2.3 s before it counts as plausible.
        val start = fold(fix(0.0, 27.78f), fix(1.0, 27.78f))
        val t2 = TripReducer.reduce(start, fix(2.0, 0f))
        val t3 = TripReducer.reduce(t2, fix(3.0, 0f))
        val t4 = TripReducer.reduce(t3, fix(4.0, 0f))
        assertEquals(27.78f, t2.currentSpeedMps, 0f)  // rejected at Δt = 1 s
        assertEquals(27.78f, t3.currentSpeedMps, 0f)  // rejected at Δt = 2 s
        assertEquals(0f, t4.currentSpeedMps, 0f)      // accepted at Δt = 3 s
        assertEquals(4 * sec, t4.lastSample!!.timestampNanos)
    }

    @Test fun `rejected fixes leave the last good fix in place so a long run goes stale`() {
        // Spikes for 4 s: lastSample stays at t = 1 s, so the UI's >3 s stale rule shows "--".
        val good = fold(fix(0.0, 20f), fix(1.0, 20f))
        val s = fold(fix(2.0, 60f), fix(3.0, 60f), fix(4.0, 62f), from = good)
        assertSame(good.lastSample, s.lastSample)
        assertEquals(20f, s.currentSpeedMps, 0f)
    }

    @Test fun `a plausible acceleration does raise max`() {
        val s = fold(fix(0.0, 20f), fix(1.0, 20f), fix(2.0, 31f))
        assertEquals(31f, s.maxSpeedMps, 0f)
    }

    @Test fun `a fix with poor speed accuracy does not raise max`() {
        val s = fold(fix(0.0, 20f), fix(1.0, 20f), fix(2.0, 25f, speedAcc = 2f))
        assertEquals(20f, s.maxSpeedMps, 0f)
        assertEquals(25f, s.currentSpeedMps, 0f)
    }

    @Test fun `a fix with no speed accuracy is trusted for max`() {
        val s = fold(fix(0.0, 20f), fix(1.0, 22f, speedAcc = null))
        assertEquals(22f, s.maxSpeedMps, 0f)
    }

    @Test fun `poor horizontal accuracy is rejected`() {
        val before = fold(fix(0.0, 10f), fix(1.0, 10f))
        val after = TripReducer.reduce(before, fix(2.0, 30f, hAcc = 40f))
        assertEquals(GpsStatus.Searching, after.gpsStatus)
        assertEquals(before.copy(gpsStatus = GpsStatus.Searching), after)
        assertSame(before.lastSample, after.lastSample)
    }

    @Test fun `a position-only fix (no speed) never touches the trip`() {
        val before = fold(fix(0.0, 10f), fix(1.0, 10f))
        val after = TripReducer.reduce(before, fix(2.0, 0f, hAcc = 5f).copy(hasSpeed = false))
        assertEquals(before.copy(gpsStatus = GpsStatus.Searching), after)
        assertSame(before.lastSample, after.lastSample)
    }

    @Test fun `a trip cannot start on a position-only fix`() {
        val after = fold(fix(0.0, 0f).copy(hasSpeed = false))
        assertEquals(null, after.lastSample)
        assertEquals(null, after.startedAtNanos)
    }

    @Test fun `accuracy exactly at the limit is accepted`() {
        val s = fold(fix(0.0, 10f), fix(1.0, 10f, hAcc = TripReducer.MAX_ACCURACY_M))
        assertEquals(GpsStatus.Fix, s.gpsStatus)
        assertEquals(10.0, s.distanceM, 1e-9)
    }

    @Test fun `distance uses the trapezoid rule`() {
        // 10 → 20 m/s over 2 s: (10 + 20) / 2 × 2 = 30 m.
        val s = fold(fix(0.0, 10f), fix(2.0, 20f))
        assertEquals(30.0, s.distanceM, 1e-9)
    }

    @Test fun `a gap over 5 s uses haversine distance`() {
        val a = fix(0.0, 20f, lat = 51.0)
        val b = fix(10.0, 20f, lat = 51.001)  // ~111 m north
        val s = fold(a, b)
        val expected = haversineMeters(a, b)
        assertEquals(expected, s.distanceM, 1e-9)
        assertEquals(111.2, expected, 0.1)
        assertNotEquals("not the trapezoid (200 m)", 200.0, s.distanceM, 1.0)
    }

    @Test fun `a gap of exactly 5 s still uses the trapezoid`() {
        val s = fold(fix(0.0, 20f, lat = 51.0), fix(5.0, 20f, lat = 51.5))
        assertEquals(100.0, s.distanceM, 1e-9)
    }

    @Test fun `duplicate fixes are ignored`() {
        val before = fold(fix(0.0, 10f), fix(1.0, 12f))
        assertSame(before, TripReducer.reduce(before, fix(1.0, 30f)))
    }

    @Test fun `out-of-order fixes are ignored`() {
        val before = fold(fix(0.0, 10f), fix(2.0, 12f))
        assertSame(before, TripReducer.reduce(before, fix(1.0, 30f)))
    }

    /**
     * 10 s cruising at 20 m/s sampled every second, a 2 s stop at 10 m/s² (an instant stop
     * would now be rejected as a spike), then parked with one sparse fix (GPS often slows down
     * when still). Same position while parked, so the >5 s gap adds 0 m.
     */
    private fun cruiseThenPark() =
        (0..10).map { fix(it.toDouble(), 20f) } + fix(11.0, 10f) + fix(12.0, 0f) + fix(60.0, 0f)

    @Test fun `average speed is distance over elapsed time, not a mean of samples`() {
        val fixes = cruiseThenPark()
        val s = fold(*fixes.toTypedArray())
        val now = 60 * sec
        // 200 m cruising + braking trapezoids 15 m (20 → 10) and 5 m (10 → 0).
        assertEquals(220.0, s.distanceM, 1e-9)
        assertEquals((220.0 / 60.0).toFloat(), s.averageSpeedMps(now), 1e-5f)
        val meanOfSamples = fixes.map { it.speedMps }.average().toFloat()  // 230 / 14 ≈ 16.4
        assertTrue("mean of samples ($meanOfSamples) is badly biased", meanOfSamples > 15f)
    }

    @Test fun `average keeps falling while parked because elapsed time grows`() {
        val s = fold(fix(0.0, 10f), fix(5.0, 10f))  // trapezoid → 50 m
        assertEquals(10f, s.averageSpeedMps(5 * sec), 1e-6f)
        assertEquals(5f, s.averageSpeedMps(10 * sec), 1e-6f)
    }

    @Test fun `moving average excludes stopped time`() {
        val s = fold(*cruiseThenPark().toTypedArray())
        assertEquals(11 * sec, s.movingNanos)  // only the 11 steps that ended above zero
        assertEquals(20f, s.movingAverageMps(), 1e-5f)          // 220 m / 11 s
        assertTrue(s.movingAverageMps() > s.averageSpeedMps(60 * sec))
    }

    // ---- start-of-trip behaviour ----

    @Test fun `first fix sets start, speed and Fix status but no distance`() {
        val s = TripReducer.reduce(TripStats(), fix(5.0, 15f))
        assertEquals(5 * sec, s.startedAtNanos)
        assertEquals(15f, s.currentSpeedMps, 0f)
        assertEquals(GpsStatus.Fix, s.gpsStatus)
        assertEquals(0.0, s.distanceM, 0.0)
    }

    @Test fun `an existing start time is kept on the first fix`() {
        val s = TripReducer.reduce(TripStats(startedAtNanos = 1L), fix(5.0, 15f))
        assertEquals(1L, s.startedAtNanos)
    }

    @Test fun `averages are zero before one second has passed`() {
        val s = fold(fix(0.0, 10f), fix(0.5, 10f))
        assertEquals(0f, s.averageSpeedMps((0.5 * sec).toLong()), 0f)
        assertEquals(0f, s.movingAverageMps(), 0f)
    }

    // ---- 13b: the max no longer lags the hero by a fix ----

    @Test fun `the first fix sets the max too`() {
        val s = TripReducer.reduce(TripStats(), fix(5.0, 20f))
        assertEquals(20f, s.maxSpeedMps, 0f)
    }

    @Test fun `a first fix with a poor speed accuracy leaves the max alone`() {
        val s = TripReducer.reduce(TripStats(), fix(5.0, 20f, speedAcc = 3f))
        assertEquals(0f, s.maxSpeedMps, 0f)
        assertEquals(20f, s.currentSpeedMps, 0f)
    }

    @Test fun `a resume keeps the earlier max and takes a faster first fix`() {
        val resumed = TripStats(startedAtNanos = 1L, maxSpeedMps = 15f, lastSample = null)
        assertEquals(15f, TripReducer.reduce(resumed, fix(9.0, 10f)).maxSpeedMps, 0f)
        assertEquals(25f, TripReducer.reduce(resumed, fix(9.0, 25f)).maxSpeedMps, 0f)
    }
}
