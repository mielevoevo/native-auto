package com.nativeauto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

val NativeAutoTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** Pane titles (8c): small, sentence case, dim. They label quietly instead of shouting. */
val PaneTitle: TextStyle = Default.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp, letterSpacing = 0.2.sp)

/** Tabular digits so changing numbers don't jiggle horizontally. */
val TabularNumbers: TextStyle = TextStyle(fontFeatureSettings = "tnum")
