package com.nativeauto.garage

import kotlin.math.abs

/**
 * Odometer maths (11a), pure so it's unit-tested on the JVM.
 *
 * The rider enters the bike's real reading once; after that the estimate is the last reading
 * plus the GPS distance of the bike's trips since, times [BikeRecord.odoFactor]. GPS drifts a
 * few percent from the bike's own odometer, so each correction teaches that factor.
 */

/** The trip in progress, for the active bike's live odometer. */
data class LiveTrip(val startedAtMs: Long, val distanceM: Double)

/**
 * Raw GPS metres since [reading]: [savedM] from the saved trips (`GarageDao.metersSince`) plus
 * the [live] trip, which only counts past the reading's offset if it was already running then.
 */
fun gpsMetersSince(reading: OdometerReading, savedM: Double, live: LiveTrip?): Double {
    val liveM = when {
        live == null -> 0.0
        live.startedAtMs < reading.timeMs -> (live.distanceM - reading.tripOffsetM).coerceAtLeast(0.0)
        else -> live.distanceM
    }
    return savedM + liveM
}

/** The odometer now, or null before the first reading. */
fun estimateOdometerKm(reading: OdometerReading?, factor: Double, gpsMetersSince: Double): Double? =
    reading?.let { it.km + factor * gpsMetersSince / 1000.0 }

/** A correction teaches the factor only over this much GPS distance (short spans are noise). */
const val MIN_LEARN_KM = 30.0

/**
 * Factors outside this band are a typo, a forgotten reading or an untracked ride, not GPS drift.
 * 11b narrowed it from 0.8–1.2: a ride without the app that goes unlogged would otherwise be
 * learned as drift (Rider-Feedback, "riding without the app").
 */
val FACTOR_RANGE = 0.9..1.1

/** How far each correction moves the factor: recent corrections count more. */
const val LEARN_WEIGHT = 0.5

/**
 * The factor after a correction to [newKm], [gpsKm] GPS km after the reading of [prevKm].
 * Unchanged when the span is too short or the measured ratio is implausible.
 */
fun learnFactor(old: Double, prevKm: Double, newKm: Double, gpsKm: Double): Double {
    if (gpsKm < MIN_LEARN_KM) return old
    val measured = (newKm - prevKm) / gpsKm
    if (measured !in FACTOR_RANGE) return old
    return (old + LEARN_WEIGHT * (measured - old)).coerceIn(FACTOR_RANGE)
}

/** What an entered reading becomes. */
sealed interface ReadingDecision {
    data class Store(val kind: ReadingKind, val awayKm: Double = 0.0) : ReadingDecision
    /** The away check found the odometer unchanged: nobody rode it, nothing to store. */
    data object Unchanged : ReadingDecision
}

/** Below this, an away reading is "the same" (the rider typed the prefilled value back). */
const val AWAY_MIN_KM = 1.0

/**
 * [entered] against the current [estimate] (null: no reading yet). From the away prompt
 * ([away]), a higher reading is away km; a lower one is just a correction.
 */
fun decideReading(entered: Double, estimate: Double?, away: Boolean): ReadingDecision = when {
    estimate == null -> ReadingDecision.Store(ReadingKind.SET)
    away && abs(entered - estimate) < AWAY_MIN_KM -> ReadingDecision.Unchanged
    away && entered > estimate -> ReadingDecision.Store(ReadingKind.AWAY, awayKm = entered - estimate)
    else -> ReadingDecision.Store(ReadingKind.CORRECTION)
}

/** A bike unused this long may have been ridden by someone else: ask for its odometer. */
const val AWAY_AFTER_MS = 3L * 24 * 60 * 60 * 1000

fun needsAwayCheck(lastUsedAtMs: Long, nowMs: Long): Boolean = nowMs - lastUsedAtMs >= AWAY_AFTER_MS

/**
 * Parses a typed number, English or Indonesian style: "6.6", "6,6", "12,345", "12.345,6".
 * With [thousands] (odometers), one separator followed by exactly three digits groups
 * thousands, so "12.345" is 12345 km as an Indonesian rider writes it. Null if it isn't a
 * non-negative number.
 */
fun parseNumber(text: String, thousands: Boolean = false): Double? {
    val t = text.trim().replace(" ", "")
    if (t.isEmpty()) return null
    val lastDot = t.lastIndexOf('.')
    val lastComma = t.lastIndexOf(',')
    fun grouped(sep: Char, last: Int) = t.count { it == sep } > 1 || (thousands && t.length - last - 1 == 3)
    val normal = when {
        // Both: the later one is the decimal mark, the other groups thousands.
        lastDot >= 0 && lastComma >= 0 ->
            if (lastDot > lastComma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
        lastComma >= 0 -> if (grouped(',', lastComma)) t.replace(",", "") else t.replace(',', '.')
        lastDot >= 0 -> if (grouped('.', lastDot)) t.replace(".", "") else t
        else -> t
    }
    return normal.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
}

/** "12,345 km". */
fun formatOdometer(km: Double): String = String.format(java.util.Locale.US, "%,d km", Math.round(km))
