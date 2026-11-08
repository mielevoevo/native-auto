package com.nativeauto.ui

import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.ui.media.formatPlaybackTime
import com.nativeauto.ui.speed.SpeedometerUiState
import com.nativeauto.ui.speed.formatElapsed
import com.nativeauto.ui.speed.label
import com.nativeauto.ui.speed.telemetry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardFormattingTest {

    @Test fun `elapsed uses M_SS under an hour`() {
        assertEquals("0:00", formatElapsed(Duration.ZERO))
        assertEquals("0:09", formatElapsed(9.seconds))
        assertEquals("42:17", formatElapsed(42.minutes + 17.seconds))
    }

    @Test fun `elapsed switches to H_MM_SS past an hour`() {
        assertEquals("1:00:00", formatElapsed(1.hours))
        assertEquals("2:03:04", formatElapsed(2.hours + 3.minutes + 4.seconds))
    }

    @Test fun `elapsed never goes negative`() {
        assertEquals("0:00", formatElapsed((-5).seconds))
    }

    @Test fun `playback time formats milliseconds`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:23", formatPlaybackTime(83_999))
        assertEquals("4:01", formatPlaybackTime(241_000))
        assertEquals("1:00:01", formatPlaybackTime(3_601_000))
        assertEquals("0:00", formatPlaybackTime(-1))
    }

    @Test fun `every GPS label fits the narrow Expanded tile`() {
        // "Searching" clipped to "Searchir" on the emulator; 6 characters is what fits.
        GpsStatus.entries.forEach { assertTrue("${it.name} → '${it.label}'", it.label.length <= 6) }
    }

    @Test fun `telemetry grid lists all six stats in unit`() {
        val ui = SpeedometerUiState.Empty.copy(maxSpeed = 70, unit = SpeedUnit.MPH, gps = GpsStatus.Stale)
        val stats = ui.telemetry()
        assertEquals(listOf("MAX", "AVERAGE", "MOVING AVG", "ELAPSED", "DISTANCE", "GPS"), stats.map { it.label })
        assertEquals("70", stats[0].value)
        assertEquals("mph", stats[0].unit)
        assertEquals("mi", stats[4].unit)
        assertEquals("Lost", stats[5].value)
    }
}
