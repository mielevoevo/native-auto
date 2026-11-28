package com.nativeauto.garage

import com.nativeauto.history.TrackPointRecord
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.8: the effort of a ride, when the range looks again, and the model with its gate. */
class EffortTest {

    private val sec = 1_000_000_000L

    /** Fixes 1 s apart going north; [speeds] per fix. */
    private fun drive(speeds: List<Double>, altitudes: List<Double?>? = null, gapAt: Int? = null): EffortSums {
        val acc = EffortAccumulator()
        var north = 0.0
        var t = 0L
        speeds.forEachIndexed { i, v ->
            if (i > 0 && i != gapAt) north += v
            t += if (i == gapAt) 600 * sec else sec
            acc.add(t, north / METERS_PER_DEGREE_LAT, 0.0, v, altitudes?.get(i))
        }
        return acc.sums
    }

    private val flatPerM = 9.81 * 0.015 + 0.0018 * 100

    @Test fun `steady riding is drag and rolling`() {
        val s = drive(List(201) { 10.0 })
        assertEquals(2_000.0, s.distanceM, 2.0)
        assertEquals(2_000 * flatPerM, s.flatJ, 3.0)
        assertEquals("no height: no slope", s.flatJ, s.slopeJ, 1e-6)
        assertEquals(0.0, s.slopeM, 0.0)
        assertEquals(0.0, s.slowS, 0.0)
        assertEquals(flatPerM * 10, s.effort(false), 0.01)
    }

    @Test fun `up costs and down gives back, never below nothing`() {
        val up = drive(List(401) { 10.0 }, List(401) { 100 + it * 0.4 })
        val down = drive(List(401) { 10.0 }, List(401) { 300 - it * 0.4 })
        val gentle = drive(List(401) { 10.0 }, List(401) { 300 - it * 0.1 })
        assertTrue("slope known: ${up.slopeM}", up.sloped)
        assertEquals(up.slopeM * 9.81 * 0.04, up.slopeJ - up.flatJ, 30.0)
        assertTrue("4 % down pays for itself: ${down.slopeJ}", down.slopeJ < 0.08 * down.flatJ)
        assertEquals(gentle.flatJ - gentle.slopeM * 9.81 * 0.01, gentle.slopeJ, 30.0)
        assertEquals("the flat figure doesn't look at it", up.flatJ, down.flatJ, 1e-6)
    }

    @Test fun `a height held for 90 s and then corrected is the same climb`() {
        val honest = List(401) { 100 + it * 0.4 }
        val held = honest.mapIndexed { i, a -> if (i in 100..190) honest[100] else a }
        val a = drive(List(401) { 10.0 }, honest)
        val b = drive(List(401) { 10.0 }, held)
        assertEquals(a.slopeJ, b.slopeJ, 0.03 * a.slopeJ)
    }

    @Test fun `a jump is no climb`() {
        // 47 m up within 60 m of road: what no road does is cut to 15 %.
        val jump = List(401) { if (it < 200) 100.0 else 147.0 }.toMutableList().also { it[194] = 100.1 }
        val s = drive(List(401) { 10.0 }, jump.map { it })
        assertTrue("${s.slopeJ - s.flatJ}", s.slopeJ - s.flatJ < 9.81 * 47 * 0.35)
    }

    @Test fun `stop and go costs more than steady, standing costs time only`() {
        val steady = drive(List(201) { 8.0 })
        val cycle = List(3) { 0.0 } + List(10) { it * 1.6 } + List(10) { 16.0 - it * 1.6 }
        val stopGo = drive(List(10) { cycle }.flatten() + 0.0)
        assertTrue(stopGo.effort(false) > 1.3 * steady.effort(false))
        val standing = drive(List(121) { 0.0 })
        assertEquals(120.0, standing.slowS, 0.01)
        assertEquals(0.0, standing.flatJ, 1e-9)
    }

    @Test fun `a pause costs nothing`() {
        val s = drive(List(41) { 0.0 }, gapAt = 20)
        assertEquals(39.0, s.slowS, 0.01)
        val riding = drive(List(201) { 10.0 }, List(201) { 100.0 + it * 0.4 }, gapAt = 100)
        assertEquals(1_990.0, riding.distanceM, 2.0)
        assertEquals(riding.distanceM * flatPerM, riding.flatJ, 8.0)
    }

    @Test fun `a saved track says the same as the ride did`() {
        val track = List(201) {
            TrackPointRecord(tripId = 1, timeMs = 1_762_650_000_000L + it * 1_000, latitude = it * 10.0 / METERS_PER_DEGREE_LAT, longitude = 0.0, speedMps = 10f, bearingDeg = null, accuracyM = 4f)
        }
        assertEquals(drive(List(201) { 10.0 }).flatJ, effortOf(track).flatJ, 1.0)
        assertEquals(0.0, effortOf(emptyList()).distanceM, 0.0)
    }

    // ---- when the range looks again ----

    private fun sums(km: Double, effort: Double = 4.0, slow: Double = 0.5) =
        EffortSums(km * 1000, effort * 100 * km, effort * 100 * km, 0.0, slow * 60 * km)

    @Test fun `once a minute, sooner only for a lasting big change`() {
        val trail = EffortTrail()
        var total = EffortSums()
        var t = 0L
        var published = 0
        /** [n] windows of 5 s at 40 km/h. */
        fun ride(n: Int, effort: Double, slow: Double = 0.5) = repeat(n) {
            t += 5 * sec
            total += sums(0.0556, effort, slow)
            if (trail.due(total, t)) { trail.onPublished(total, t); published++ }
        }
        ride(1, 4.0)
        assertEquals("the first fixes", 1, published)
        ride(12 * 5, 4.0)
        assertEquals(6, published)
        ride(12 * 5, 4.4)
        assertEquals("a little harder changes nothing", 11, published)
        // From 4.4 to a crawl with 12 min/km: the last km differs a lot.
        val before = published
        ride(12 * 4, 12.0, 12.0)
        assertTrue("sooner than once a minute: ${published - before}", published - before > 4)
        assertTrue("but not all the time: ${published - before}", published - before <= 8)
    }

    @Test fun `the last kilometres are told apart from the rest`() {
        val trail = EffortTrail()
        var total = EffortSums()
        repeat(30) { total += sums(1.0, 4.0); trail.live(total) }
        repeat(8) { total += sums(1.0, 8.0); trail.live(total) }
        val live = trail.live(total)
        assertEquals(38.0, live.total.km, 1e-6)
        assertEquals(8.0, live.last8.effort(false), 1e-6)
        assertEquals(8.0, live.last8.km, 1.01)
        assertTrue(live.last20.effort(false) in 5.0..6.5)
    }

    // ---- the model ----

    private class Data(val dash: List<DashKmPerL>, val rides: List<RideEffort>)

    /** [n] rides with a dash figure after each; what each used follows effort and slow time if [follows]. */
    private fun data(n: Int, follows: Boolean = true, seed: Int = 1, slopeM: Double = 0.0): Data {
        val r = Random(seed)
        var f = 1 / 40.0
        val dash = arrayListOf(DashKmPerL(1, "b", 0, 40.0, 5.0))
        val rides = ArrayList<RideEffort>()
        repeat(n) { i ->
            val km = 4 + 8 * r.nextDouble()
            val effort = 2.5 + 4 * r.nextDouble()
            val slow = 2 * r.nextDouble()
            val used = if (follows) 0.018 + 0.0012 * effort + 0.002 * slow else 0.02 + 0.01 * r.nextDouble()
            f += km / (km + DASH_MEMORY_KM) * (used - f)
            val s = sums(km, effort, slow)
            rides += RideEffort(i * 1_000L + 900, s.distanceM, s.flatJ, s.slopeJ, slopeM * s.distanceM, s.slowS)
            dash += DashKmPerL(i + 2L, "b", i * 1_000L + 950, Math.round(10 / f) / 10.0, km)
        }
        return Data(dash, rides)
    }

    private fun tanks(n: Int) = List(n) { TankMeasure(it + 1L, 1_000_000L * (it + 1), 200.0, 5.0, true, 1_000_000L * it) }

    private fun learn(d: Data, tanks: Int = 2) = learnRangeModel(d.dash, d.rides, emptyList(), tanks(tanks))

    @Test fun `the model finds what the bike does and turns on`() {
        val m = learn(data(50)).model
        assertNotNull(m)
        assertTrue("per effort ${m!!.perEffort}", m.perEffort in 0.0006..0.0018)
        assertTrue("per slow ${m.perSlow}", m.perSlow in 0.001..0.003)
        assertFalse(m.withSlope)
        assertEquals(50, m.steps)
    }

    @Test fun `it stays off without two tanks, with few figures, and when riding doesn't explain the figures`() {
        assertNull(learn(data(50), tanks = 1).model)
        assertEquals(RangeLearning(null, 50, 1), learn(data(50), tanks = 1))
        assertNull(learn(data(MIN_MODEL_STEPS - 1)).model)
        // Over many bikes whose figures don't follow the riding it hardly ever turns on.
        val on = (1..40).count { learn(data(40, follows = false, seed = it)).model != null }
        assertTrue("$on of 40", on <= 3)
        assertNull(learnRangeModel(emptyList(), emptyList(), emptyList(), tanks(5)).model)
    }

    @Test fun `it turns itself off when it stops being better`() {
        val good = data(40)
        val odd = data(60, follows = false, seed = 7)
        val shift = 100_000L
        val mixed = Data(
            good.dash + odd.dash.map { it.copy(id = it.id + 1_000, timeMs = it.timeMs + shift) },
            good.rides + odd.rides.map { it.copy(endedAtMs = it.endedAtMs + shift) },
        )
        assertNotNull(learn(good).model)
        assertNull(learn(mixed).model)
    }

    @Test fun `slope joins after four tanks that knew it`() {
        fun withTanks(n: Int): RangeLearning {
            val d = data(60, slopeM = 1.0)
            // All rides within the first tanks: each tank holds 20 of them.
            val tanks = List(n) { TankMeasure(it + 1L, 1_000L * 15 * (it + 1), 100.0, 2.5, true, 1_000L * 15 * it) }
            return learnRangeModel(d.dash, d.rides, emptyList(), tanks)
        }
        assertFalse(withTanks(3).model!!.withSlope)
        assertTrue(withTanks(4).model!!.withSlope)
        // Without heights it stays with what it knows.
        val bare = data(60)
        assertFalse(learnRangeModel(bare.dash, bare.rides, emptyList(), List(6) { TankMeasure(it + 1L, 10_000L * (it + 1), 100.0, 2.5, true, 10_000L * it) }).model!!.withSlope)
    }

    @Test fun `steps with unseen km or hardly any riding are left out`() {
        val d = data(30)
        val all = dashSteps(d.dash, d.rides, emptyList())
        assertEquals(30, all.size)
        assertEquals(29, dashSteps(d.dash, d.rides, listOf(d.dash[5].timeMs + 1)).size)
        // Two figures for one ride: the second step has no riding in it.
        val twice = d.dash + DashKmPerL(900, "b", d.dash[10].timeMs + 1, 39.0, 5.0)
        assertEquals(30, dashSteps(twice, d.rides, emptyList()).size)
        // A step over several rides adds them up.
        val sparse = d.dash.filterIndexed { i, _ -> i % 3 == 0 }
        val steps = dashSteps(sparse, d.rides, emptyList())
        assertEquals(10, steps.size)
        assertEquals(d.rides.take(3).sumOf { it.distanceM }, steps.first().ride.distanceM, 1e-6)
    }

    private val model = RangeModel(0.018, 0.0012, 0.002, sums(300.0, 4.5, 1.0), false, 40)

    private fun ride(end: Long, km: Double, effort: Double, slow: Double = 1.0): RideEffort {
        val s = sums(km, effort, slow)
        return RideEffort(end, s.distanceM, s.flatJ, s.slopeJ, 0.0, s.slowS)
    }

    @Test fun `the factor is one without a model or riding, drops sooner than it rises, and is limited`() {
        assertEquals(RangeLoad(), rangeLoad(null, listOf(ride(1, 30.0, 9.0)), null))
        assertEquals(RangeLoad(1.0, true), rangeLoad(model, emptyList(), null))
        assertEquals(1.0, rangeLoad(model, listOf(ride(1, 1.5, 9.0)), null).factor, 0.0)
        assertEquals(1.0, rangeLoad(model, listOf(ride(1, 30.0, 4.5)), null).factor, 1e-9)
        // The last 8 km hard after an easy stretch: down with the 8 km.
        val hardNow = rangeLoad(model, listOf(ride(2, 8.0, 7.0), ride(1, 30.0, 3.5)), null).factor
        assertTrue("$hardNow", hardNow < 0.93)
        // The last 8 km easy after hard ones: the 20 km still hold it down.
        val easyNow = rangeLoad(model, listOf(ride(2, 8.0, 3.0), ride(1, 30.0, 7.5)), null).factor
        assertTrue("$easyNow", easyNow < 1.0)
        assertEquals(0.8, rangeLoad(model, listOf(ride(1, 30.0, 20.0, 10.0)), null).factor, 0.0)
        assertEquals(1.1, rangeLoad(model, listOf(ride(1, 30.0, 0.5, 0.0)), null).factor, 0.0)
        // The trip in progress comes first.
        val live = LiveEffort(sums(25.0, 7.0, 1.0), sums(8.0, 7.0, 1.0), sums(20.0, 7.0, 1.0))
        assertTrue(abs(rangeLoad(model, listOf(ride(1, 30.0, 3.0)), live).factor - hardNow) < 0.01)
    }

    @Test fun `the note shows only beyond five percent`() {
        assertNull(RangeLoad(0.96).note)
        assertEquals("Riding hard: −12%", RangeLoad(0.88).note)
        assertEquals("Riding easy: +8%", RangeLoad(1.08).note)
    }
}
