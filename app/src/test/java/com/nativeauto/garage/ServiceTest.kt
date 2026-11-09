package com.nativeauto.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 11c: service items due by km and/or months, date items, reminders and their text. */
class ServiceTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L
    private val now = day(2026, 11, 9)

    private fun oil(km: Double? = 2_000.0, months: Int? = 3, lastKm: Double? = 10_000.0, lastMs: Long = day(2026, 10, 1)) = ServiceItem(
        id = 1, bikeId = "b", name = "Engine oil", kind = ServiceKind.DISTANCE.name, intervalKm = km, intervalMonths = months,
        lastDoneMs = lastMs, lastDoneKm = lastKm, createdAtMs = 0,
    )

    private fun tax(due: Long) = ServiceItem(
        id = 2, bikeId = "b", name = "Vehicle tax", kind = ServiceKind.DATE.name, intervalMonths = 12, dueDateMs = due,
        lastDoneMs = 0, createdAtMs = 0,
    )

    @Test fun `km and months, whichever comes first`() {
        val d = serviceDue(oil(), odometerNowKm = 11_000.0, nowMs = now, zone = zone)
        assertEquals(1_000.0, d.dueInKm!!, 1e-9)
        // 1 Oct + 3 months = 1 Jan: 53 days from 9 Nov.
        assertEquals(53L, d.dueInDays)
        assertEquals(DueLevel.OK, d.level)
        assertEquals("due in 1,000 km", dueText(d))
        // Months first: last done in August.
        val byTime = serviceDue(oil(lastMs = day(2026, 8, 20)), 10_200.0, now, zone)
        assertEquals(DueLevel.SOON, byTime.level) // 20 Nov: 11 days
        assertEquals("due in 11 days", dueText(byTime))
    }

    @Test fun `amber within a tenth of the km interval, red past it`() {
        assertEquals(DueLevel.SOON, serviceDue(oil(months = null), 11_850.0, now, zone).level)
        val over = serviceDue(oil(months = null), 12_050.0, now, zone)
        assertEquals(DueLevel.OVERDUE, over.level)
        assertEquals("overdue by 50 km", dueText(over))
        assertEquals("Engine oil overdue by 50 km", reminderText(over))
        // A short interval still warns at least 100 km ahead.
        assertEquals(DueLevel.SOON, serviceDue(oil(km = 500.0, months = null), 10_420.0, now, zone).level)
    }

    @Test fun `no interval, or no odometer`() {
        assertEquals(DueLevel.NO_INTERVAL, serviceDue(oil(km = null, months = null), 11_000.0, now, zone).level)
        val noOdo = serviceDue(oil(lastKm = null), null, now, zone)
        assertNull(noOdo.dueInKm)
        assertEquals(53L, noOdo.dueInDays) // months still count
    }

    @Test fun `date items`() {
        assertEquals(DueLevel.OK, serviceDue(tax(day(2027, 3, 1)), null, now, zone).level)
        val soon = serviceDue(tax(day(2026, 11, 21)), null, now, zone)
        assertEquals(DueLevel.SOON, soon.level)
        assertEquals("due in 12 days", dueText(soon))
        assertEquals("due today", dueText(serviceDue(tax(day(2026, 11, 9)), null, now, zone)))
        val late = serviceDue(tax(day(2026, 11, 8)), null, now, zone)
        assertEquals(DueLevel.OVERDUE, late.level)
        assertEquals("overdue by 1 day", dueText(late))
    }

    @Test fun `the reminder is the most urgent item due soon or overdue`() {
        val ok = serviceDue(oil(), 11_000.0, now, zone)
        val soonTax = serviceDue(tax(day(2026, 11, 30)), null, now, zone)
        val overdue = serviceDue(oil(months = null).copy(id = 3, name = "CVT belt"), 12_100.0, now, zone)
        assertEquals(listOf(overdue, soonTax, ok), byUrgency(listOf(ok, soonTax, overdue)))
        assertEquals(overdue, reminderOf(listOf(ok, soonTax, overdue)))
        assertNull(reminderOf(listOf(ok)))
    }

    @Test fun `a paid date item moves on by its interval, past today`() {
        val next = nextDueDate(day(2026, 11, 20), 12, now, zone)
        assertEquals(LocalDate.of(2027, 11, 20), java.time.Instant.ofEpochMilli(next).atZone(zone).toLocalDate())
        // Paid more than a year late: still lands in the future.
        val late = nextDueDate(day(2024, 6, 1), 12, now, zone)
        assertEquals(LocalDate.of(2027, 6, 1), java.time.Instant.ofEpochMilli(late).atZone(zone).toLocalDate())
    }

    @Test fun `dates are typed day first`() {
        val ms = parseDate("31/12/2026", zone)!!
        assertEquals("31/12/2026", formatDate(ms, zone))
        assertEquals(ms, parseDate(" 31-12-2026 ", zone))
        assertEquals(ms, parseDate("31.12.2026", zone))
        assertNull(parseDate("31/02/2026", zone))
        assertNull(parseDate("2026-12-31", zone))
    }

    @Test fun `interval text`() {
        assertEquals("every 2,000 km · 3 months", intervalText(oil(), zone))
        assertEquals("every 2,000 km", intervalText(oil(months = null), zone))
        assertEquals("no interval set", intervalText(oil(km = null, months = null), zone))
        assertEquals("yearly · due 1/3/2027", intervalText(tax(day(2027, 3, 1)), zone))
    }
}
