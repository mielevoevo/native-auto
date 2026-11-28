package com.nativeauto.garage

import androidx.room.ColumnInfo
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
    /** [Fuel.key]. */
    val fuelType: String,
    /** 4.8: false = the rider said not to learn km/L from it ("Don't learn from this"). */
    @ColumnInfo(defaultValue = "1")
    val learn: Boolean = true,
)

/** The last price per litre seen at the pump, per fuel type (the refuel log updates it). */
@Entity(tableName = "fuel_prices")
data class FuelPrice(
    /** [Fuel.key]. */
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

/**
 * Litres for [bars] lit out of [ofBars]. A bar stays lit until it's empty, so n bars means
 * somewhere between n−1 and n: the cautious middle of the last bar, n − ½. No bars = 0.
 */
fun gaugeLitres(bars: Int, ofBars: Int, tankLitres: Double): Double =
    if (bars <= 0 || ofBars <= 0) 0.0 else ((bars - 0.5) / ofBars).coerceIn(0.0, 1.0) * tankLitres

/** Rp per litre for [fuel]: the last one logged, else the built-in default. */
fun priceOf(fuel: Fuel, prices: List<FuelPrice>): Double =
    prices.firstOrNull { it.fuelType == fuel.key }?.pricePerL ?: fuel.defaultPricePerL

/** What a litre may cost (Rp): outside it is a typo. */
val PRICE_RANGE = 1_000.0..100_000.0

// ---- Consumption learning -------------------------------------------------------------------

/** How far each full-to-full measure moves the estimate (recent tanks count more). */
const val KMPL_WEIGHT = 0.3

/** The first measure replaces a guessed starting value faster. */
const val KMPL_FIRST_WEIGHT = 0.5

/** Measures outside this are typos or a forgotten fill-up, whatever the estimate. */
val KMPL_PLAUSIBLE = 10.0..100.0

/**
 * 4.8: how far the dash figures since the last full fill move the estimate, all of them together: less than a tank does
 * ([KMPL_WEIGHT], [KMPL_FIRST_WEIGHT]), whatever their number. [DASH_FIRST_WEIGHT] while no tank has taught it yet (the
 * starting value is a guess). A group of n counts for n / (n + [DASH_GROUP_HALF]) of it, so a single figure does little.
 */
const val DASH_WEIGHT = 0.2
const val DASH_FIRST_WEIGHT = 0.4
const val DASH_GROUP_HALF = 2.0

/** 4.8: the bike's own tanks say how far its dash is off: tank km/L over the mean of the figures given during that tank. */
const val DASH_RATIO_MIN_ENTRIES = 3
val DASH_RATIO_RANGE = 0.85..1.15

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
    /** 4.3d: when the span began (the full fill before it); 0 for spans built elsewhere. */
    val startTimeMs: Long = 0,
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
    /** 4.8: what the dash figures are multiplied by (1.0 until a tank has been measured against them). */
    val dashRatio: Double = 1.0,
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
 * an away reading in them ([awayTimesMs]) are skipped: others rode it; so are spans with a fill-up the rider said not
 * to learn from (4.8), and the one that starts at it.
 *
 * Dash figures ([dash], 4.8) are grouped by the tank they were given in. Once that tank is measured it speaks for
 * itself: its figures only teach [Consumption.dashRatio]. Figures of a tank that wasn't measured, and those since the
 * last full fill, move the estimate together, corrected by that ratio.
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
    var ratio = 1.0
    var ratios = 0
    var learn = true
    val measures = ArrayList<TankMeasure>()
    val pending = dash.sortedWith(compareBy({ it.timeMs }, { it.id }))
    var next = 0
    val group = ArrayList<Double>()
    fun takeDash(untilMs: Long) {
        while (next < pending.size && pending[next].timeMs <= untilMs) {
            val v = pending[next++].kmPerL
            val learning = accepted + dashUsed > 0
            val reference = if (group.isEmpty()) estimate else group.average() * ratio
            if (v in KMPL_PLAUSIBLE && (!learning || abs(v * ratio / reference - 1) <= KMPL_OUTLIER)) {
                group += v
                dashUsed++
            }
        }
    }
    fun closeGroup() {
        if (group.isNotEmpty()) {
            val weight = (if (accepted == 0) DASH_FIRST_WEIGHT else DASH_WEIGHT) * group.size / (group.size + DASH_GROUP_HALF)
            estimate += weight * (group.average() * ratio - estimate)
        }
        group.clear()
    }
    for (r in chronological(refuels)) {
        takeDash(r.timeMs)
        if (lastFull == null) {
            if (r.full) {
                closeGroup()
                lastFull = r
            }
            continue
        }
        litres += r.litres
        learn = learn && r.learn
        if (!r.full) continue
        val km = r.odometerKm - lastFull.odometerKm
        val away = awayTimesMs.any { it > lastFull!!.timeMs && it <= r.timeMs }
        if (km > 0 && litres > 0 && !away && learn) {
            val measured = km / litres
            val plausible = measured in KMPL_PLAUSIBLE && (accepted + dashUsed == 0 || abs(measured / estimate - 1) <= KMPL_OUTLIER)
            if (plausible) {
                if (group.size >= DASH_RATIO_MIN_ENTRIES) {
                    val seen = (measured / group.average()).coerceIn(DASH_RATIO_RANGE.start, DASH_RATIO_RANGE.endInclusive)
                    ratio += (if (ratios++ == 0) KMPL_FIRST_WEIGHT else KMPL_WEIGHT) * (seen - ratio)
                }
                group.clear()
                estimate += (if (accepted == 0) KMPL_FIRST_WEIGHT else KMPL_WEIGHT) * (measured - estimate)
                accepted++
            }
            measures += TankMeasure(r.id, r.timeMs, km, litres, used = plausible, startTimeMs = lastFull.timeMs)
        } else if (km > 0 && litres > 0) {
            measures += TankMeasure(r.id, r.timeMs, km, litres, used = false, startTimeMs = lastFull.timeMs)
        }
        closeGroup()
        lastFull = r
        litres = 0.0
        // A fill-up in doubt says as little about the tank it starts.
        learn = r.learn
    }
    takeDash(Long.MAX_VALUE)
    closeGroup()
    return Consumption(estimate, measures, dashUsed, ratio)
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
    /** 4.3d: how the riding lately moves the range ([rangeLoad]); 1.0 until the fill-ups have taught it. */
    val loadFactor: Double = 1.0,
    /** 4.6.1: the bike's low-fuel warning: at or below this many km on the range shown. */
    val lowFuelKm: Int = DEFAULT_LOW_FUEL_KM,
    /** 4.8: the bike's own caution ([RangeCaution]), on the range only. */
    val caution: Double = 1.0,
    /** 4.8: [RANGE_STEP_KM] once km/L has been learned, [RANGE_GUESS_STEP_KM] before. */
    val stepKm: Double = RANGE_GUESS_STEP_KM,
    /** 4.8: what is shown now ([RangeHold]: no flicker); null = the figure as it is. */
    val heldKm: Long? = null,
) {
    /** The one low-fuel rule (header pump, Garage card, bike detail, greeting). Unknown fuel is never low. */
    val low: Boolean get() = cautiousRangeKm?.let { it <= lowFuelKm } == true

    /** [rangeKm] rounded down to the step. */
    val steppedRangeKm: Long? get() = rangeKm?.let { (floor(it / stepKm) * stepKm).toLong() }

    /** 12b: the one number the rider sees, everywhere. */
    val cautiousRangeKm: Long? get() = if (rangeKm == null) null else heldKm ?: steppedRangeKm

    /** Upper bound between readings: untracked km only show up at the next reading. */
    val rangeKm: Double? get() = litres?.let { it * kmPerL * loadFactor * caution }
    val fraction: Double? get() = litres?.let { (it / tankLitres).coerceIn(0.0, 1.0) }
    /** Litres to fill up now. */
    val toFillLitres: Double? get() = litres?.let { (tankLitres - it).coerceAtLeast(0.0) }
}

enum class UnknownFuel { NO_FULL_FILL, AWAY }

/** 4.6.1 (rider): the low-fuel warning, in km left, per bike. */
const val DEFAULT_LOW_FUEL_KM = 30
val LOW_FUEL_CHOICES_KM = listOf(20, 30, 40, 50)

/** What a custom warning may be; below 20 km the estimate isn't exact enough to rely on. */
val LOW_FUEL_RANGE_KM = 20..150

/** The range is rounded down to this (4.8; before anything is learned, to [RANGE_GUESS_STEP_KM]). */
const val RANGE_STEP_KM = 2.0
const val RANGE_GUESS_STEP_KM = 5.0

/** 4.8: the range shown rises only by this much or more, so it doesn't flicker between two steps. */
const val RANGE_RISE_KM = 4L

/**
 * 4.8, the range as shown: it falls at once, rises only by [RANGE_RISE_KM] or more, and starts over when something was
 * entered (a fill-up, a gauge reading, the bike's settings: the key). Kept in memory: a new start shows the plain figure.
 */
class RangeHold {
    private class Shown(val key: Any, val km: Long)

    private val shown = HashMap<String, Shown>()

    @Synchronized
    fun show(bikeId: String, key: Any, km: Long?): Long? {
        if (km == null) {
            shown.remove(bikeId)
            return null
        }
        val before = shown[bikeId]
        if (before != null && before.key == key && km >= before.km && km < before.km + RANGE_RISE_KM) return before.km
        shown[bikeId] = Shown(key, km)
        return km
    }
}

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

// ---- Refuel entry rules (13a) --------------------------------------------------------------

/** An entry this much (L) over the estimated room in the tank is still taken as "to the top": the estimate isn't that exact. */
const val OVERFLOW_TOLERANCE_L = 0.2

/**
 * 13a: the litres field runs from 0 to the tank's size, never limited by the estimate (which can be
 * wrong; the pump is the truth).
 */
fun refuelLitresFit(litres: Double, tankLitres: Double): Boolean = litres > 0 && litres <= tankLitres

/**
 * 13a: whether a fill-up counts as a full-tank anchor. The rider's "Full refill" switch, or an
 * entry that doesn't fit in the room the estimate says is left ([spaceLeftL], null when unknown): the
 * tank must have been fuller than thought, so after it the tank is full (the level is capped at the
 * tank). The ±35% outlier rule in [learnConsumption] then decides whether that span teaches km/L.
 */
fun countsAsFull(filledToTop: Boolean, litres: Double, spaceLeftL: Double?): Boolean =
    filledToTop || (spaceLeftL != null && litres > spaceLeftL + OVERFLOW_TOLERANCE_L)

// ---- Fill-up review (4.8) ------------------------------------------------------------------

/** What looks wrong about a fill-up, before it is saved. [learning]: it would teach km/L something wrong. */
data class RefuelIssue(val text: String, val learning: Boolean)

/** A tank this far (±) from the usual km/L is asked about; past [KMPL_OUTLIER] it isn't learned from anyway. */
const val REVIEW_KMPL_OFF = 0.25

/** A price this far (±) from the fuel's last one. */
const val REVIEW_PRICE_OFF = 0.3

/** An odometer this far (km) from the estimate. */
const val REVIEW_ODO_KM = 100.0

/** A partial this close (L) to the room left looks like a full refill with the switch off. */
const val REVIEW_LOOKS_FULL_L = 0.3

/**
 * 4.8: what is unusual about [entry] (a new fill-up with id 0, or an edited one among [refuels] by its id), in plain
 * words; empty for a normal one, which is saved without a question. [roomL] and [estimateKm] (new entries): the
 * estimated room in the tank and odometer. [knownPrice]: the fuel's price, 0 when there is none yet.
 */
fun reviewRefuel(
    entry: RefuelRecord,
    refuels: List<RefuelRecord>,
    startKmPerL: Double,
    awayTimesMs: List<Long>,
    dash: List<DashKmPerL>,
    tankLitres: Double,
    roomL: Double?,
    estimateKm: Double?,
    knownPrice: Double,
): List<RefuelIssue> {
    val issues = ArrayList<RefuelIssue>()
    val others = refuels.filterNot { it.id == entry.id }
    val all = chronological(others + entry)
    val at = all.indexOfFirst { it.id == entry.id }
    val before = all.getOrNull(at - 1)
    val after = all.getOrNull(at + 1)
    when {
        before != null && entry.odometerKm < before.odometerKm ->
            issues += RefuelIssue("The odometer is below the fill-up before it (${formatOdometer(before.odometerKm)}).", true)
        after != null && entry.odometerKm > after.odometerKm ->
            issues += RefuelIssue("The odometer is above the next fill-up (${formatOdometer(after.odometerKm)}).", true)
        estimateKm != null && abs(entry.odometerKm - estimateKm) > REVIEW_ODO_KM ->
            issues += RefuelIssue("The odometer is ${formatOdometer(abs(entry.odometerKm - estimateKm))} from the estimate.", true)
        else -> {
            // Not learning from this entry: what the bike usually does. With it: the tank that ends here, and the one that starts here.
            val usual = learnConsumption(startKmPerL, others + entry.copy(learn = false), awayTimesMs, dash)
            val nextFull = all.drop(at + 1).firstOrNull { it.full }
            learnConsumption(startKmPerL, all, awayTimesMs, dash).measures
                .filter { it.endRefuelId == entry.id || (nextFull != null && nextFull.learn && it.endRefuelId == nextFull.id) }
                .firstOrNull { it.kmPerL !in KMPL_PLAUSIBLE || (usual.learned && abs(it.kmPerL / usual.kmPerL - 1) > REVIEW_KMPL_OFF) }
                ?.let { m ->
                    val which = if (m.endRefuelId == entry.id) "This tank" else "The next tank"
                    issues += RefuelIssue("$which comes to ${formatKmPerL(m.kmPerL)}; usually ${formatKmPerL(usual.kmPerL)}.", true)
                }
            if (issues.isEmpty() && roomL != null) {
                if (entry.litres > roomL + maxOf(1.0, 0.15 * tankLitres)) {
                    issues += RefuelIssue("More than the estimated room in the tank (${formatLitres(roomL)}).", true)
                } else if (!entry.full && entry.litres >= roomL - REVIEW_LOOKS_FULL_L) {
                    issues += RefuelIssue("Enough to fill the tank, but Full refill is off.", false)
                }
            }
        }
    }
    if (knownPrice > 0 && abs(entry.pricePerL / knownPrice - 1) > REVIEW_PRICE_OFF) {
        issues += RefuelIssue("${formatRupiah(entry.pricePerL)} per liter; last time ${formatRupiah(knownPrice)}.", false)
    }
    return issues
}
