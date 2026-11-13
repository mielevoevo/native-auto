package com.nativeauto.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The app's colour tokens (Rider-Feedback §11). Monochrome base, a navy that fills things in
 * (selected, active), an accent that points at things (at most one per card; green by default,
 * the rider's choice since 13c), and status colours
 * kept apart from the accent. The Material scheme is derived from these in Theme.kt; read the
 * tokens directly (`AppTheme.colors`) where Material has no matching role.
 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    /** Screen background. True black in the dark theme: OLED pixels off. */
    val bg: Color,
    /** Sheets and dialogs, barely off the background. */
    val surface: Color,
    /** Dashboard cards (12a): true black in the dark theme, so only text and lines light the OLED. */
    val card: Color,
    /** Card borders, dividers. */
    val outline: Color,
    /** Main text and icons. */
    val text: Color,
    /** Labels, units, titles. */
    val textDim: Color,
    /** The main button (play, start): fill and icon. */
    val button: Color,
    val onButton: Color,
    /** Every other button (skip, search, recenter): fill and icon. */
    val buttonQuiet: Color,
    val onButtonQuiet: Color,
    /** Active or selected backgrounds (traffic on, selected segment) and the text on them. */
    val navy: Color,
    val onNavy: Color,
    /** Small highlights: progress, switch thumb, live dot, ETA. The rider's accent (13c); see [withAccent]. */
    val accent: Color,
    /** Due soon, low fuel. */
    val amber: Color,
    /** Alerts and confirm states (reset, end, low battery). */
    val red: Color,
    /** Behind the map until it draws; the land colour of the map style. */
    val mapBackground: Color,
) {
    val mapBackgroundArgb: Int get() = mapBackground.toArgb()
}

val OledDark = AppColors(
    isDark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF0A0C10),
    card = Color(0xFF000000),
    outline = Color(0xFF262A33),
    text = Color(0xFFE8EAED),
    textDim = Color(0xFF8A9099),
    button = Color(0xFFE8EAED),
    onButton = Color(0xFF000000),
    buttonQuiet = Color(0xFF1A1D23),
    onButtonQuiet = Color(0xFFE8EAED),
    navy = Color(0xFF0F2A4A),
    onNavy = Color(0xFFE8EAED),
    accent = Color(0xFF3FC8E0), // cyan, what every install had before 13c; the theme swaps in the chosen accent
    amber = Color(0xFFF2A93B),
    red = Color(0xFFE5484D),
    mapBackground = Color(0xFF000000),
)

/** For daylight. Same roles, inverted; the accent is darker so it reads on white. */
val Light = AppColors(
    isDark = false,
    bg = Color(0xFFF1F2F4),
    surface = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    outline = Color(0xFFD3D7DE),
    text = Color(0xFF14171C),
    textDim = Color(0xFF5E6570),
    button = Color(0xFF14171C),
    onButton = Color(0xFFFFFFFF),
    buttonQuiet = Color(0xFFE6E8EC),
    onButtonQuiet = Color(0xFF14171C),
    navy = Color(0xFFD5E3F5),
    onNavy = Color(0xFF0F2A4A),
    accent = Color(0xFF0A86A0),
    amber = Color(0xFFB86E00),
    red = Color(0xFFC62828),
    mapBackground = Color(0xFFF2F3F0),
)

val LocalAppColors = staticCompositionLocalOf { OledDark }

/** These colours with the rider's accent: a hand-picked preset keeps its light variant, anything else is adjusted for the theme. */
fun AppColors.withAccent(chosen: Color): AppColors = copy(accent = accentFor(chosen, isDark))
