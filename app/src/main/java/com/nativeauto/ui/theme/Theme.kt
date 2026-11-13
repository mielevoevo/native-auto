package com.nativeauto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.nativeauto.settings.ThemeMode

/** The tokens of the theme in use. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

/** Which palette a [ThemeMode] means right now. */
@Composable
fun ThemeMode.palette(): AppColors = when (this) {
    ThemeMode.DARK -> OledDark
    ThemeMode.LIGHT -> Light
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) OledDark else Light
}

/**
 * OLED dark by default (true black: a dashboard at night, pixels off); light for daylight, by
 * choice or following the system. Material components get a scheme derived from the same
 * tokens, so a stock button or sheet already matches; the cards read the tokens directly.
 */
@Composable
fun NativeAutoTheme(mode: ThemeMode = ThemeMode.DARK, accent: Color = LegacyAccent.dark, content: @Composable () -> Unit) {
    val colors = mode.palette().withAccent(accent)
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = NativeAutoTypography,
            content = content,
        )
    }
}

internal fun AppColors.toScheme(): ColorScheme {
    // Sheets and dialogs sit over the dashboard: a step up from the cards so their edge shows.
    val sheet = if (isDark) Color(0xFF111318) else Color(0xFFFFFFFF)
    val errorContainer = if (isDark) Color(0xFF4A1417) else Color(0xFFFDE2E1)
    val onErrorContainer = if (isDark) Color(0xFFFFDAD8) else Color(0xFF7A1212)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = bg,
        primaryContainer = navy,
        onPrimaryContainer = onNavy,
        inversePrimary = accent,
        secondary = textDim,
        onSecondary = bg,
        // Tonal buttons are the quiet buttons; selection gets navy explicitly (segments, toggles).
        secondaryContainer = buttonQuiet,
        onSecondaryContainer = onButtonQuiet,
        tertiary = amber,
        onTertiary = bg,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = buttonQuiet,
        onSurfaceVariant = textDim,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = surface,
        surfaceContainerLow = sheet,
        surfaceContainer = sheet,
        surfaceContainerHigh = sheet,
        surfaceContainerHighest = buttonQuiet,
        surfaceBright = sheet,
        surfaceDim = bg,
        outline = outline,
        outlineVariant = outline,
        error = red,
        onError = Color.White,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
    )
}

/** Navy fills the track, the accent thumb points at "on". */
@Composable
fun appSwitchColors(): SwitchColors {
    val c = AppTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = c.accent,
        checkedTrackColor = c.navy,
        checkedBorderColor = c.accent,
        checkedIconColor = c.navy,
        uncheckedThumbColor = c.textDim,
        uncheckedTrackColor = c.bg,
        uncheckedBorderColor = c.outline,
    )
}

/** Selected segment in navy, with an accent tick. */
@Composable
fun appSegmentedColors(): SegmentedButtonColors {
    val c = AppTheme.colors
    return SegmentedButtonDefaults.colors(
        activeContainerColor = c.navy,
        activeContentColor = c.onNavy,
        activeBorderColor = c.outline,
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = c.text,
        inactiveBorderColor = c.outline,
    )
}
