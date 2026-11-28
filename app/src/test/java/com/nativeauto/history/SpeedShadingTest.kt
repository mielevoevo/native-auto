package com.nativeauto.history

import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.8: the route in shades by speed. */
class SpeedShadingTest {

    /** One fix a second, going north at each of [kmh]. */
    private fun track(kmh: List<Double>): List<TrackPointRecord> {
        var north = 0.0
        return kmh.mapIndexed { i, v ->
            if (i > 0) north += v / 3.6
            TrackPointRecord(tripId = 1, timeMs = i * 1_000L, latitude = north / METERS_PER_DEGREE_LAT, longitude = 0.0, speedMps = (v / 3.6).toFloat(), bearingDeg = null, accuracyM = 4f)
        }
    }

    @Test fun `each stretch gets its step and the line has no gaps`() {
        val bands = speedBands(track(List(60) { 30.0 } + List(60) { 70.0 } + List(60) { 110.0 }), SpeedUnit.KMH)
        assertEquals(1, bands.first().step)
        assertEquals(5, bands.last().step)
        assertTrue(bands.any { it.step == 3 })
        bands.zipWithNext { a, b -> assertEquals(a.points.last(), b.points.first()) }
        // The changes pass through the steps between, once each.
        assertEquals(bands.map { it.step }, bands.map { it.step }.sorted())
    }

    @Test fun `one odd fix doesn't speckle the line`() {
        val kmh = MutableList(120) { 50.0 }.also { it[60] = 95.0 }
        assertEquals(listOf(2), speedBands(track(kmh), SpeedUnit.KMH).map { it.step })
    }

    @Test fun `standing still adds nothing, and the start of each step belongs to it`() {
        val stop = List(60) { 30.0 } + List(120) { 0.0 } + List(60) { 30.0 }
        val bands = speedBands(track(stop), SpeedUnit.KMH)
        assertTrue(bands.sumOf { it.points.size } < 130)
        assertEquals(listOf(1), speedBands(track(List(60) { 20.001 }), SpeedUnit.KMH).map { it.step })
    }

    @Test fun `miles have their own round steps`() {
        // 50 km/h = 31 mph.
        assertEquals(listOf(2), speedBands(track(List(60) { 50.0 }), SpeedUnit.MPH).map { it.step })
        assertEquals(6, speedSteps(SpeedUnit.MPH).size)
    }

    @Test fun `no track, no shades`() {
        assertTrue(speedBands(emptyList(), SpeedUnit.KMH).isEmpty())
        assertTrue(speedBands(track(listOf(10.0)), SpeedUnit.KMH).isEmpty())
        assertTrue(speedBands(track(List(20) { 0.0 }), SpeedUnit.KMH).isEmpty())
    }

    private fun luma(argb: Int) = 0.299 * (argb shr 16 and 255) + 0.587 * (argb shr 8 and 255) + 0.114 * (argb and 255)

    @Test fun `faster is brighter on black and darker on a light map`() {
        listOf(0xFF3FC8E0.toInt(), 0xFF2BD576.toInt(), 0xFFB18CFF.toInt(), 0xFFFF6BD6.toInt()).forEach { accent ->
            val dark = speedShades(accent, true).map(::luma)
            assertEquals(6, dark.size)
            dark.zipWithNext { a, b -> assertTrue("$dark", b - a > 8) }
            val light = speedShades(accent, false).map(::luma)
            light.zipWithNext { a, b -> assertTrue("$light", a - b > 8) }
        }
    }
}
