package com.nativeauto.garage

import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.haversineMeters

/**
 * 4.3d: how hard the bike is ridden, from the fixes the trip already gets (no new sensor, no extra GPS request).
 *
 * Per accepted fix (1 Hz) a few running sums, O(1) and allocation-free ([LoadAccumulator]); per trip they are stored
 * with the trip. Their **energy per km** ([LoadSums.energyPerKm], J per kg per km) is a rough physical model of what
 * the engine has to supply: rolling (constant), air drag (∝ v² per metre), speeding up (kinetic energy gained) and
 * climbing (potential energy). Recorded with the trip (its traffic line comes from here); the range uses [EffortSums] (4.8).
 */

/** Raw sums of one trip, or of a stretch of trips (they add). */
data class LoadSums(
    val distanceM: Double = 0.0,
    /** Σ v²·Δd (m³/s²): air drag work per unit of drag constant. */
    val aero: Double = 0.0,
    /** Σ max(0, Δv)·v on the smoothed speed (m²/s²): kinetic energy gained per kg. */
    val accel: Double = 0.0,
    /** Metres climbed (smoothed altitude, dead band); 0 without altitude. */
    val climbM: Double = 0.0,
    /** Times the bike came to a stop from riding. */
    val stops: Int = 0,
    /**
     * 4.7, recorded only (the range doesn't read them yet): time and distance crawling (slower than [CRAWL_MPS], not
     * stopped), and the metres climbed meanwhile. Standing time is the trip's stopped time.
     */
    val crawlS: Double = 0.0,
    val crawlM: Double = 0.0,
    val crawlClimbM: Double = 0.0,
) {
    operator fun plus(o: LoadSums) = LoadSums(
        distanceM + o.distanceM, aero + o.aero, accel + o.accel, climbM + o.climbM, stops + o.stops,
        crawlS + o.crawlS, crawlM + o.crawlM, crawlClimbM + o.crawlClimbM,
    )

    /** J per kg per km; null on too short a stretch to say anything. */
    val energyPerKm: Double?
        get() = if (distanceM < MIN_LOAD_DISTANCE_M) null
        else 1000.0 * (G * CRR + AERO_K * aero / distanceM + accel / distanceM + G * climbM / distanceM)
}

private const val G = 9.81

/** Rolling resistance of a scooter tyre on asphalt. */
private const val CRR = 0.015

/** ½·ρ·CdA / mass for a scooter with rider: 0.5 × 1.2 kg/m³ × 0.6 m² / 200 kg. */
private const val AERO_K = 0.5 * 1.2 * 0.6 / 200.0

/** Below this a stretch's energy per km is noise. */
const val MIN_LOAD_DISTANCE_M = 500.0

/** A gap longer than this between fixes (a pause, a tunnel) starts the smoothing over and adds no distance. */
internal const val LOAD_MAX_GAP_NANOS = 10_000_000_000L

/** Speed smoothing (exponential, per fix): GPS speed jitter would otherwise count as speeding up. */
internal const val LOAD_SPEED_ALPHA = 0.5

/** Altitude: a ~10 s moving average, and a climb counts once it passes a 2 m dead band. */
internal const val LOAD_ALT_WINDOW = 10
internal const val LOAD_ALT_DEAD_BAND_M = 2.0

/** A stop: the smoothed speed falls under 1 m/s after being over 3 m/s. */
private const val STOP_MPS = 1.0
private const val GO_MPS = 3.0

/** 4.7: slower than this (15 km/h) and not stopped is crawling: queues, jams. */
const val CRAWL_MPS = 15.0 / 3.6

/**
 * Running sums over the accepted fixes of one trip. No allocation per fix (the altitude window is a fixed array).
 * Not thread-safe: fed from one collector.
 */
class LoadAccumulator {
    private var distanceM = 0.0
    private var aero = 0.0
    private var accel = 0.0
    private var climbM = 0.0
    private var stops = 0
    private var crawlS = 0.0
    private var crawlM = 0.0
    private var crawlClimbM = 0.0

    private var lastNanos = Long.MIN_VALUE
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var smoothV = 0.0
    private var moving = false

    private val alt = DoubleArray(LOAD_ALT_WINDOW)
    private var altCount = 0
    private var altIndex = 0
    private var altSum = 0.0
    private var anchor = Double.NaN

    val sums: LoadSums get() = LoadSums(distanceM, aero, accel, climbM, stops, crawlS, crawlM, crawlClimbM)

    fun add(s: SpeedSample) {
        val v = s.speedMps.toDouble()
        val fresh = lastNanos == Long.MIN_VALUE || s.timestampNanos - lastNanos > LOAD_MAX_GAP_NANOS || s.timestampNanos < lastNanos
        if (fresh) {
            smoothV = v
            altCount = 0; altIndex = 0; altSum = 0.0; anchor = Double.NaN
        } else {
            val d = haversineMeters(lastLat, lastLon, s.latitude, s.longitude)
            distanceM += d
            aero += v * v * d
            val sv = smoothV + LOAD_SPEED_ALPHA * (v - smoothV)
            val dv = sv - smoothV
            if (dv > 0) accel += dv * sv
            smoothV = sv
            if (crawling) {
                crawlS += (s.timestampNanos - lastNanos) / 1e9
                crawlM += d
            }
            if (moving && sv < STOP_MPS) { stops++; moving = false }
        }
        if (smoothV > GO_MPS) moving = true
        s.altitudeM?.let(::addAltitude)
        lastNanos = s.timestampNanos
        lastLat = s.latitude
        lastLon = s.longitude
    }

    private val crawling: Boolean get() = smoothV >= STOP_MPS && smoothV < CRAWL_MPS

    private fun addAltitude(a: Double) {
        if (altCount == LOAD_ALT_WINDOW) altSum -= alt[altIndex] else altCount++
        alt[altIndex] = a
        altSum += a
        altIndex = (altIndex + 1) % LOAD_ALT_WINDOW
        if (altCount < LOAD_ALT_WINDOW) return
        val mean = altSum / LOAD_ALT_WINDOW
        when {
            anchor.isNaN() -> anchor = mean
            mean - anchor > LOAD_ALT_DEAD_BAND_M -> {
                climbM += mean - anchor
                if (crawling) crawlClimbM += mean - anchor
                anchor = mean
            }
            anchor - mean > LOAD_ALT_DEAD_BAND_M -> anchor = mean
        }
    }
}
