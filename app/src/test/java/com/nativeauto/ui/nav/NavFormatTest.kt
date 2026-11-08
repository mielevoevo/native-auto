package com.nativeauto.ui.nav

import com.nativeauto.nav.Instruction
import com.nativeauto.nav.ManeuverKind
import com.nativeauto.nav.TurnDirection
import com.nativeauto.settings.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class NavFormatTest {

    private fun km(m: Double) = formatNavDistance(m, SpeedUnit.KMH)
    private fun mi(m: Double) = formatNavDistance(m, SpeedUnit.MPH)

    @Test fun `metric distances round to glanceable steps`() {
        assertEquals("0 m", km(0.0))
        assertEquals("0 m", km(-3.0)) // the router can overshoot a maneuver slightly
        assertEquals("10 m", km(7.0))
        assertEquals("90 m", km(94.0))
        assertEquals("100 m", km(100.0))
        assertEquals("350 m", km(349.0))
        assertEquals("950 m", km(960.0))
        assertEquals("1 km", km(980.0)) // not "1000 m"
        assertEquals("1.0 km", km(1_000.0))
        assertEquals("1.2 km", km(1_234.0))
        assertEquals("9.9 km", km(9_940.0))
        assertEquals("10 km", km(9_960.0)) // not "10.0 km"
        assertEquals("10 km", km(10_000.0))
        assertEquals("124 km", km(123_600.0))
    }

    @Test fun `imperial distances use feet up close, then miles`() {
        assertEquals("0 ft", mi(0.0))
        assertEquals("100 ft", mi(30.0))     // 98 ft
        assertEquals("500 ft", mi(152.0))    // 499 ft
        assertEquals("0.1 mi", mi(161.0))    // 0.1 mi exactly
        assertEquals("1.0 mi", mi(1_609.344))
        assertEquals("9.9 mi", mi(9.9 * 1_609.344))
        assertEquals("10 mi", mi(9.96 * 1_609.344)) // not "10.0 mi"
        assertEquals("25 mi", mi(25 * 1_609.344))
    }

    @Test fun `durations round up to the minute`() {
        assertEquals("0 min", formatNavDuration(0.0))
        assertEquals("1 min", formatNavDuration(1.0))
        assertEquals("1 min", formatNavDuration(60.0))
        assertEquals("2 min", formatNavDuration(61.0))
        assertEquals("59 min", formatNavDuration(59 * 60.0))
        assertEquals("1 h 00 min", formatNavDuration(59 * 60.0 + 1))
        assertEquals("1 h 05 min", formatNavDuration(65 * 60.0))
        assertEquals("12 h 30 min", formatNavDuration(750 * 60.0))
    }

    @Test fun `eta is the arrival clock time in the given zone`() {
        val utc = ZoneId.of("UTC")
        val jakarta = ZoneId.of("Asia/Jakarta") // UTC+7, no DST
        assertEquals("00:00", formatEta(0L, 0.0, utc))
        assertEquals("01:30", formatEta(0L, 90 * 60.0, utc))
        assertEquals("08:30", formatEta(0L, 90 * 60.0, jakarta))
        assertEquals("00:01", formatEta(0L, 60.0, utc))
        assertEquals("00:00", formatEta(0L, 59.4, utc)) // the clock shows the minute it's in
        assertEquals("00:15", formatEta(23 * 3_600_000L + 45 * 60_000L, 30 * 60.0, utc)) // past midnight
    }

    private fun instr(text: String, kind: ManeuverKind = ManeuverKind.Turn, dir: TurnDirection? = TurnDirection.Left) =
        Instruction(text, kind, dir)

    @Test fun `the banner shows the road name`() {
        assertEquals("Jalan Malioboro", instr("Jalan Malioboro").bannerText())
        assertEquals("Jalan Malioboro", instr("  Jalan Malioboro ").bannerText())
    }

    @Test fun `a road known by its number becomes Route n`() {
        assertEquals("Route 7", instr("7").bannerText())
        assertEquals("Route 12A", instr("12A").bannerText())
        assertEquals("A12 Ring Road", instr("A12 Ring Road").bannerText())
    }

    @Test fun `the router's sentence for an unnamed road loses its full stop`() {
        assertEquals("Turn left", instr("Turn left.").bannerText())
    }

    @Test fun `an empty banner falls back to the maneuver in words`() {
        assertEquals("Turn left", instr("").bannerText())
        assertEquals("Arrive", instr(" ", ManeuverKind.Arrive, null).bannerText())
    }

    @Test fun `maneuvers in words`() {
        fun words(kind: ManeuverKind, dir: TurnDirection?) = instr("", kind, dir).maneuverWords()
        assertEquals("Arrive", words(ManeuverKind.Arrive, TurnDirection.Left))
        assertEquals("Head out", words(ManeuverKind.Depart, null))
        assertEquals("Roundabout", words(ManeuverKind.Roundabout, TurnDirection.Right))
        assertEquals("Merge", words(ManeuverKind.Merge, TurnDirection.SlightLeft))
        assertEquals("Keep left", words(ManeuverKind.Fork, TurnDirection.SlightLeft))
        assertEquals("Keep left", words(ManeuverKind.Fork, TurnDirection.SharpLeft))
        assertEquals("Keep right", words(ManeuverKind.Fork, TurnDirection.SlightRight))
        assertEquals("Keep right", words(ManeuverKind.Fork, null))
        assertEquals("Take the ramp left", words(ManeuverKind.Ramp, TurnDirection.Left))
        assertEquals("Take the ramp right", words(ManeuverKind.Ramp, TurnDirection.Right))
        assertEquals("Turn left", words(ManeuverKind.Turn, TurnDirection.Left))
        assertEquals("Turn right", words(ManeuverKind.Turn, TurnDirection.Right))
        assertEquals("Bear left", words(ManeuverKind.Turn, TurnDirection.SlightLeft))
        assertEquals("Bear right", words(ManeuverKind.Continue, TurnDirection.SlightRight))
        assertEquals("Sharp left", words(ManeuverKind.Turn, TurnDirection.SharpLeft))
        assertEquals("Sharp right", words(ManeuverKind.Turn, TurnDirection.SharpRight))
        assertEquals("Make a U-turn", words(ManeuverKind.Turn, TurnDirection.UTurn))
        assertEquals("Continue", words(ManeuverKind.Continue, TurnDirection.Straight))
        assertEquals("Continue", words(ManeuverKind.Turn, null))
    }
}
