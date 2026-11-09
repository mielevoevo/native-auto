package com.nativeauto.history

import com.nativeauto.settings.TotalPeriod
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 12b (R2): the log face's "Today 42 km" line, from the saved trips, so it matches the history.
 * A trip counts for the period it started in.
 */
fun periodStartMs(period: TotalPeriod, nowMs: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val start = when (period) {
        TotalPeriod.DAY -> today
        TotalPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        TotalPeriod.MONTH -> today.withDayOfMonth(1)
    }
    return start.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Metres of the trips that started in [period] up to [nowMs]. */
fun periodDistanceM(trips: List<TripRecord>, period: TotalPeriod, nowMs: Long, zone: ZoneId): Double {
    val from = periodStartMs(period, nowMs, zone)
    return trips.filter { it.startedAtMs in from..nowMs }.sumOf { it.distanceM }
}
