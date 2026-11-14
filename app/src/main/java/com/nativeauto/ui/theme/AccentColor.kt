package com.nativeauto.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 13c: the accent colour, the one highlight on a card (progress, switch thumb, ETA, the lifted card's
 * outline). Presets plus any hex the rider types. Amber and red stay status colours and are not offered.
 *
 * Pure maths (JVM-tested): hex parsing, WCAG contrast, the light theme's darker variant, and the
 * readability warning for the hex field.
 */
data class AccentPreset(val key: String, val label: String, val dark: Color, val light: Color)

/** Cyan is the colour every install had until 13c; its light variant is the one 8c shipped. */
val AccentPresets: List<AccentPreset> = listOf(
    AccentPreset("green", "Green", Color(0xFF3DDC84), Color(0xFF0B7A3E)),
    AccentPreset("cyan", "Cyan", Color(0xFF3FC8E0), Color(0xFF0A86A0)),
    AccentPreset("ice", "Ice blue", Color(0xFF8CC8FF), Color(0xFF1F6FB2)),
    AccentPreset("violet", "Violet", Color(0xFFB794F6), Color(0xFF6B46C1)),
    AccentPreset("magenta", "Magenta", Color(0xFFFF6BCB), Color(0xFFB0187E)),
)

/** The accent of every install, new or old: cyan (4.1.1; fresh installs were green from 13c to 4.1). */
val DefaultAccent: AccentPreset = AccentPresets.first { it.key == "cyan" }

/** What every install from before 13c has: its settings and look stay as they were. */
val LegacyAccent: AccentPreset = DefaultAccent

/**
 * Which accent an install starts with: cyan, whether fresh or existing (the arguments stay so the one caller and its
 * tests keep their shape). An accent already stored is never touched.
 */
@Suppress("UNUSED_PARAMETER")
fun initialAccent(hasSavedPreferences: Boolean, freshInstall: Boolean): AccentPreset = DefaultAccent

/** `#RRGGBB`, `RRGGBB` or `#RGB`, any case, spaces around ignored. Null when it isn't a colour. */
fun parseHexColor(text: String): Color? {
    val t = text.trim().removePrefix("#")
    val full = when (t.length) {
        3 -> t.map { "$it$it" }.joinToString("")
        6 -> t
        else -> return null
    }
    if (!full.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return Color(0xFF000000L or full.toLong(16))
}

/** `#RRGGBB`, upper case. */
fun Color.toHex(): String = "#%02X%02X%02X".format(Math.round(red * 255) and 0xFF, Math.round(green * 255) and 0xFF, Math.round(blue * 255) and 0xFF)

private fun channel(v: Float): Double {
    val c = v.toDouble()
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/** WCAG relative luminance. */
fun Color.luminance2(): Double = 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)

/** WCAG contrast ratio, 1..21. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance2()
    val lb = b.luminance2()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Below this the accent is hard to see (WCAG's 3:1 for UI parts and large text). */
const val MIN_ACCENT_CONTRAST = 3.0

/** What the accent needs on white to read as text: the light theme darkens it to this. */
const val LIGHT_TARGET_CONTRAST = 4.5

/**
 * The accent for the light theme: the dark one blended towards black, in small steps, until it reads on
 * white ([LIGHT_TARGET_CONTRAST]). A colour that already does stays as it is.
 */
fun lightVariant(dark: Color, white: Color = Color.White): Color {
    var c = dark
    var step = 0
    while (contrastRatio(c, white) < LIGHT_TARGET_CONTRAST && step < 40) {
        step++
        val t = step * 0.025f
        c = Color(dark.red * (1 - t), dark.green * (1 - t), dark.blue * (1 - t), 1f)
    }
    return c
}

/** The accent in the dark theme: a colour too dark for black is lightened towards white until it is visible. */
fun darkVariant(chosen: Color, black: Color = Color.Black): Color {
    var c = chosen
    var step = 0
    while (contrastRatio(c, black) < MIN_ACCENT_CONTRAST && step < 40) {
        step++
        val t = step * 0.025f
        c = Color(chosen.red + (1 - chosen.red) * t, chosen.green + (1 - chosen.green) * t, chosen.blue + (1 - chosen.blue) * t, 1f)
    }
    return c
}

/** Warnings for the hex field (empty when the colour reads well on black and on white). */
fun accentWarnings(color: Color): List<String> = buildList {
    if (contrastRatio(color, Color.Black) < MIN_ACCENT_CONTRAST) add("Hard to see on black: the dark theme will lighten it.")
    if (contrastRatio(color, Color.White) < MIN_ACCENT_CONTRAST) add("Hard to see on white: the light theme will darken it.")
}

/**
 * The colour a stored accent draws with in each theme. A preset keeps its hand-picked light variant;
 * anything else gets the computed one.
 */
fun accentFor(chosen: Color, isDark: Boolean): Color {
    val preset = AccentPresets.firstOrNull { it.dark.toArgbInt() == chosen.toArgbInt() }
    return if (isDark) darkVariant(chosen) else preset?.light ?: lightVariant(chosen)
}

private fun Color.toArgbInt(): Int = (0xFF shl 24) or ((Math.round(red * 255) and 0xFF) shl 16) or ((Math.round(green * 255) and 0xFF) shl 8) or (Math.round(blue * 255) and 0xFF)
