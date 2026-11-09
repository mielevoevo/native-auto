package com.nativeauto.ui.history

import com.nativeauto.history.TripRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of history (Rider-Feedback §5): its header and the day's total distance. */
data class DayGroup(val day: LocalDate, val label: String, val totalDistanceM: Double, val trips: List<TripRecord>)

/**
 * Trips (newest first) grouped by the local day they started: "Today", "Yesterday", then
 * "Mon 3 Nov" (with the year once it isn't this year's).
 */
fun groupByDay(trips: List<TripRecord>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<DayGroup> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return trips
        .groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, list) -> DayGroup(day, dayLabel(day, today), list.sumOf { it.distanceM }, list.sortedByDescending { it.startedAtMs }) }
}

internal fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.ENGLISH).format(day)
}
