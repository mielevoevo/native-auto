package com.nativeauto.garage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Fuel (11b, Rider-Feedback §6 "Fuel"), pure maths so it's unit-tested on the JVM.
 *
 * - Distances come from the **odometer** (readings, corrections, untracked km), never from GPS
 *   km directly, so a ride without the app can't skew km/L ("riding without the app").
 * - km/L is learned from full-to-full fill-ups; the tank level is walked forward from the last
 *   full fill; away km (someone else rode it) make the level unknown until the next full fill.
 */

/** One fill-up. [odometerKm] is the bike's real reading at the pump (prefilled with the estimate). */
@Entity(tableName = "refuels", indices = [Index("bikeId")])
data class RefuelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bikeId: String,
    val timeMs: Long,
    val odometerKm: Double,
    val litres: Double,
    /** Rp per litre at the pump. */
    val pricePerL: Double,
    /** Rp paid. */
    val totalRp: Double,
    /** Filled to the brim (the default) or partial. */
    val full: Boolean,
    /** [FuelType] name. */
    val fuelType: String,
) {
    val fuel: FuelType get() = FuelType.entries.firstOrNull { it.name == fuelType } ?: FuelType.PERTAMAX
}

/** The last price per litre seen at the pump, per fuel type (the refuel log updates it). */
@Entity(tableName = "fuel_prices")
data class FuelPrice(
    /** [FuelType] name. */
    @PrimaryKey val fuelType: String,
    val pricePerL: Double,
    val updatedAtMs: Long,
)

/**
 * 12b "Set fuel level": the tank as read off the bike's own gauge, with the odometer at that
 * moment. Until the next full fill the level is walked forward from here instead.
 */
@Entity(tableName = "fuel_marks", indices = [Index("bikeId")])
data class FuelMark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bikeId: String,
    val timeMs: Long,
    val odometerKm: Double,
    /** Bars lit on the gauge, out of [ofBars]. */
    val bars: Int,
    val ofBars: Int,
    /** What that means in litres ([gaugeLitres], cautious). */
    val litres: Double,
)

/** 12b: the bike's own trip average (km/L from its dash), given after a ride. */
@Entity(tableName = "dash_kmpl", indices = [Index("bikeId")])
data class DashKmPerL(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bikeId: String,
    val timeMs: Long,
    val kmPerL: Double,
    /** GPS km of the ride it was given after. */
    val tripKm: Double,
)

/** Bars on the bike's fuel gauge (rider, 2026-11-09): NMAX gen 1 6, gen 2 8. Per bike: after Phase 12. */
fun gaugeBars(model: BikeModel): Int = if (model == BikeModel.NMAX_GEN1) 6 else 8

/**
 * Litres for [bars] lit out of [ofBars]. A bar stays lit until it's empty, so n bars means
 * somewhere between n−1 and n: the cautious middle of the last bar, n − ½. No bars = 0.
 */
fun gaugeLitres(bars: Int, ofBars: Int, tankLitres: Double): Double =
    if (bars <= 0 || ofBars <= 0) 0.0 else ((bars - 0.5) / ofBars).coerceIn(0.0, 1.0) * tankLitres

/** Rp per litre for [fuel]: the last one logged, else the built-in default. */
fun priceOf(fuel: FuelType, prices: List<FuelPrice>): Double =
    prices.firstOrNull { it.fuelType == fuel.name }?.pricePerL ?: fuel.defaultPricePerL

// ---- Consumption learning -------------------------------------------------------------------

/** How far each full-to-full measure moves the estimate (recent tanks count more). */
const val KMPL_WEIGHT = 0.3

/** The first measure replaces a guessed starting value faster. */
const val KMPL_FIRST_WEIGHT = 0.5

/** Measures outside this are typos or a forgotten fill-up, whatever the estimate. */
val KMPL_PLAUSIBLE = 10.0..100.0

/** 12b: how far one dash figure moves the estimate. Smaller than a tank: dashes read optimistic. */
const val DASH_WEIGHT = 0.15

/** 12b: the cautious figure looks at this many recent tanks. */
const val RECENT_TANKS = 3

/** Once learning has started, a measure this far (±) from the estimate is an outlier. */
const val KMPL_OUTLIER = 0.35

/** One full-to-full span. [used] false: skipped (away km in it) or an outlier. */
data class TankMeasure(
    val endRefuelId: Long,
    val endTimeMs: Long,
    val km: Double,
    val litres: Double,
    val used: Boolean,
) {
    val kmPerL: Double get() = if (litres > 0) km / litres else 0.0
}

data class Consumption(
    /** The learned km/L (the bike's starting value until the first full-to-full span). */
    val kmPerL: Double,
    /** Oldest first: the km/L trend. */
    val measures: List<TankMeasure>,
    /** 12b: dash figures that taught the estimate. */
    val dashUsed: Int = 0,
) {
    val learned: Boolean get() = measures.any { it.used } || dashUsed > 0

    /** Full-to-full spans that taught the estimate. */
    val tanksUsed: Int get() = measures.count { it.used }

    /**
     * 12b, the range's figure: the lower of the long-run estimate and the mean of the last
     * [RECENT_TANKS] tanks, so a recent drop in economy shows at once and a good tank doesn't
     * make the range promise more than the long run.
     */
    val cautiousKmPerL: Double get() {
        val recent = measures.filter { it.used }.takeLast(RECENT_TANKS)
        return if (recent.isEmpty()) kmPerL else minOf(kmPerL, recent.map { it.kmPerL }.average())
    }
}

/** Oldest first; same-time entries by odometer. */
private fun chronological(refuels: List<RefuelRecord>) = refuels.sortedWith(compareBy({ it.timeMs }, { it.odometerKm }, { it.id }))

/**
 * Walks the fill-ups oldest first. Between two full fills, km = odometer delta and litres =
 * everything added after the first, up to and including the second (partials too). Spans with
 * an away reading in them ([awayTimesMs]) are skipped: others rode it.
 */
fun learnConsumption(
    startKmPerL: Double,
    refuels: List<RefuelRecord>,
    awayTimesMs: List<Long>,
    dash: List<DashKmPerL> = emptyList(),
): Consumption {
    var estimate = startKmPerL
    var lastFull: RefuelRecord? = null
    var litres = 0.0
    var accepted = 0
    var dashUsed = 0
    val measures = ArrayList<TankMeasure>()
    // 12b: dash figures join in time order; they nudge the estimate and leave the tank spans alone.
    val pending = dash.sortedWith(compareBy({ it.timeMs }, { it.id })).toMutableList()
    fun takeDash(untilMs: Long) {
        while (pending.isNotEmpty() && pending.first().timeMs <= untilMs) {
            val v = pending.removeAt(0).kmPerL
            val learning = accepted + dashUsed > 0
            if (v in KMPL_PLAUSIBLE && (!learning || abs(v / estimate - 1) <= KMPL_OUTLIER)) {
                estimate += DASH_WEIGHT * (v - estimate)
                dashUsed++
            }
        }
    }
    for (r in chronological(refuels)) {
        takeDash(r.timeMs)
        if (lastFull == null) {
            if (r.full) lastFull = r
            continue
        }
        litres += r.litres
        if (!r.full) continue
        val km = r.odometerKm - lastFull.odometerKm
        val away = awayTimesMs.any { it > lastFull!!.timeMs && it <= r.timeMs }
        if (km > 0 && litres > 0 && !away) {
            val measured = km / litres
            val plausible = measured in KMPL_PLAUSIBLE && (accepted + dashUsed == 0 || abs(measured / estimate - 1) <= KMPL_OUTLIER)
            if (plausible) {
                estimate += (if (accepted == 0) KMPL_FIRST_WEIGHT else KMPL_WEIGHT) * (measured - estimate)
                accepted++
            }
            measures += TankMeasure(r.id, r.timeMs, km, litres, used = plausible)
        } else if (km > 0 && litres > 0) {
            measures += TankMeasure(r.id, r.timeMs, km, litres, used = false)
        }
        lastFull = r
        litres = 0.0
    }
    takeDash(Long.MAX_VALUE)
    return Consumption(estimate, measures, dashUsed)
}

// ---- Tank level and range ------------------------------------------------------------------

data class FuelLevel(
    /** Estimated litres in the tank now; null when unknown (no full fill yet, or away km since). */
    val litres: Double?,
    val tankLitres: Double,
    val kmPerL: Double,
    /** Odometer km since the last fill-up (any kind); null before the first one. */
    val sinceRefuelKm: Double?,
    /** Why [litres] is null. */
    val unknownBecause: UnknownFuel? = null,
    /** 12b: walked from a gauge reading ("Set fuel level"), not from a full fill. */
    val fromGauge: Boolean = false,
) {
    /** 12b: the one number the rider sees: [rangeKm] rounded down to 5 km. */
    val cautiousRangeKm: Long? get() = rangeKm?.let { (floor(it / RANGE_STEP_KM) * RANGE_STEP_KM).toLong() }

    /** Upper bound between readings: untracked km only show up at the next reading. */
    val rangeKm: Double? get() = litres?.let { it * kmPerL }
    val fraction: Double? get() = litres?.let { (it / tankLitres).coerceIn(0.0, 1.0) }
    /** Litres to fill up now. */
    val toFillLitres: Double? get() = litres?.let { (tankLitres - it).coerceAtLeast(0.0) }
}

enum class UnknownFuel { NO_FULL_FILL, AWAY }

/** The range is rounded down to this. */
const val RANGE_STEP_KM = 5.0

/**
 * The tank now: full at the last full fill, minus odometer km ÷ [kmPerL] at each later step,
 * plus each partial's litres (capped at the tank), minus the km since to [odometerNowKm].
 * 12b: a gauge reading ([marks]) newer than the last full fill is the starting point instead.
 */
fun fuelLevel(
    tankLitres: Double,
    kmPerL: Double,
    refuels: List<RefuelRecord>,
    awayTimesMs: List<Long>,
    odometerNowKm: Double?,
    marks: List<FuelMark> = emptyList(),
): FuelLevel {
    val sorted = chronological(refuels)
    val last = sorted.lastOrNull()
    val since = if (last != null && odometerNowKm != null) (odometerNowKm - last.odometerKm).coerceAtLeast(0.0) else null
    val fullIndex = sorted.indexOfLast { it.full }
    val full = sorted.getOrNull(fullIndex)
    val mark = marks.maxWithOrNull(compareBy({ it.timeMs }, { it.id }))?.takeIf { full == null || it.timeMs > full.timeMs }
    if (full == null && mark == null) return FuelLevel(null, tankLitres, kmPerL, since, UnknownFuel.NO_FULL_FILL)
    val anchorMs = mark?.timeMs ?: full!!.timeMs
    if (awayTimesMs.any { it > anchorMs }) return FuelLevel(null, tankLitres, kmPerL, since, UnknownFuel.AWAY, fromGauge = mark != null)
    var litres = mark?.litres?.coerceIn(0.0, tankLitres) ?: tankLitres
    var odo = mark?.odometerKm ?: full!!.odometerKm
    val after = if (mark != null) sorted.filter { it.timeMs > mark.timeMs } else sorted.drop(fullIndex + 1)
    for (r in after) {
        litres = (litres - (r.odometerKm - odo).coerceAtLeast(0.0) / kmPerL).coerceAtLeast(0.0)
        litres = (litres + r.litres).coerceAtMost(tankLitres)
        odo = maxOf(odo, r.odometerKm)
    }
    if (odometerNowKm != null) litres = (litres - (odometerNowKm - odo).coerceAtLeast(0.0) / kmPerL).coerceAtLeast(0.0)
    return FuelLevel(litres, tankLitres, kmPerL, since, fromGauge = mark != null)
}

// ---- Amounts, costs, spend -----------------------------------------------------------------

/** How the rider typed the amount at the pump. */
enum class AmountMode { RUPIAH, LITRES }

/** Litres and Rp from one typed amount and the price per litre; null if either is unusable. */
fun refuelAmounts(mode: AmountMode, amount: Double?, pricePerL: Double?): Pair<Double, Double>? {
    if (amount == null || amount <= 0 || pricePerL == null || pricePerL <= 0) return null
    return when (mode) {
        AmountMode.RUPIAH -> amount / pricePerL to amount
        AmountMode.LITRES -> amount to amount * pricePerL
    }
}

/** Rp per odometer km between the first and the last full fill (the fuel bought in between). */
fun costPerKm(refuels: List<RefuelRecord>): Double? {
    val sorted = chronological(refuels)
    val first = sorted.indexOfFirst { it.full }
    val last = sorted.indexOfLast { it.full }
    if (first < 0 || last <= first) return null
    val km = sorted[last].odometerKm - sorted[first].odometerKm
    if (km <= 0) return null
    return sorted.subList(first + 1, last + 1).sumOf { it.totalRp } / km
}

data class Spend(val last7Days: Double, val thisMonth: Double, val lastMonth: Double)

fun spend(refuels: List<RefuelRecord>, nowMs: Long, zone: ZoneId): Spend {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
    val lastMonthStart = monthStart.minusMonths(1)
    fun sum(from: Long, to: Long) = refuels.filter { it.timeMs in from until to }.sumOf { it.totalRp }
    val end = nowMs + 1
    return Spend(
        last7Days = sum(nowMs - 7L * 24 * 3_600_000, end),
        thisMonth = sum(monthStart.toInstant().toEpochMilli(), end),
        lastMonth = sum(lastMonthStart.toInstant().toEpochMilli(), monthStart.toInstant().toEpochMilli()),
    )
}

/** "Rp 98.000": Indonesian grouping, whole rupiah. */
fun formatRupiah(rp: Double): String = "Rp " + String.format(Locale.US, "%,d", rp.roundToLong()).replace(',', '.')

/** "5.0 L". */
fun formatLitres(l: Double): String = String.format(Locale.US, "%.1f L", l)

/** "36.4 km/L". */
fun formatKmPerL(v: Double): String = String.format(Locale.US, "%.1f km/L", v)
