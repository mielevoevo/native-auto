package com.nativeauto.garage

import com.nativeauto.history.TrackPointRecord
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.haversineMeters
import kotlin.math.abs

/**
 * 4.8: what a ride asked of the engine, from the fixes the trip gets anyway (no new sensor, no extra GPS request).
 *
 * Over short windows (~5 s) the demand per kg is rolling + air drag + the change in height + the change in kinetic energy,
 * all signed and never below zero: going down or slowing takes nothing, and what a descent gives back is credited, but
 * only against the same seconds. The time spent standing or crawling is kept beside it (the engine runs). What both cost
 * in fuel is learned per bike ([learnRangeModel]); nothing here assumes a figure in litres.
 */
data class EffortSums(
    val distanceM: Double = 0.0,
    /** Demand without the slope, J per kg. */
    val flatJ: Double = 0.0,
    /** The same with the slope wherever the height was known. */
    val slopeJ: Double = 0.0,
    /** Metres whose slope was known. */
    val slopeM: Double = 0.0,
    /** Seconds standing or under [CRAWL_MPS] on the running trip. */
    val slowS: Double = 0.0,
) {
    operator fun plus(o: EffortSums) =
        EffortSums(distanceM + o.distanceM, flatJ + o.flatJ, slopeJ + o.slopeJ, slopeM + o.slopeM, slowS + o.slowS)

    operator fun minus(o: EffortSums) =
        EffortSums(distanceM - o.distanceM, flatJ - o.flatJ, slopeJ - o.slopeJ, slopeM - o.slopeM, slowS - o.slowS)

    val km: Double get() = distanceM / 1000.0

    /** Hundreds of J per kg per km. */
    fun effort(withSlope: Boolean): Double = if (distanceM <= 0) 0.0 else (if (withSlope) slopeJ else flatJ) / km / 100.0

    /** Minutes per km standing or crawling. */
    val slow: Double get() = if (distanceM <= 0) 0.0 else slowS / 60.0 / km

    /** Most of it has a slope. */
    val sloped: Boolean get() = distanceM > 0 && slopeM >= SLOPE_COVERAGE * distanceM
}

/** One saved trip's effort. */
data class RideEffort(val endedAtMs: Long, val distanceM: Double, val flatJ: Double, val slopeJ: Double, val slopeM: Double, val slowS: Double) {
    val sums: EffortSums get() = EffortSums(distanceM, flatJ, slopeJ, slopeM, slowS)
}

const val SLOPE_COVERAGE = 0.8

private const val WINDOW_NANOS = 5_000_000_000L

/**
 * The height is read once per this many metres, and only when it has changed: the rider's phone repeats the old value for
 * up to 90 s after a stop, then jumps. Its change is spread over all the road since the last new value, so the totals are
 * right and the jump is nobody's climb.
 */
private const val ALTITUDE_STEP_M = 50.0

/** No road is steeper; what is left of a jump is not a climb. */
private const val MAX_SLOPE = 0.15

/** Windows that may wait for a height; more than this and they count without one. */
private const val PENDING = 64

/**
 * The running sums over one trip's accepted fixes. Constant work per fix, no allocation (the waiting windows are fixed
 * arrays). Not thread-safe: fed from one collector.
 */
class EffortAccumulator {
    private var distanceM = 0.0
    private var flatJ = 0.0
    private var slopeJ = 0.0
    private var slopeM = 0.0
    private var slowS = 0.0

    private var lastNanos = Long.MIN_VALUE
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var smoothV = 0.0

    // The open window.
    private var openNanos = 0L
    private var openV = 0.0
    private var openM = 0.0
    private var openJ = 0.0
    /** Of [openM]: what already has a slope, and that slope's work. */
    private var openSlopedM = 0.0
    private var openSlopeJ = 0.0

    // Closed windows waiting for a height (all their metres, or the last part of them).
    private val waitM = DoubleArray(PENDING)
    private val waitJ = DoubleArray(PENDING)
    private val waitSlopedM = DoubleArray(PENDING)
    private val waitSlopeJ = DoubleArray(PENDING)
    private var waiting = 0

    private var altitude = Double.NaN
    private var sinceAltitudeM = 0.0

    /** True when this fix closed a window (about every 5 s): the moment to look at the sums. */
    fun add(s: SpeedSample): Boolean = add(s.timestampNanos, s.latitude, s.longitude, s.speedMps.toDouble(), s.altitudeM)

    fun add(nanos: Long, lat: Double, lon: Double, v: Double, altitudeM: Double?): Boolean {
        if (lastNanos == Long.MIN_VALUE || nanos - lastNanos > LOAD_MAX_GAP_NANOS || nanos <= lastNanos) {
            // A pause or a gap: what was open ends without it, and the height starts over.
            closeWindow()
            flush()
            altitude = altitudeM ?: Double.NaN
            sinceAltitudeM = 0.0
            smoothV = v
            openNanos = nanos
            openV = v
            lastNanos = nanos
            lastLat = lat
            lastLon = lon
            return false
        }
        val d = haversineMeters(lastLat, lastLon, lat, lon)
        val dt = (nanos - lastNanos) / 1e9
        smoothV += LOAD_SPEED_ALPHA * (v - smoothV)
        distanceM += d
        openM += d
        openJ += d * (ROLLING + AERO_K * v * v)
        sinceAltitudeM += d
        if (smoothV < CRAWL_MPS) slowS += dt
        if (altitudeM != null) readAltitude(altitudeM)
        lastNanos = nanos
        lastLat = lat
        lastLon = lon
        if (nanos - openNanos < WINDOW_NANOS) return false
        closeWindow()
        return true
    }

    private fun readAltitude(a: Double) {
        if (altitude.isNaN()) {
            altitude = a
            sinceAltitudeM = 0.0
            // No height before this one: the road so far counts without a slope.
            flush()
            openSlopedM = openM
            return
        }
        if (sinceAltitudeM < ALTITUDE_STEP_M || abs(a - altitude) < 1e-3) return
        val slopeWork = G * ((a - altitude) / sinceAltitudeM).coerceIn(-MAX_SLOPE, MAX_SLOPE)
        for (i in 0 until waiting) {
            val bare = waitM[i] - waitSlopedM[i]
            emit(waitM[i], waitJ[i], waitSlopeJ[i] + slopeWork * bare, sloped = true)
        }
        waiting = 0
        openSlopeJ += slopeWork * (openM - openSlopedM)
        openSlopedM = openM
        altitude = a
        sinceAltitudeM = 0.0
    }

    private fun closeWindow() {
        val j = openJ + (smoothV * smoothV - openV * openV) / 2
        flatJ += j.coerceAtLeast(0.0)
        when {
            // Standing, or no height on this phone: nothing to wait for.
            altitude.isNaN() || openM - openSlopedM < 0.5 -> emit(openM, j, openSlopeJ, sloped = !altitude.isNaN())
            else -> {
                if (waiting == PENDING) {
                    flush()
                    // The height was held too long to say which road its change belongs to.
                    altitude = Double.NaN
                }
                waitM[waiting] = openM
                waitJ[waiting] = j
                waitSlopedM[waiting] = openSlopedM
                waitSlopeJ[waiting] = openSlopeJ
                waiting++
            }
        }
        openNanos = lastNanos
        openV = smoothV
        openM = 0.0
        openJ = 0.0
        openSlopedM = 0.0
        openSlopeJ = 0.0
    }

    private fun emit(m: Double, j: Double, slopeWork: Double, sloped: Boolean) {
        slopeJ += (j + slopeWork).coerceAtLeast(0.0)
        if (sloped) slopeM += m
    }

    private fun flush() {
        for (i in 0 until waiting) emit(waitM[i], waitJ[i], waitSlopeJ[i], sloped = false)
        waiting = 0
    }

    /** The sums now; windows still waiting for a height count without one. */
    val sums: EffortSums
        get() {
            var s = slopeJ
            for (i in 0 until waiting) s += (waitJ[i] + waitSlopeJ[i]).coerceAtLeast(0.0)
            val open = (openJ + (smoothV * smoothV - openV * openV) / 2)
            return EffortSums(
                distanceM,
                flatJ + if (openM > 0) open.coerceAtLeast(0.0) else 0.0,
                s + if (openM > 0) (open + openSlopeJ).coerceAtLeast(0.0) else 0.0,
                slopeM,
                slowS,
            )
        }

    private companion object {
        const val G = 9.81

        /** Rolling resistance of a scooter tyre on asphalt, per metre and kg. */
        const val ROLLING = G * 0.015

        /** ½·ρ·CdA / mass for a scooter with rider: 0.5 × 1.2 kg/m³ × 0.6 m² / 200 kg. */
        const val AERO_K = 0.5 * 1.2 * 0.6 / 200.0
    }
}

/** A saved track's effort, for trips from before 4.8 and imported ones (no slope where no height was saved). */
fun effortOf(points: List<TrackPointRecord>): EffortSums {
    val acc = EffortAccumulator()
    points.forEach { acc.add(it.timeMs * 1_000_000, it.latitude, it.longitude, it.speedMps.toDouble(), it.altitudeM?.toDouble()) }
    return acc.sums
}

/** The end of the trip in progress: all of it, and its last ~8 and ~20 km. */
data class LiveEffort(val total: EffortSums, val last8: EffortSums, val last20: EffortSums)

/**
 * The sums at each kilometre of the trip in progress, so its last few can be told apart (one entry per km, nothing else
 * is kept), and when the range should look again: every [PUBLISH_EVERY_NANOS], sooner only when the last kilometre
 * differs by [SOON_CHANGE] or more from what was last published for [SOON_HOLD_NANOS], at most every [SOON_EVERY_NANOS].
 */
class EffortTrail {
    private val marks = ArrayList<EffortSums>().apply { add(EffortSums()) }
    private var publishedAt = Long.MIN_VALUE
    private var published: EffortSums? = null
    private var differsSince = Long.MIN_VALUE

    private fun last(total: EffortSums, km: Int): EffortSums = total - marks[(marks.size - 1 - km).coerceAtLeast(0)]

    private fun mark(total: EffortSums) {
        while (total.distanceM >= marks.size * 1000.0) marks += total
    }

    fun live(total: EffortSums): LiveEffort {
        mark(total)
        return LiveEffort(total, last(total, RECENT_SHORT_KM), last(total, RECENT_LONG_KM))
    }

    /** Called about every 5 s; true = publish now. */
    fun due(total: EffortSums, nowNanos: Long): Boolean {
        mark(total)
        if (publishedAt == Long.MIN_VALUE || nowNanos - publishedAt >= PUBLISH_EVERY_NANOS) return true
        val before = published
        val lastKm = last(total, 1)
        val differs = before != null && before.km >= 1 && lastKm.km >= 1 && abs(roughCost(lastKm) / roughCost(before) - 1) >= SOON_CHANGE
        if (!differs) {
            differsSince = Long.MIN_VALUE
            return false
        }
        if (differsSince == Long.MIN_VALUE) differsSince = nowNanos
        return nowNanos - differsSince >= SOON_HOLD_NANOS && nowNanos - publishedAt >= SOON_EVERY_NANOS
    }

    fun onPublished(total: EffortSums, nowNanos: Long) {
        publishedAt = nowNanos
        published = live(total).last8
        differsSince = Long.MIN_VALUE
    }

    /** Only to tell easy from hard riding, not a fuel figure: a scooter's rough shares of effort and slow time. */
    private fun roughCost(s: EffortSums) = 36.0 + s.effort(false) + 3.0 * s.slow

    private companion object {
        const val PUBLISH_EVERY_NANOS = 60_000_000_000L
        const val SOON_EVERY_NANOS = 15_000_000_000L
        const val SOON_HOLD_NANOS = 30_000_000_000L
        const val SOON_CHANGE = 0.25
    }
}

const val RECENT_SHORT_KM = 8
const val RECENT_LONG_KM = 20
