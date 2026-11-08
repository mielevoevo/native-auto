package com.nativeauto.location

import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.TripState
import com.nativeauto.ui.speed.SpeedometerUiState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class TripNotificationTextTest {

    private val live = SpeedometerUiState.Empty.copy(
        speed = 100, elapsed = 12.minutes + 5.seconds, distance = 14.25, gps = GpsStatus.Fix, tripState = TripState.Running,
    )

    @Test fun `shows speed in the title and elapsed, distance and GPS in the text`() {
        assertEquals("100 km/h", live.notificationTitle())
        assertEquals("12:05 · 14.3 km · GPS Locked", live.notificationText())
    }

    @Test fun `paused says so, with just time and distance`() {
        val paused = live.copy(speed = null, tripState = TripState.Paused)
        assertEquals("Trip paused", paused.notificationTitle())
        assertEquals("12:05 · 14.3 km", paused.notificationText())
    }

    @Test fun `no live fix reads as dashes, in the chosen unit`() {
        val lost = live.copy(speed = null, gps = GpsStatus.Stale, unit = SpeedUnit.MPH, distance = 8.86)
        assertEquals("-- mph", lost.notificationTitle())
        assertEquals("12:05 · 8.9 mi · GPS Lost", lost.notificationText())
    }
}
