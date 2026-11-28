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
        assertEquals(6, BikeModel.NMAX_GEN1.gaugeBars)
        assertEquals(8, BikeModel.NMAX_GEN2.gaugeBars)
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

    @Test fun `range rounds down to 5 km as a guess and to 2 km once learned`() {
        val l = FuelLevel(litres = 3.88, tankLitres = 6.6, kmPerL = 36.0, sinceRefuelKm = null) // 139.68 km
        assertEquals(135L, l.cautiousRangeKm)
        assertEquals(138L, l.copy(stepKm = RANGE_STEP_KM).cautiousRangeKm)
        // 4.8: the bike's caution, on the range only.
        val cautious = l.copy(stepKm = RANGE_STEP_KM, caution = RangeCaution.CAUTIOUS.factor)
        assertEquals(125.712, cautious.rangeKm!!, 1e-9)
        assertEquals(124L, cautious.cautiousRangeKm)
        assertEquals(l.litres, cautious.litres)
        assertEquals(0.8, RangeCaution.VERY_CAUTIOUS.factor, 1e-9)
    }

    @Test fun `the range falls at once and rises only by 4 km or after an entry`() {
        val h = RangeHold()
        assertEquals(138L, h.show("b", "k", 138))
        assertEquals(136L, h.show("b", "k", 136))
        assertEquals(136L, h.show("b", "k", 138))
        assertEquals(136L, h.show("b", "k", 139))
        assertEquals(140L, h.show("b", "k", 140))
        assertEquals(134L, h.show("b", "k", 134))
        // A fill-up is another key; another bike has its own figure.
        assertEquals(136L, h.show("b", "k2", 136))
        assertEquals(50L, h.show("c", "k2", 50))
        assertNull(h.show("b", "k2", null))
        assertEquals(137L, h.show("b", "k2", 137))
    }

    @Test fun `dash figures nudge the estimate, implausible ones are ignored`() {
        val c = learnConsumption(36.0, emptyList(), emptyList(), listOf(dash(46.0, 1), dash(5.0, 2)))
        // One figure, no tank yet: 0.4 × 1/3 of the way.
        assertEquals(36.0 + DASH_FIRST_WEIGHT / 3 * 10, c.kmPerL, 1e-9)
        assertEquals(1, c.dashUsed)
        assertTrue(c.learned)
        // Once learning has started, a wild figure is an outlier.
        val wild = learnConsumption(36.0, emptyList(), emptyList(), listOf(dash(40.0, 1), dash(80.0, 2)))
        assertEquals(1, wild.dashUsed)
    }

    @Test fun `all dash figures of one tank together weigh less than a tank`() {
        val many = learnConsumption(36.0, emptyList(), emptyList(), List(40) { dash(40.0, it + 1L) })
        assertTrue(many.kmPerL > 37.4 && many.kmPerL < 36.0 + KMPL_FIRST_WEIGHT * 4)
        // After a tank they move it still less.
        val fills = listOf(fill(1_000.0, 5.0), fill(1_180.0, 5.0)) // 36 km/L
        val later = learnConsumption(36.0, fills, emptyList(), List(40) { dash(40.0, 2_000_000L + it) })
        assertTrue(later.kmPerL > 36.0 && later.kmPerL < 36.0 + DASH_WEIGHT * 4)
    }

    @Test fun `a measured tank speaks for itself and shows how far the dash is off`() {
        val fills = listOf(fill(1_000.0, 5.0), fill(1_190.0, 5.0)) // 38 km/L
        val during = List(5) { dash(40.0, 1_050_000L + it) }
        val c = learnConsumption(36.0, fills, emptyList(), during)
        // The tank only: halfway from 36 to 38. Its figures teach the ratio (halfway to 0.95).
        assertEquals(37.0, c.kmPerL, 1e-9)
        assertEquals(0.975, c.dashRatio, 1e-9)
        // The next figures are read through it.
        val next = learnConsumption(36.0, fills, emptyList(), during + dash(40.0, 2_000_000L))
        assertEquals(37.0 + DASH_WEIGHT / 3 * (39.0 - 37.0), next.kmPerL, 1e-9)
    }

    @Test fun `a fill-up not to learn from spoils its tank and the next, and nothing else`() {
        val fills = listOf(fill(1_000.0, 5.0), fill(1_200.0, 5.0).copy(learn = false), fill(1_400.0, 5.0), fill(1_560.0, 4.0))
        val c = learnConsumption(36.0, fills, emptyList())
        assertEquals(listOf(false, false, true), c.measures.map { it.used })
        assertEquals(38.0, c.kmPerL, 1e-9)
        // The level still starts from the last full fill.
        assertEquals(6.6, fuelLevel(6.6, 36.0, fills, emptyList(), 1_560.0).litres!!, 1e-9)
    }

    // ---- 4.8: the review ----

    private val usual = listOf(fill(1_000.0, 5.0), fill(1_200.0, 5.0), fill(1_400.0, 5.0)) // 40 km/L

    private fun review(entry: RefuelRecord, refuels: List<RefuelRecord> = usual, room: Double? = null, estimate: Double? = null, price: Double = 12_500.0) =
        reviewRefuel(entry, refuels, 40.0, emptyList(), emptyList(), 6.6, room, estimate, price).map { it.text }

    @Test fun `a normal fill-up is saved without a question`() {
        assertEquals(emptyList<String>(), review(fill(1_590.0, 5.0).copy(id = 0), room = 4.8, estimate = 1_588.0))
        // The first one of a bike, and a real partial.
        assertEquals(emptyList<String>(), review(fill(1_000.0, 4.0).copy(id = 0), refuels = emptyList(), price = 0.0))
        assertEquals(emptyList<String>(), review(fill(1_500.0, 1.5, full = false).copy(id = 0), room = 2.5, estimate = 1_500.0))
    }

    @Test fun `an unusual one is asked about, in plain words`() {
        assertTrue(review(fill(1_390.0, 5.0, timeMs = 9_000_000).copy(id = 0)).single().startsWith("The odometer is below the fill-up before it"))
        assertTrue(review(fill(1_900.0, 5.0).copy(id = 0), estimate = 1_590.0).single().contains("from the estimate"))
        // 80 km on 5 L = 16 km/L: a partial logged as full, or a typo.
        assertEquals("This tank comes to 16.0 km/L; usually 40.0 km/L.", review(fill(1_480.0, 5.0).copy(id = 0), room = 2.0, estimate = 1_480.0).single())
        assertTrue(review(fill(1_590.0, 5.0, full = false).copy(id = 0), room = 4.8, estimate = 1_590.0).single().contains("Full refill is off"))
        assertTrue(review(fill(1_500.0, 4.0, full = false).copy(id = 0), room = 2.5, estimate = 1_500.0).single().startsWith("More than the estimated room"))
        assertTrue(review(fill(1_590.0, 5.0).copy(id = 0), price = 20_000.0).single().contains("per liter; last time"))
    }

    @Test fun `correcting a past fill-up looks at both tanks around it`() {
        // The middle one to 3 L: its own tank reads 66.7, the next stays 40.
        val edited = usual[1].copy(litres = 3.0)
        assertTrue(review(edited, usual + fill(1_600.0, 5.0)).single().startsWith("This tank comes to 66.7"))
        // With nothing else to go by, there is nothing to compare it to.
        assertEquals(emptyList<String>(), review(edited))
        // Its odometer past the next fill-up.
        assertTrue(review(usual[1].copy(odometerKm = 1_450.0)).single().startsWith("The odometer is above the next fill-up"))
        assertEquals(emptyList<String>(), review(usual[1].copy(litres = 4.9)))
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
