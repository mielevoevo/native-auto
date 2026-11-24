package com.nativeauto.ui.history

import com.nativeauto.history.TripRecord
import com.nativeauto.settings.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 4.7: the trip page's traffic line. */
class TrafficLineTest {
    private val trip = TripRecord(
        startedAtMs = 0, endedAtMs = 1, durationMs = 1_800_000, movingMs = 1_550_000, distanceM = 9_000.0, maxSpeedMps = 15f,
        startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "", stoppedMs = 250_000,
    )

    @Test fun `trips from before it was recorded have none`() = assertNull(trafficLine(trip, SpeedUnit.KMH))

    @Test fun `standing, crawling and stops`() {
        assertEquals(
            "Stopped 4 min · 14 stops\nCrawling 1.2 km in 6 min",
            trafficLine(trip.copy(loadCrawlS = 380.0, loadCrawlM = 1_240.0, loadCrawlClimbM = 3.0, loadStops = 14), SpeedUnit.KMH),
        )
        assertEquals(
            "Stopped 0 s · 1 stop\nCrawling 0.0 km in 0 s",
            trafficLine(trip.copy(stoppedMs = 0, loadCrawlS = 0.0, loadCrawlM = 0.0, loadStops = 1), SpeedUnit.KMH),
        )
    }
}
