package com.nativeauto.ui.clock

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ClockFormatTest {

    private val jakarta = ZoneId.of("Asia/Jakarta")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 8, h, m, 30, 0, jakarta).toInstant().toEpochMilli()

    @Test fun `24-hour`() {
        assertEquals(ClockTime("00:05", null), formatClockTime(at(0, 5), jakarta, is24 = true))
        assertEquals(ClockTime("14:30", null), formatClockTime(at(14, 30), jakarta, is24 = true))
    }

    @Test fun `12-hour with AM and PM`() {
        assertEquals(ClockTime("12:05", "AM"), formatClockTime(at(0, 5), jakarta, is24 = false))
        assertEquals(ClockTime("9:41", "AM"), formatClockTime(at(9, 41), jakarta, is24 = false))
        assertEquals(ClockTime("12:30", "PM"), formatClockTime(at(12, 30), jakarta, is24 = false))
        assertEquals(ClockTime("11:59", "PM"), formatClockTime(at(23, 59), jakarta, is24 = false))
    }

    @Test fun `the zone decides the hour`() {
        assertEquals(ClockTime("07:30", null), formatClockTime(at(14, 30), ZoneId.of("UTC"), is24 = true))
    }

    @Test fun `short date`() {
        assertEquals("Sun 8 Nov", formatClockDate(at(10, 0), jakarta, Locale.UK))
    }
}
