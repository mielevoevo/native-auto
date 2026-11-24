package com.nativeauto.garage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Service reminders (11c, Rider-Feedback §6 "Service reminders"), pure maths so it's unit-tested.
 *
 * Intervals are the rider's own (decided 2026-11-08: no Yamaha presets, bikes get modified). An
 * item is due by km and/or months, whichever comes first, counted from when it was last done
 * (or added). Date-only items (vehicle tax, insurance, licence) have a due date instead, moved on
 * by their interval when marked done.
 */
enum class ServiceKind {
    /** Due every [ServiceItem.intervalKm] and/or [ServiceItem.intervalMonths]. */
    DISTANCE,
    /** Due on [ServiceItem.dueDateMs]; marking it done moves that on by [ServiceItem.intervalMonths]. */
    DATE,
}

@Entity(tableName = "service_items", indices = [Index("bikeId")])
data class ServiceItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bikeId: String,
    val name: String,
    /** [ServiceKind] name. */
    val kind: String,
    val intervalKm: Double? = null,
    val intervalMonths: Int? = null,
    /** DATE only: the next due date. */
    val dueDateMs: Long? = null,
    /** DISTANCE: when it was last done (or added). */
    val lastDoneMs: Long,
    /** DISTANCE: the odometer then; null if the odometer wasn't set. */
    val lastDoneKm: Double? = null,
    val createdAtMs: Long,
) {
    val serviceKind: ServiceKind get() = ServiceKind.entries.firstOrNull { it.name == kind } ?: ServiceKind.DISTANCE
}

/** One "Mark done": the service history. [itemName] is kept so the history reads well after a rename or delete. */
@Entity(tableName = "service_records", indices = [Index("bikeId"), Index("itemId")])
data class ServiceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val bikeId: String,
    val itemName: String,
    val timeMs: Long,
    val odometerKm: Double? = null,
    val costRp: Double? = null,
    val note: String? = null,
)

/** Names offered when adding an item: the rider sets the intervals. */
val SERVICE_NAMES = listOf(
    "Engine oil", "Gear oil", "CVT belt", "CVT rollers", "Tire pressure", "Front tire", "Rear tire",
    "Brake pads", "Brake fluid", "Air filter", "Spark plug", "Coolant",
)

/** Date-only reminders, each with its usual interval in months. */
val DATE_NAMES = listOf("Vehicle tax" to 12, "Insurance" to 12, "Licence" to 60)

enum class DueLevel { OK, SOON, OVERDUE, NO_INTERVAL }

/** An item's state now. Negative values are overdue. */
data class ServiceDue(
    val item: ServiceItem,
    val dueInKm: Double?,
    val dueInDays: Long?,
    val level: DueLevel,
    /** 0 = due now, 1 = just done; how far through its interval (for "most urgent first"). */
    val remaining: Double,
)

/** Amber within this share of the km interval (but at least [SOON_MIN_KM]). */
const val SOON_FRACTION = 0.1
const val SOON_MIN_KM = 100.0

/** Amber within this many days of a time limit (date items: [SOON_DATE_DAYS]). */
const val SOON_DAYS = 14L
const val SOON_DATE_DAYS = 30L

fun serviceDue(item: ServiceItem, odometerNowKm: Double?, nowMs: Long, zone: ZoneId): ServiceDue {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    fun daysUntil(ms: Long) = ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    return when (item.serviceKind) {
        ServiceKind.DATE -> {
            val due = item.dueDateMs ?: return ServiceDue(item, null, null, DueLevel.NO_INTERVAL, 1.0)
            val days = daysUntil(due)
            val level = when {
                days < 0 -> DueLevel.OVERDUE
                days <= SOON_DATE_DAYS -> DueLevel.SOON
                else -> DueLevel.OK
            }
            val span = (item.intervalMonths ?: 12) * 30.0
            ServiceDue(item, null, days, level, (days / span).coerceIn(-1.0, 1.0))
        }
        ServiceKind.DISTANCE -> {
            val km = item.intervalKm?.takeIf { it > 0 }
            val months = item.intervalMonths?.takeIf { it > 0 }
            if (km == null && months == null) return ServiceDue(item, null, null, DueLevel.NO_INTERVAL, 1.0)
            val dueKm = if (km != null && item.lastDoneKm != null && odometerNowKm != null) {
                item.lastDoneKm + km - odometerNowKm
            } else {
                null
            }
            val dueDays = months?.let {
                val due = Instant.ofEpochMilli(item.lastDoneMs).atZone(zone).toLocalDate().plusMonths(it.toLong())
                ChronoUnit.DAYS.between(today, due)
            }
            val overdue = (dueKm != null && dueKm <= 0) || (dueDays != null && dueDays < 0)
            val soon = (dueKm != null && km != null && dueKm <= maxOf(km * SOON_FRACTION, SOON_MIN_KM)) ||
                (dueDays != null && dueDays <= SOON_DAYS)
            val level = when {
                overdue -> DueLevel.OVERDUE
                soon -> DueLevel.SOON
                else -> DueLevel.OK
            }
            val byKm = if (dueKm != null && km != null) dueKm / km else 1.0
            val byDays = if (dueDays != null && months != null) dueDays / (months * 30.0) else 1.0
            ServiceDue(item, dueKm, dueDays, level, minOf(byKm, byDays).coerceIn(-1.0, 1.0))
        }
    }
}

/** Overdue first, then due soon, then the rest; within each, the nearest first. Items without an interval last. */
fun byUrgency(dues: List<ServiceDue>): List<ServiceDue> =
    dues.sortedWith(
        compareBy<ServiceDue>({
            when (it.level) {
                DueLevel.OVERDUE -> 0
                DueLevel.SOON -> 1
                DueLevel.OK -> 2
                DueLevel.NO_INTERVAL -> 3
            }
        }, { it.remaining }),
    )

/** The reminder shown at trip start and end (§6 "When reminders appear"): only when something is due soon or overdue. */
fun reminderOf(dues: List<ServiceDue>): ServiceDue? =
    byUrgency(dues).firstOrNull { it.level == DueLevel.SOON || it.level == DueLevel.OVERDUE }

/**
 * "due in 120 km", "overdue by 50 km", "due in 12 days", "due today", "overdue by 3 days".
 * Whichever limit comes first speaks.
 */
fun dueText(d: ServiceDue): String {
    val km = d.dueInKm
    val days = d.dueInDays
    val kmFirst = when {
        km == null -> false
        days == null -> true
        d.item.intervalKm != null && d.item.intervalMonths != null ->
            km / d.item.intervalKm <= days / (d.item.intervalMonths * 30.0)
        else -> true
    }
    return when {
        d.level == DueLevel.NO_INTERVAL -> "no interval set"
        kmFirst && km != null -> {
            val n = abs(km).roundToLong()
            if (km <= 0) "overdue by ${formatKm(n)}" else "due in ${formatKm(n)}"
        }
        days != null -> when {
            days == 0L -> "due today"
            days < 0 -> "overdue by ${dayCount(-days)}"
            else -> "due in ${dayCount(days)}"
        }
        else -> "set the odometer"
    }
}

private fun formatKm(n: Long) = String.format(Locale.US, "%,d km", n)

private fun dayCount(d: Long) = if (d == 1L) "1 day" else "$d days"

/** A date item's next due date after "Mark done": [months] after the old one, and after today. */
fun nextDueDate(dueMs: Long?, monthsIn: Int, nowMs: Long, zone: ZoneId): Long {
    val months = monthsIn.coerceAtLeast(1)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    var due = Instant.ofEpochMilli(dueMs ?: nowMs).atZone(zone).toLocalDate().plusMonths(months.toLong())
    while (!due.isAfter(today)) due = due.plusMonths(months.toLong())
    return due.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** "Engine oil due in 120 km". */
fun reminderText(d: ServiceDue): String = "${d.item.name} ${dueText(d)}"

/**
 * A typed date, day first as Indonesians write it: "31/12/2026", "31-12-2026", "31.12.2026".
 * Start of that day in [zone]; null if it isn't a real date.
 */
fun parseDate(text: String, zone: ZoneId): Long? {
    val m = Regex("""^\s*(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})\s*$""").find(text) ?: return null
    val (d, mo, y) = m.destructured
    return runCatching { java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
}

/** "31/12/2026". */
fun formatDate(ms: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return String.format(Locale.US, "%d/%d/%d", d.dayOfMonth, d.monthValue, d.year)
}

/** "every 2,000 km · 3 months", "yearly · due 31/12/2026". */
fun intervalText(item: ServiceItem, zone: ZoneId): String = when (item.serviceKind) {
    ServiceKind.DATE -> {
        val every = when (val m = item.intervalMonths ?: 12) {
            12 -> "yearly"
            1 -> "monthly"
            else -> if (m % 12 == 0) "every ${m / 12} years" else "every $m months"
        }
        listOfNotNull(every, item.dueDateMs?.let { "due ${formatDate(it, zone)}" }).joinToString(" · ")
    }
    ServiceKind.DISTANCE -> listOfNotNull(
        item.intervalKm?.let { String.format(Locale.US, "%,d km", it.roundToLong()) },
        item.intervalMonths?.let { if (it == 1) "1 month" else "$it months" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "every ") ?: "no interval set"
}
