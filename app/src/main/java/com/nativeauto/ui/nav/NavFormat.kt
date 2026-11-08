package com.nativeauto.ui.nav

import com.nativeauto.nav.Instruction
import com.nativeauto.nav.ManeuverKind
import com.nativeauto.nav.TurnDirection
import com.nativeauto.settings.SpeedUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Glanceable distances: "350 m", "1.2 km", "12 km"; imperial: "500 ft", "0.3 mi", "12 mi". */
internal fun formatNavDistance(meters: Double, unit: SpeedUnit): String = when (unit) {
    SpeedUnit.KMH -> when {
        meters < 100 -> "${(meters / 10).roundToInt().coerceAtLeast(0) * 10} m"
        meters < 1_000 -> "${(meters / 50).roundToInt() * 50} m".replace("1000 m", "1 km")
        // 9_950 m and up would print as "10.0 km"; from there it's whole kilometres.
        meters < 9_950 -> String.format(Locale.ROOT, "%.1f km", meters / 1_000)
        else -> "${(meters / 1_000).roundToInt()} km"
    }
    SpeedUnit.MPH -> {
        val miles = meters / 1_609.344
        when {
            miles < 0.1 -> "${(meters * 3.28084 / 50).roundToInt().coerceAtLeast(0) * 50} ft"
            miles < 9.95 -> String.format(Locale.ROOT, "%.1f mi", miles)
            else -> "${miles.roundToInt()} mi"
        }
    }
}

/** "8 min", "1 h 05 min". Rounded up: "0 min" while still riding reads as "arrived". */
internal fun formatNavDuration(seconds: Double): String {
    val minutes = ((seconds + 59) / 60).toLong().coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else String.format(Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60)
}

/** Arrival clock time, in the device's zone. */
internal fun formatEta(nowMs: Long, remainingS: Double, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(nowMs + (remainingS * 1000).roundToLong()).atZone(zone))

/**
 * The banner's line. Valhalla puts the road name there; a road known only by its number
 * ("7") becomes "Route 7", and an unnamed road falls back to the maneuver in words.
 */
internal fun Instruction.bannerText(): String {
    val t = text.trim()
    return when {
        t.isEmpty() -> maneuverWords()
        ROAD_NUMBER.matches(t) -> "Route $t"
        else -> t.removeSuffix(".") // the router's fallback for unnamed roads is a sentence: "Turn left."
    }
}

internal fun Instruction.maneuverWords(): String = when (kind) {
    ManeuverKind.Arrive -> "Arrive"
    ManeuverKind.Depart -> "Head out"
    ManeuverKind.Roundabout -> "Roundabout"
    ManeuverKind.Merge -> "Merge"
    ManeuverKind.Fork -> if (direction.isLeft()) "Keep left" else "Keep right"
    ManeuverKind.Ramp -> if (direction.isLeft()) "Take the ramp left" else "Take the ramp right"
    ManeuverKind.Continue, ManeuverKind.Turn -> when (direction) {
        TurnDirection.Left -> "Turn left"
        TurnDirection.Right -> "Turn right"
        TurnDirection.SlightLeft -> "Bear left"
        TurnDirection.SlightRight -> "Bear right"
        TurnDirection.SharpLeft -> "Sharp left"
        TurnDirection.SharpRight -> "Sharp right"
        TurnDirection.UTurn -> "Make a U-turn"
        TurnDirection.Straight, null -> "Continue"
    }
}

private fun TurnDirection?.isLeft() = this == TurnDirection.Left || this == TurnDirection.SlightLeft || this == TurnDirection.SharpLeft

private val ROAD_NUMBER = Regex("""^\d+[A-Za-z]?$""")
