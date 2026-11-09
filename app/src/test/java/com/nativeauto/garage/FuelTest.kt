package com.nativeauto.garage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 11b: km/L learning, the tank level, amounts and spend. */
class FuelTest {

    private var nextId = 1L

    private fun fill(odo: Double, litres: Double, full: Boolean = true, timeMs: Long = odo.toLong() * 1_000, rp: Double = litres * 12_500) =
        RefuelRecord(
            id = nextId++, bikeId = "b", timeMs = timeMs, odometerKm = odo, litres = litres, pricePerL = 12_500.0,
            totalRp = rp, full = full, fuelType = FuelType.PERTAMAX.name,
        )

    // ---- learning ----

    @Test fun `no full-to-full span keeps the starting value`() {
        val c = learnConsumption(36.0, listOf(fill(1_000.0, 5.0), fill(1_100.0, 2.0, full = false)), emptyList())
        assertEquals(36.0, c.kmPerL, 0.0)
        assertFalse(c.learned)
        assertTrue(c.measures.isEmpty())
    }

    @Test fun `a full-to-full span counts the partials in between`() {
        // 240 km on 1.0 + 5.0 L = 40 km/L; the first measure moves halfway from the guess.
        val c = learnConsumption(36.0, listOf(fill(1_000.0, 5.0), fill(1_100.0, 1.0, full = false), fill(1_240.0, 5.0)), emptyList())
        assertEquals(1, c.measures.size)
        assertEquals(40.0, c.measures.single().kmPerL, 1e-9)
        assertEquals(38.0, c.kmPerL, 1e-9)
        assertTrue(c.learned)
    }

    @Test fun `later spans move the estimate less, and order comes from time`() {
        val fills = listOf(fill(1_000.0, 5.0), fill(1_200.0, 5.0), fill(1_350.0, 5.0)) // 40 km/L, then 30 km/L
        val c = learnConsumption(36.0, fills.reversed(), emptyList())
        // 36 → 38 (first, weight 0.5) → 38 + 0.3 × (30 − 38) = 35.6
        assertEquals(35.6, c.kmPerL, 1e-9)
        assertEquals(listOf(40.0, 30.0), c.measures.map { it.kmPerL })
    }

    @Test fun `outliers and impossible values are ignored`() {
        val fills = listOf(
            fill(1_000.0, 5.0),
            fill(1_180.0, 5.0), // 36
            fill(1_480.0, 5.0), // 60: a forgotten fill-up
            fill(1_485.0, 5.0), // 1 km/L: a typo
            fill(1_665.0, 5.0), // 36
        )
        val c = learnConsumption(36.0, fills, emptyList())
        assertEquals(listOf(true, false, false, true), c.measures.map { it.used })
        assertEquals(36.0, c.kmPerL, 1e-9)
    }

    @Test fun `the first measure may be far from a wrong guess`() {
        // The rider guessed 36, the bike does 22: still learned (only plausibility applies).
        val c = learnConsumption(36.0, listOf(fill(1_000.0, 5.0), fill(1_110.0, 5.0)), emptyList())
        assertEquals(29.0, c.kmPerL, 1e-9)
    }

    @Test fun `a span with away km teaches nothing`() {
        val fills = listOf(fill(1_000.0, 5.0, timeMs = 1_000), fill(1_400.0, 5.0, timeMs = 3_000))
        val c = learnConsumption(36.0, fills, awayTimesMs = listOf(2_000))
        assertEquals(36.0, c.kmPerL, 0.0)
        assertFalse(c.measures.single().used)
    }

    // ---- tank level ----

    @Test fun `unknown before any full fill`() {
        val l = fuelLevel(6.6, 36.0, listOf(fill(1_000.0, 2.0, full = false)), emptyList(), 1_050.0)
        assertNull(l.litres)
        assertNull(l.rangeKm)
        assertEquals(UnknownFuel.NO_FULL_FILL, l.unknownBecause)
        assertEquals(50.0, l.sinceRefuelKm!!, 1e-9)
    }

    @Test fun `full at the fill, minus the km since`() {
        val l = fuelLevel(6.6, 36.0, listOf(fill(1_000.0, 5.0)), emptyList(), 1_072.0)
        assertEquals(4.6, l.litres!!, 1e-9)
        assertEquals(4.6 * 36, l.rangeKm!!, 1e-9)
        assertEquals(2.0, l.toFillLitres!!, 1e-9)
        assertEquals(4.6 / 6.6, l.fraction!!, 1e-9)
    }

    @Test fun `a partial adds its litres, capped at the tank`() {
        val fills = listOf(fill(1_000.0, 5.0), fill(1_108.0, 1.0, full = false))
        // 6.6 − 3.0 + 1.0 = 4.6, then 36 km more = 3.6
        assertEquals(3.6, fuelLevel(6.6, 36.0, fills, emptyList(), 1_144.0).litres!!, 1e-9)
        val over = listOf(fill(1_000.0, 5.0), fill(1_036.0, 4.0, full = false))
        assertEquals(6.6, fuelLevel(6.6, 36.0, over, emptyList(), 1_036.0).litres!!, 1e-9)
    }

    @Test fun `never below empty`() {
        assertEquals(0.0, fuelLevel(6.6, 36.0, listOf(fill(1_000.0, 5.0)), emptyList(), 2_000.0).litres!!, 0.0)
    }

    @Test fun `away km after the last full fill make it unknown, a full fill after them restores it`() {
        val fills = listOf(fill(1_000.0, 5.0, timeMs = 1_000))
        assertEquals(UnknownFuel.AWAY, fuelLevel(6.6, 36.0, fills, listOf(2_000), 1_200.0).unknownBecause)
        val again = fills + fill(1_200.0, 5.0, timeMs = 3_000)
        assertEquals(6.6, fuelLevel(6.6, 36.0, again, listOf(2_000), 1_200.0).litres!!, 1e-9)
    }

    // ---- amounts and money ----

    @Test fun `amounts from rupiah or litres`() {
        val (l, rp) = refuelAmounts(AmountMode.RUPIAH, 50_000.0, 12_500.0)!!
        assertEquals(4.0, l, 1e-9)
        assertEquals(50_000.0, rp, 0.0)
        val (l2, rp2) = refuelAmounts(AmountMode.LITRES, 4.5, 10_000.0)!!
        assertEquals(4.5, l2, 0.0)
        assertEquals(45_000.0, rp2, 1e-9)
        assertNull(refuelAmounts(AmountMode.RUPIAH, null, 12_500.0))
        assertNull(refuelAmounts(AmountMode.RUPIAH, 50_000.0, 0.0))
    }

    @Test fun `prices fall back to the default`() {
        assertEquals(FuelType.PERTALITE.defaultPricePerL, priceOf(FuelType.PERTALITE, emptyList()), 0.0)
        // 12c: the rates of 2026-11-09.
        assertEquals(listOf(10_000.0, 15_950.0, 19_150.0, 19_600.0), FuelType.entries.map { it.defaultPricePerL })
        assertEquals(10_200.0, priceOf(FuelType.PERTALITE, listOf(FuelPrice("PERTALITE", 10_200.0, 1))), 0.0)
    }

    @Test fun `cost per km spans first to last full fill`() {
        val fills = listOf(fill(1_000.0, 5.0, rp = 60_000.0), fill(1_100.0, 1.0, full = false, rp = 12_000.0), fill(1_200.0, 4.0, rp = 48_000.0))
        assertEquals(300.0, costPerKm(fills)!!, 1e-9)
        assertNull(costPerKm(fills.take(1)))
    }

    @Test fun `spend per period`() {
        val zone = ZoneId.of("Asia/Jakarta")
        fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val fills = listOf(
            fill(1.0, 1.0, timeMs = at(2026, 10, 20), rp = 10_000.0),
            fill(2.0, 1.0, timeMs = at(2026, 11, 1), rp = 20_000.0),
            fill(3.0, 1.0, timeMs = at(2026, 11, 8), rp = 40_000.0),
        )
        val s = spend(fills, at(2026, 11, 9), zone)
        assertEquals(40_000.0, s.last7Days, 0.0)
        assertEquals(60_000.0, s.thisMonth, 0.0)
        assertEquals(10_000.0, s.lastMonth, 0.0)
        assertEquals(0.0, spend(emptyList(), at(2026, 11, 9), ZoneOffset.UTC).thisMonth, 0.0)
    }

    @Test fun formatting() {
        assertEquals("Rp 98.000", formatRupiah(98_000.4))
        assertEquals("Rp 1.250.000", formatRupiah(1_250_000.0))
        assertEquals("5.0 L", formatLitres(4.96))
        assertEquals("36.4 km/L", formatKmPerL(36.44))
    }
}
