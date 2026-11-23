package com.nativeauto.nav

/**
 * 4.3a: the router's English, as a rider says it (DESIGN.md §3/§4). Valhalla says "Bear left"; riders say "Slight left".
 * Whole words only, case kept ("bear right" → "slight right"). Used on the banner text and on the spoken prompts.
 */
fun riderPhrasing(text: String): String = BEAR.replace(text) { m ->
    val slight = if (m.groupValues[1][0].isUpperCase()) "Slight" else "slight"
    "$slight ${m.groupValues[2]}"
}

/**
 * The "Then …" line under the current turn: a maneuver sentence continues in lower case ("Then turn left"),
 * a road name keeps its capital ("Then Jalan Malioboro").
 */
fun thenLine(next: String): String {
    val first = next.substringBefore(' ')
    val text = if (first in MANEUVER_WORDS) next.replaceFirstChar { it.lowercaseChar() } else next
    return "Then $text"
}

private val BEAR = Regex("""\b([Bb])ear (left|right)\b""")
private val MANEUVER_WORDS = setOf(
    "Turn", "Slight", "Sharp", "Keep", "Continue", "Make", "Take", "Head", "Merge", "Exit", "Enter",
    "Go", "Stay", "Arrive", "Roundabout", "Bear", "Drive", "Follow", "Cross",
)
