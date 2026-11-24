package com.nativeauto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nativeauto.R

/**
 * Geist (Vercel, SIL Open Font License, `assets/licenses/geist-OFL.txt`): the app's one font,
 * bundled so it works offline. Static files for the four weights the app uses; a weight in
 * between (or Light) is matched to the nearest of these. Every face has tabular figures (`tnum`).
 */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

private val Default = Typography()

private fun TextStyle.geist(weight: FontWeight? = null) =
    copy(fontFamily = Geist, fontWeight = weight ?: fontWeight)

/** Material's scale with Geist in every style, so stock components (dialogs, chips, fields) use it too. */
val NativeAutoTypography = Typography(
    displayLarge = Default.displayLarge.geist(FontWeight.Bold),
    displayMedium = Default.displayMedium.geist(),
    displaySmall = Default.displaySmall.geist(),
    headlineLarge = Default.headlineLarge.geist(),
    headlineMedium = Default.headlineMedium.geist(),
    headlineSmall = Default.headlineSmall.geist(),
    titleLarge = Default.titleLarge.geist(),
    titleMedium = Default.titleMedium.geist(FontWeight.SemiBold),
    titleSmall = Default.titleSmall.geist(),
    bodyLarge = Default.bodyLarge.geist(),
    bodyMedium = Default.bodyMedium.geist(),
    bodySmall = Default.bodySmall.geist(),
    labelLarge = Default.labelLarge.geist(FontWeight.Medium),
    labelMedium = Default.labelMedium.geist(),
    labelSmall = Default.labelSmall.geist(),
)

/** Pane titles (8c): small, sentence case, dim. They label quietly instead of shouting. */
val PaneTitle: TextStyle = Default.titleSmall.copy(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp)

/** 4.6.3: portrait cards are read from further away on a vertical mount. */
val PaneTitlePortrait: TextStyle = PaneTitle.copy(fontSize = 17.sp)

/** Tabular digits so changing numbers don't jiggle horizontally. */
val TabularNumbers: TextStyle = TextStyle(fontFamily = Geist, fontFeatureSettings = "tnum")
