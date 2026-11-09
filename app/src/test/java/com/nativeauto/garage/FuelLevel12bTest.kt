package com.nativeauto.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 12b: the level from the bike's gauge, the dash km/L, and the cautious range. */
class FuelLevel12bTest {

    private var nextId = 1L

    private fun fill(odo: Double, litres: Double, full: Boolean = true, timeMs: Long = odo.toLong() * 1_000) =
        RefuelRecord(
            id = nextId++, bikeId = "b", timeMs = timeMs, odometerKm = odo, litres = litres, pricePerL = 12_500.0,
            totalRp = litres * 12_500, full = full, fuelType = FuelType.PERTAMAX.name,
        )

    private fun mark(odo: Double, bars: Int, of: Int, tank: Double = 6.6, timeMs: Long = odo.toLong() * 1_000) =
        FuelMark(id = nextId++, bikeId = "b", timeMs = timeMs, odometerKm = odo, bars = bars, ofBars = of, litres = gaugeLitres(bars, of, tank))

    private fun dash(kmpl: Double, timeMs: Long) = DashKmPerL(id = nextId++, bikeId = "b", timeMs = timeMs, kmPerL = kmpl, tripKm = 20.0)

    @Test fun `gauge bars by model`() {
        assertEquals(6, gaugeBars(BikeModel.NMAX_GEN1))
        assertEquals(8, gaugeBars(BikeModel.NMAX_GEN2))
    }

    @Test fun `the last lit bar counts as half used`() {
        assertEquals(0.0, gaugeLitres(0, 6, 6.6), 0.0)
        assertEquals(2.5 / 6 * 6.6, gaugeLitres(3, 6, 6.6), 1e-9)
        assertEquals(7.5 / 8 * 7.1, gaugeLitres(8, 8, 7.1), 1e-9)
    }

    @Test fun `a gauge reading gives a level without any full fill`() {
        val l = fuelLevel(6.6, 36.0, emptyList(), emptyList(), 1_036.0, listOf(mark(1_000.0, 3, 6)))
        assertTrue(l.fromGauge)
        assertEquals(2.75 - 1.0, l.litres!!, 1e-9)
        assertNull(l.unknownBecause)
    }

    @Test fun `a later full fill replaces the gauge reading, a later reading replaces the fill`() {
        val fills = listOf(fill(1_100.0, 5.0))
        val older = fuelLevel(6.6, 36.0, fills, emptyList(), 1_100.0, listOf(mark(1_000.0, 3, 6)))
        assertFalse(older.fromGauge)
        assertEquals(6.6, older.litres!!, 1e-9)
        val newer = fuelLevel(6.6, 36.0, fills, emptyList(), 1_236.0, listOf(mark(1_200.0, 2, 6)))
        assertTrue(newer.fromGauge)
        assertEquals(1.5 / 6 * 6.6 - 1.0, newer.litres!!, 1e-9)
    }

    @Test fun `partials after a gauge reading add up, away km after it make it unknown`() {
        val m = mark(1_000.0, 2, 6)
        val l = fuelLevel(6.6, 36.0, listOf(fill(1_036.0, 2.0, full = false)), emptyList(), 1_036.0, listOf(m))
        assertEquals(1.65 - 1.0 + 2.0, l.litres!!, 1e-9)
        val away = fuelLevel(6.6, 36.0, emptyList(), listOf(m.timeMs + 1), 1_036.0, listOf(m))
        assertEquals(UnknownFuel.AWAY, away.unknownBecause)
    }

    @Test fun `range rounds down to 5 km`() {
        val l = FuelLevel(litres = 3.9, tankLitres = 6.6, kmPerL = 36.0, sinceRefuelKm = null)
        assertEquals(140.4, l.rangeKm!!, 1e-9)
        assertEquals(140L, l.cautiousRangeKm)
        assertEquals(135L, l.copy(litres = 3.88).cautiousRangeKm)
    }

    @Test fun `dash figures nudge the estimate, implausible ones are ignored`() {
        val c = learnConsumption(36.0, emptyList(), emptyList(), listOf(dash(46.0, 1), dash(5.0, 2)))
        assertEquals(36.0 + DASH_WEIGHT * 10, c.kmPerL, 1e-9)
        assertEquals(1, c.dashUsed)
        assertTrue(c.learned)
        // Once learning has started, a wild figure is an outlier.
        val wild = learnConsumption(36.0, emptyList(), emptyList(), listOf(dash(40.0, 1), dash(80.0, 2)))
        assertEquals(1, wild.dashUsed)
    }

    @Test fun `the cautious figure is the lower of the long run and the recent tanks`() {
        // Tanks: 40, then 30 km/L. Long run ends between; the recent mean (35) or the estimate, whichever is lower.
        val fills = listOf(fill(1_000.0, 5.0), fill(1_200.0, 5.0), fill(1_350.0, 5.0))
        val c = learnConsumption(36.0, fills, emptyList())
        assertEquals(minOf(c.kmPerL, 35.0), c.cautiousKmPerL, 1e-9)
        assertTrue(c.cautiousKmPerL <= c.kmPerL)
        // Nothing learned: the starting value.
        assertEquals(36.0, learnConsumption(36.0, emptyList(), emptyList()).cautiousKmPerL, 0.0)
    }
}
