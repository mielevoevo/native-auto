package com.nativeauto.ui.history

import com.nativeauto.history.TripRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DayGroupsTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun trip(id: Long, startMs: Long, km: Double) = TripRecord(
        id = id, startedAtMs = startMs, endedAtMs = startMs + 1, durationMs = 1, movingMs = 1, distanceM = km * 1000,
        maxSpeedMps = 1f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "",
    )

    @Test fun `trips group by local day with labels and totals, newest first`() {
        val now = at(2026, 11, 9, 10)
        val trips = listOf(
            trip(1, at(2026, 11, 9, 8), 10.0),
            trip(2, at(2026, 11, 9, 0), 2.5), // just after midnight local: still today
            trip(3, at(2026, 11, 8, 23), 4.0),
            trip(4, at(2026, 11, 3, 7), 1.0),
            trip(5, at(2025, 12, 30, 7), 3.0),
        )
        val groups = groupByDay(trips, now, zone)
        assertEquals(listOf("Today", "Yesterday", "Tue 3 Nov", "Tue 30 Dec 2025"), groups.map { it.label })
        assertEquals(12_500.0, groups[0].totalDistanceM, 1e-9)
        assertEquals(listOf(1L, 2L), groups[0].trips.map { it.id })
        assertEquals(LocalDate.of(2026, 11, 8), groups[1].day)
    }
}
