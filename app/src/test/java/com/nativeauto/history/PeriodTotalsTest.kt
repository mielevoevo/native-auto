package com.nativeauto.history

import com.nativeauto.settings.TotalPeriod
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 12b (R2): the log face's day / week / month total. */
class PeriodTotalsTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun trip(start: Long, m: Double) = TripRecord(
        startedAtMs = start, endedAtMs = start + 600_000, durationMs = 600_000, movingMs = 600_000, distanceM = m,
        maxSpeedMps = 10f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "",
    )

    // Wednesday 2026-11-11.
    private val now = ms(2026, 11, 11, 18)

    @Test fun `period starts`() {
        assertEquals(ms(2026, 11, 11, 0), periodStartMs(TotalPeriod.DAY, now, zone))
        assertEquals(ms(2026, 11, 9, 0), periodStartMs(TotalPeriod.WEEK, now, zone))
        assertEquals(ms(2026, 11, 1, 0), periodStartMs(TotalPeriod.MONTH, now, zone))
        // A Monday's week starts that day.
        assertEquals(ms(2026, 11, 9, 0), periodStartMs(TotalPeriod.WEEK, ms(2026, 11, 9, 7), zone))
    }

    @Test fun `trips count for the period they started in`() {
        val trips = listOf(
            trip(ms(2026, 11, 11, 8), 10_000.0),
            trip(ms(2026, 11, 10, 23), 5_000.0),
            trip(ms(2026, 11, 8), 7_000.0),
            trip(ms(2026, 10, 31), 3_000.0),
        )
        assertEquals(10_000.0, periodDistanceM(trips, TotalPeriod.DAY, now, zone), 0.0)
        assertEquals(15_000.0, periodDistanceM(trips, TotalPeriod.WEEK, now, zone), 0.0)
        assertEquals(22_000.0, periodDistanceM(trips, TotalPeriod.MONTH, now, zone), 0.0)
    }
}
