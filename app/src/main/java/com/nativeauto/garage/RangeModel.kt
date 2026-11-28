package com.nativeauto.garage

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 4.8: the range that follows how the bike is ridden. Per bike, learned from its own dash km/L figures and switched on
 * only once it has earned it ([learnRangeModel]); until then, and whenever it stops being better, the range is the plain
 * one. It is a factor on that plain range ([rangeLoad]), so the fill-ups keep the last word on km/L itself.
 *
 * The dash shows a running average over its last [DASH_MEMORY_KM] or so. In litres per km, a ride of d km moves it from F
 * to F + a·(r − F) with a = d / (d + memory), r being what the ride itself used: base + effort·b1 + slow time·b2.
 */

/** About how far back the dash average looks. Checked against the rider's 56 figures of November 2026: 30 predicts best. */
const val DASH_MEMORY_KM = 30.0

/** A step between two dash figures needs this much riding in it: a shorter one is lost in the dash's rounding. */
const val MIN_STEP_KM = 1.5

/** The gate: steps needed, measured tanks needed, and how much better than the plain figure the model must predict steps it hasn't seen. */
const val MIN_MODEL_STEPS = 20
const val MIN_MODEL_TANKS = 2
const val MODEL_MARGIN = 0.9

/** Steps looked at (the newest). */
const val MAX_MODEL_STEPS = 200

/** Slope joins after this many tanks ridden with a known height. */
const val MIN_SLOPE_TANKS = 4

/** The range moves at most this far: sooner and further down than up. */
val RANGE_FACTOR = 0.8..1.1

/** Less riding than this says nothing about the riding. */
const val MIN_RECENT_M = 2_000.0

/** A note under the range only past this change. */
const val LOAD_NOTE_MIN = 0.05

/** Two dash figures and the riding between them. */
data class DashStep(val fuelBefore: Double, val fuelAfter: Double, val ride: EffortSums) {
    val share: Double get() = ride.km / (ride.km + DASH_MEMORY_KM)
    /** What the step says the dash's share of this ride used, in litres per km. */
    val y: Double get() = fuelAfter - (1 - share) * fuelBefore
}

/** Litres per km (by the dash): base + effort·[perEffort] + slow·[perSlow]; [usual]: the riding it was learned on. */
data class RangeModel(
    val base: Double,
    val perEffort: Double,
    val perSlow: Double,
    val usual: EffortSums,
    val withSlope: Boolean,
    val steps: Int,
) {
    fun litresPerKm(s: EffortSums): Double = base + perEffort * s.effort(withSlope) + perSlow * s.slow
}

/** Where a bike stands: [model] is null until (or unless) it is good enough. [steps] and [tanks] so far. */
data class RangeLearning(val model: RangeModel? = null, val steps: Int = 0, val tanks: Int = 0)

/**
 * The steps between dash figures, oldest first. [gapTimesMs]: odometer readings that stand for km the app didn't see; a
 * step with one in it says nothing.
 */
fun dashSteps(dash: List<DashKmPerL>, rides: List<RideEffort>, gapTimesMs: List<Long>): List<DashStep> {
    val figures = dash.filter { it.kmPerL in KMPL_PLAUSIBLE }.sortedWith(compareBy({ it.timeMs }, { it.id }))
    val byTime = rides.sortedBy { it.endedAtMs }
    var r = 0
    val steps = ArrayList<DashStep>()
    for ((before, after) in figures.zipWithNext()) {
        while (r < byTime.size && byTime[r].endedAtMs <= before.timeMs) r++
        var sum = EffortSums()
        var i = r
        while (i < byTime.size && byTime[i].endedAtMs <= after.timeMs) sum += byTime[i++].sums
        if (sum.km >= MIN_STEP_KM && gapTimesMs.none { it > before.timeMs && it <= after.timeMs }) {
            steps += DashStep(1 / before.kmPerL, 1 / after.kmPerL, sum)
        }
    }
    return steps.takeLast(MAX_MODEL_STEPS)
}

/**
 * The model, if the bike has earned it: [MIN_MODEL_STEPS] steps, [MIN_MODEL_TANKS] measured tanks, and predicting steps it
 * has not seen better than a plain figure does, both one by one (by [MODEL_MARGIN]) and for the newest 40 % from the rest.
 */
fun learnRangeModel(
    dash: List<DashKmPerL>,
    rides: List<RideEffort>,
    gapTimesMs: List<Long>,
    measures: List<TankMeasure>,
): RangeLearning {
    val all = dashSteps(dash, rides, gapTimesMs)
    val used = measures.filter { it.used }
    val sloped = all.filter { it.ride.sloped }
    val withSlope = sloped.size >= MIN_MODEL_STEPS && used.count { tankSloped(it, rides) } >= MIN_SLOPE_TANKS
    val steps = if (withSlope) sloped else all
    val learning = RangeLearning(null, steps.size, used.size)
    if (steps.size < MIN_MODEL_STEPS || used.size < MIN_MODEL_TANKS) return learning
    val c = fit(steps, withSlope) ?: return learning
    // Harder riding and standing about both cost fuel, or it isn't physics.
    if (c[0] <= 0 || c[1] < 0 || c[2] < 0) return learning
    if (leaveOneOut(steps, withSlope, full = true) > MODEL_MARGIN * leaveOneOut(steps, withSlope, full = false)) return learning
    if (newest(steps, withSlope, full = true) > newest(steps, withSlope, full = false)) return learning
    val usual = steps.fold(EffortSums()) { sum, s -> sum + s.ride }
    return learning.copy(model = RangeModel(c[0], c[1], c[2], usual, withSlope, steps.size))
}

private fun tankSloped(m: TankMeasure, rides: List<RideEffort>): Boolean {
    val sum = rides.filter { it.endedAtMs > m.startTimeMs && it.endedAtMs <= m.endTimeMs }.fold(EffortSums()) { s, r -> s + r.sums }
    return sum.km >= SLOPE_COVERAGE * m.km * 0.5 && sum.sloped
}

private fun row(s: DashStep, withSlope: Boolean): DoubleArray =
    doubleArrayOf(s.share, s.share * s.ride.effort(withSlope), s.share * s.ride.slow)

/** Least squares over the steps; [skip] leaves one out. Null when the steps don't tell the parts apart. */
private fun fit(steps: List<DashStep>, withSlope: Boolean, skip: Int = -1): DoubleArray? {
    val a = Array(3) { DoubleArray(4) }
    steps.forEachIndexed { i, s ->
        if (i != skip) {
            val x = row(s, withSlope)
            for (p in 0..2) {
                for (q in 0..2) a[p][q] += x[p] * x[q]
                a[p][3] += x[p] * s.y
            }
        }
    }
    for (p in 0..2) {
        val pivot = (p..2).maxBy { abs(a[it][p]) }
        val t = a[p]; a[p] = a[pivot]; a[pivot] = t
        if (abs(a[p][p]) < 1e-9) return null
        for (q in 0..2) {
            if (q == p) continue
            val f = a[q][p] / a[p][p]
            for (k in p..3) a[q][k] -= f * a[p][k]
        }
    }
    return DoubleArray(3) { a[it][3] / a[it][it] }
}

/** The plain figure: one consumption for all riding. */
private fun plain(steps: List<DashStep>, skip: Int = -1): Double {
    var xx = 0.0
    var xy = 0.0
    steps.forEachIndexed { i, s -> if (i != skip) { xx += s.share * s.share; xy += s.share * s.y } }
    return if (xx > 0) xy / xx else 0.0
}

private fun error(s: DashStep, c: DoubleArray, withSlope: Boolean): Double {
    val x = row(s, withSlope)
    val e = s.y - (c[0] * x[0] + c[1] * x[1] + c[2] * x[2])
    return e * e
}

private fun error(s: DashStep, plain: Double): Double = (s.y - plain * s.share).let { it * it }

private fun leaveOneOut(steps: List<DashStep>, withSlope: Boolean, full: Boolean): Double =
    steps.indices.sumOf { i ->
        if (full) fit(steps, withSlope, skip = i)?.let { error(steps[i], it, withSlope) } ?: Double.MAX_VALUE else error(steps[i], plain(steps, skip = i))
    }

private fun newest(steps: List<DashStep>, withSlope: Boolean, full: Boolean): Double {
    val cut = steps.size * 6 / 10
    val old = steps.subList(0, cut)
    val new = steps.subList(cut, steps.size)
    if (!full) return plain(old).let { p -> new.sumOf { error(it, p) } }
    val c = fit(old, withSlope) ?: return Double.MAX_VALUE
    return new.sumOf { error(it, c, withSlope) }
}

/** The last [km] of riding: the trip in progress ([live], its own last km), then saved trips newest first. */
fun recentEffort(ridesNewestFirst: List<RideEffort>, live: EffortSums?, km: Int): EffortSums {
    var sum = live ?: EffortSums()
    for (r in ridesNewestFirst) {
        if (sum.distanceM >= km * 1000.0) break
        sum += r.sums
    }
    return sum
}

/** How the range is adjusted for riding: [factor] multiplies the plain range. [on]: the bike's model is in use. */
data class RangeLoad(val factor: Double = 1.0, val on: Boolean = false) {
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

/**
 * The factor now: what the usual riding uses over what the recent riding uses. The lower of the last 8 and the last 20 km,
 * so it drops sooner than it rises.
 */
fun rangeLoad(model: RangeModel?, ridesNewestFirst: List<RideEffort>, live: LiveEffort?): RangeLoad {
    if (model == null) return RangeLoad()
    val usual = model.litresPerKm(model.usual)
    val factors = listOf(
        recentEffort(ridesNewestFirst, live?.last8, RECENT_SHORT_KM),
        recentEffort(ridesNewestFirst, live?.last20, RECENT_LONG_KM),
    ).mapNotNull { s -> if (s.distanceM < MIN_RECENT_M) null else usual / model.litresPerKm(s) }
    val factor = factors.filter { it.isFinite() && it > 0 }.minOrNull() ?: 1.0
    return RangeLoad(factor.coerceIn(RANGE_FACTOR.start, RANGE_FACTOR.endInclusive), on = true)
}

/**
 * The trip in progress' effort, published by `TripRecorder` ([EffortTrail]: about once a minute), so the range can follow
 * a ride without redrawing at 1 Hz. Null when no trip is running or paused.
 */
@Singleton
class LiveRidingLoad @Inject constructor() {
    private val _live = MutableStateFlow<LiveEffort?>(null)
    val live: StateFlow<LiveEffort?> = _live.asStateFlow()
    fun publish(l: LiveEffort?) { _live.value = l }
}
