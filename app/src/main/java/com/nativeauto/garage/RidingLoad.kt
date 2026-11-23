package com.nativeauto.garage

import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.haversineMeters
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 4.3d: how hard the bike is ridden, from the fixes the trip already gets (no new sensor, no extra GPS request).
 *
 * Per accepted fix (1 Hz) a few running sums, O(1) and allocation-free ([LoadAccumulator]); per trip they are stored
 * with the trip. Their **energy per km** ([LoadSums.energyPerKm], J per kg per km) is a rough physical model of what
 * the engine has to supply: rolling (constant), air drag (∝ v² per metre), speeding up (kinetic energy gained) and
 * climbing (potential energy). It's a relative measure only: the bike's own fill-ups teach how it maps to km/L
 * ([fitLoad]), and until they have, the range is untouched.
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
) {
    operator fun plus(o: LoadSums) =
        LoadSums(distanceM + o.distanceM, aero + o.aero, accel + o.accel, climbM + o.climbM, stops + o.stops)

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

    val sums: LoadSums get() = LoadSums(distanceM, aero, accel, climbM, stops)

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
            if (moving && sv < STOP_MPS) { stops++; moving = false }
        }
        if (smoothV > GO_MPS) moving = true
        s.altitudeM?.let(::addAltitude)
        lastNanos = s.timestampNanos
        lastLat = s.latitude
        lastLon = s.longitude
    }

    private fun addAltitude(a: Double) {
        if (altCount == LOAD_ALT_WINDOW) altSum -= alt[altIndex] else altCount++
        alt[altIndex] = a
        altSum += a
        altIndex = (altIndex + 1) % LOAD_ALT_WINDOW
        if (altCount < LOAD_ALT_WINDOW) return
        val mean = altSum / LOAD_ALT_WINDOW
        when {
            anchor.isNaN() -> anchor = mean
            mean - anchor > LOAD_ALT_DEAD_BAND_M -> { climbM += mean - anchor; anchor = mean }
            anchor - mean > LOAD_ALT_DEAD_BAND_M -> anchor = mean
        }
    }
}

/** One saved trip's sums, for learning (the trips table's load columns). */
data class TripLoad(
    val endedAtMs: Long,
    val distanceM: Double,
    val aero: Double,
    val accel: Double,
    val climbM: Double,
    val stops: Int,
) {
    val sums: LoadSums get() = LoadSums(distanceM, aero, accel, climbM, stops)
}

/** A full-to-full span with the riding load of the trips in it. */
data class SpanLoad(val kmPerL: Double, val load: Double, val km: Double)

/** A span counts only if trips with load data cover this much of its odometer km. */
const val SPAN_LOAD_COVERAGE = 0.5

/** The learned link between load and km/L: kmPerL ≈ a + b·load. [meanLoad]: the spans' weighted mean load. */
data class LoadFit(val a: Double, val b: Double, val meanLoad: Double, val spans: Int) {
    fun kmPerLAt(load: Double) = a + b * load
}

/** Fewer spans than this: no fit, factor 1.0 (the "record first" step). */
const val MIN_LOAD_SPANS = 4

/** The range moves at most this far either way. */
val LOAD_FACTOR_RANGE = 0.8..1.2

/** The recent load looks at about this many km of riding. */
const val RECENT_LOAD_M = 20_000.0

/** A note under the range only past this change. */
const val LOAD_NOTE_MIN = 0.05

/**
 * The used full-to-full spans, each with the distance-weighted load of the trips that ended inside it. Spans the trips
 * don't cover well ([SPAN_LOAD_COVERAGE]: rides without the app, old trips without load data) are left out.
 */
fun spanLoads(measures: List<TankMeasure>, trips: List<TripLoad>): List<SpanLoad> =
    measures.filter { it.used && it.startTimeMs > 0 }.mapNotNull { m ->
        val inSpan = trips.filter { it.endedAtMs > m.startTimeMs && it.endedAtMs <= m.endTimeMs }
        val sums = inSpan.fold(LoadSums()) { acc, t -> acc + t.sums }
        val load = sums.energyPerKm ?: return@mapNotNull null
        if (sums.distanceM / 1000.0 < SPAN_LOAD_COVERAGE * m.km) return@mapNotNull null
        SpanLoad(m.kmPerL, load, m.km)
    }

/**
 * Weighted least squares of km/L on load over the spans (oldest first), recent spans weighted more, like
 * [learnConsumption] ((1 − [KMPL_WEIGHT]) per span of age). Kept only when it is worth it: at least [MIN_LOAD_SPANS]
 * spans, loads that differ, harder riding costing km/L (b < 0), and a fit that beats the plain mean once its extra
 * parameter is paid for (residual per degree of freedom).
 */
fun fitLoad(spans: List<SpanLoad>): LoadFit? {
    val n = spans.size
    if (n < MIN_LOAD_SPANS) return null
    val w = DoubleArray(n) { i -> (1 - KMPL_WEIGHT).pow(n - 1 - i) }
    val sw = w.sum()
    val mx = spans.indices.sumOf { w[it] * spans[it].load } / sw
    val my = spans.indices.sumOf { w[it] * spans[it].kmPerL } / sw
    val sxx = spans.indices.sumOf { w[it] * (spans[it].load - mx).pow(2) }
    if (sxx <= 1e-9) return null
    val sxy = spans.indices.sumOf { w[it] * (spans[it].load - mx) * (spans[it].kmPerL - my) }
    val b = sxy / sxx
    val a = my - b * mx
    if (b >= 0) return null
    val sseFit = spans.indices.sumOf { w[it] * (spans[it].kmPerL - (a + b * spans[it].load)).pow(2) }
    val sseMean = spans.indices.sumOf { w[it] * (spans[it].kmPerL - my).pow(2) }
    if (sseFit / (n - 2) >= sseMean / (n - 1)) return null
    return LoadFit(a, b, mx, n)
}

/** The load of the last ~[RECENT_LOAD_M] of riding: the live trip first, then saved trips newest first. */
fun recentLoad(tripsNewestFirst: List<TripLoad>, live: LoadSums?): Double? {
    var sums = live ?: LoadSums()
    for (t in tripsNewestFirst) {
        if (sums.distanceM >= RECENT_LOAD_M) break
        sums += t.sums
    }
    return sums.energyPerKm
}

/** How the range is adjusted for riding: [factor] multiplies the cautious km/L. */
data class RangeLoad(val factor: Double = 1.0, val spans: Int = 0) {
    /** "Riding hard: −12%" / "Riding easy: +8%", or null within ±[LOAD_NOTE_MIN]. */
    val note: String?
        get() {
            val change = factor - 1
            if (abs(change) < LOAD_NOTE_MIN) return null
            val pct = (abs(change) * 100).roundToInt()
            return if (change < 0) String.format(Locale.US, "Riding hard: −%d%%", pct)
            else String.format(Locale.US, "Riding easy: +%d%%", pct)
        }
}

/** The range factor: km/L the fit expects at the recent load over what it expects at the spans' usual load, clamped. */
fun rangeLoad(fit: LoadFit?, recent: Double?): RangeLoad {
    if (fit == null || recent == null) return RangeLoad(1.0, fit?.spans ?: 0)
    val usual = fit.kmPerLAt(fit.meanLoad)
    val now = fit.kmPerLAt(recent)
    if (usual <= 0 || now <= 0) return RangeLoad(1.0, fit.spans)
    return RangeLoad((now / usual).coerceIn(LOAD_FACTOR_RANGE.start, LOAD_FACTOR_RANGE.endInclusive), fit.spans)
}

/**
 * The trip in progress' sums, published by `TripRecorder` about once a minute (at its checkpoint), so the range can
 * react during a ride without redrawing at 1 Hz. Null when no trip is running or paused.
 */
@Singleton
class LiveRidingLoad @Inject constructor() {
    private val _sums = MutableStateFlow<LoadSums?>(null)
    val sums: StateFlow<LoadSums?> = _sums.asStateFlow()
    fun publish(s: LoadSums?) { _sums.value = s }
}
