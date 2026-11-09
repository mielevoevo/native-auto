package com.nativeauto.garage

import com.nativeauto.nav.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerTest {

    private fun reading(km: Double, timeMs: Long = 1_000, offsetM: Double = 0.0) =
        OdometerReading(bikeId = "b", timeMs = timeMs, km = km, kind = ReadingKind.SET.name, tripOffsetM = offsetM)

    @Test fun `no reading means no estimate`() {
        assertNull(estimateOdometerKm(null, 1.0, 5_000.0))
    }

    @Test fun `estimate is the reading plus GPS km times the factor`() {
        assertEquals(12_010.0, estimateOdometerKm(reading(12_000.0), 1.0, 10_000.0)!!, 1e-9)
        assertEquals(12_010.3, estimateOdometerKm(reading(12_000.0), 1.03, 10_000.0)!!, 1e-9)
    }

    @Test fun `a live trip that started after the reading counts in full`() {
        val r = reading(100.0, timeMs = 1_000)
        assertEquals(7_000.0, gpsMetersSince(r, 5_000.0, LiveTrip(startedAtMs = 2_000, distanceM = 2_000.0)), 1e-9)
    }

    @Test fun `a live trip running at the reading counts only past its offset`() {
        val r = reading(100.0, timeMs = 5_000, offsetM = 1_500.0)
        assertEquals(500.0, gpsMetersSince(r, 0.0, LiveTrip(startedAtMs = 1_000, distanceM = 2_000.0)), 1e-9)
        // Never negative, whatever the offset.
        assertEquals(0.0, gpsMetersSince(r, 0.0, LiveTrip(startedAtMs = 1_000, distanceM = 1_000.0)), 1e-9)
        assertEquals(300.0, gpsMetersSince(r, 300.0, null), 1e-9)
    }

    @Test fun `a correction moves the factor halfway to the measured ratio`() {
        // 103 bike km over 100 GPS km: GPS reads 3% short.
        assertEquals(1.015, learnFactor(1.0, prevKm = 1_000.0, newKm = 1_103.0, gpsKm = 100.0), 1e-9)
        assertEquals(1.0225, learnFactor(1.015, prevKm = 1_103.0, newKm = 1_206.0, gpsKm = 100.0), 1e-9)
    }

    @Test fun `short spans and implausible ratios teach nothing`() {
        assertEquals(1.0, learnFactor(1.0, 1_000.0, 1_030.0, gpsKm = 20.0), 0.0)
        // A forgotten reading or a typo: 150 km on the bike for 100 km of GPS.
        assertEquals(1.02, learnFactor(1.02, 1_000.0, 1_150.0, gpsKm = 100.0), 0.0)
        assertEquals(1.02, learnFactor(1.02, 1_000.0, 900.0, gpsKm = 100.0), 0.0)
    }

    @Test fun `the first reading is a set, later ones corrections`() {
        assertEquals(ReadingDecision.Store(ReadingKind.SET), decideReading(12_000.0, null, away = false))
        assertEquals(ReadingDecision.Store(ReadingKind.SET), decideReading(12_000.0, null, away = true))
        assertEquals(ReadingDecision.Store(ReadingKind.CORRECTION), decideReading(12_000.0, 12_000.0, away = false))
        assertEquals(ReadingDecision.Store(ReadingKind.CORRECTION), decideReading(11_900.0, 12_000.0, away = false))
    }

    @Test fun `away km is the rise over the estimate`() {
        assertEquals(ReadingDecision.Store(ReadingKind.AWAY, awayKm = 230.0), decideReading(12_230.0, 12_000.0, away = true))
        // The prefilled value typed back: nobody rode it.
        assertEquals(ReadingDecision.Unchanged, decideReading(12_000.4, 12_000.0, away = true))
        // Lower than the estimate: just a correction.
        assertEquals(ReadingDecision.Store(ReadingKind.CORRECTION), decideReading(11_950.0, 12_000.0, away = true))
    }

    @Test fun `away check after three days unused`() {
        val day = 24 * 60 * 60 * 1000L
        assertFalse(needsAwayCheck(lastUsedAtMs = 0, nowMs = 3 * day - 1))
        assertTrue(needsAwayCheck(lastUsedAtMs = 0, nowMs = 3 * day))
    }

    @Test fun `which bike asks only with two bikes and a far start`() {
        val yk = GeoPoint(-7.7956, 110.3695)
        val home = GeoPoint(-6.2088, 106.8456) // Jakarta, ~430 km
        val near = GeoPoint(-7.80, 110.40) // ~3 km
        assertNull(whichBikeDistanceKm(1, yk, home))
        assertNull(whichBikeDistanceKm(2, null, home))
        assertNull(whichBikeDistanceKm(2, yk, near))
        val km = whichBikeDistanceKm(2, yk, home)!!
        assertTrue("was $km", km in 400.0..470.0)
    }

    @Test fun `numbers typed either way`() {
        assertEquals(6.6, parseNumber("6.6")!!, 0.0)
        assertEquals(6.6, parseNumber("6,6")!!, 0.0)
        assertEquals(12_345.0, parseNumber("12,345", thousands = true)!!, 0.0)
        assertEquals(12.345, parseNumber("12,345")!!, 1e-9) // a decimal comma, Indonesian style
        assertEquals(12_345.6, parseNumber("12.345,6")!!, 1e-9)
        assertEquals(12_345.6, parseNumber("12,345.6")!!, 1e-9)
        assertEquals(1_234_567.0, parseNumber("1.234.567")!!, 0.0)
        // Odometers: an Indonesian "12.345" is twelve thousand.
        assertEquals(12.345, parseNumber("12.345")!!, 1e-9)
        assertEquals(12_345.0, parseNumber("12.345", thousands = true)!!, 0.0)
        assertEquals(12_345.0, parseNumber(" 12345 ", thousands = true)!!, 0.0)
        assertNull(parseNumber(""))
        assertNull(parseNumber("abc"))
        assertNull(parseNumber("-5"))
    }

    @Test fun `odometer format`() {
        assertEquals("12,346 km", formatOdometer(12_345.6))
        assertEquals("0 km", formatOdometer(0.2))
    }
}
