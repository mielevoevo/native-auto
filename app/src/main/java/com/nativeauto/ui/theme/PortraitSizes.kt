package com.nativeauto.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 4.6.3: the portrait cards' sizes by role, one scale for every pane, so a clock, a range and a track title of the same
 * kind are the same size wherever they stand. Fixed per card shape (rule #6: never the measured size). 4.9 (tablets)
 * adds shapes here rather than sizes in the cards. Landscape cards keep their own.
 */
@Immutable
data class PortraitSizes(
    /** The live speed. */
    val hero: TextUnit,
    /** The one value a card is about, digits only (the clock). */
    val value: TextUnit,
    /** The same with its unit beside it (range, km/L). */
    val valueWithUnit: TextUnit,
    /** The same when the text is long (an odometer). */
    val valueLong: TextUnit,
    /** The main value where it shares the card with other lines (the distance to the turn). */
    val valueShared: TextUnit,
    /** A name: track, road. */
    val title: TextUnit,
    /** Lines that are read, not glanced at: artist, arrival, trip figures, a value's label. */
    val secondary: TextUnit,
    /** A trip figure beside the speed, and its label. */
    val figure: TextUnit,
    val figureLabel: TextUnit,
    /** The clock's date. */
    val date: TextUnit,
    /** The turn arrow. */
    val glyph: Dp,
    val art: Dp,
    val play: Dp,
    /** How many lines a road may take. */
    val roadLines: Int = 2,
)

/** The greeting sheet in portrait (4.6.4): its range, a figure and the lines under them. */
object PortraitSheet {
    val value = 72.sp
    val figure = 28.sp
    val label = 18.sp
    val gaugeWidth = 56.dp
    val gaugeHeight = 100.dp
}

/** Longer than this, a value is drawn at [PortraitSizes.valueLong] ("123 km" fits, "12.345 km" is long). */
const val LONG_VALUE_CHARS = 7

object PortraitScale {
    /** A slim row beside a maximized card (about 110 dp of content). */
    val Slim = PortraitSizes(
        hero = 64.sp, value = 64.sp, valueWithUnit = 52.sp, valueLong = 40.sp, valueShared = 30.sp,
        title = 18.sp, secondary = 14.sp, figure = 18.sp, figureLabel = 14.sp, date = 16.sp,
        glyph = 36.dp, art = 64.dp, play = 60.dp, roadLines = 1,
    )

    /** Four stacked cards (about 160 dp of content). */
    val Mini = PortraitSizes(
        hero = 64.sp, value = 64.sp, valueWithUnit = 56.sp, valueLong = 44.sp, valueShared = 40.sp,
        title = 18.sp, secondary = 16.sp, figure = 20.sp, figureLabel = 14.sp, date = 18.sp,
        glyph = 48.dp, art = 88.dp, play = 72.dp,
    )

    /** A half-width card under the big one (about 190 dp across). */
    val Narrow = PortraitSizes(
        hero = 56.sp, value = 48.sp, valueWithUnit = 44.sp, valueLong = 30.sp, valueShared = 44.sp,
        title = 18.sp, secondary = 16.sp, figure = 20.sp, figureLabel = 14.sp, date = 18.sp,
        glyph = 88.dp, art = 120.dp, play = 72.dp,
    )

    /** Three stacked rows (about 250 dp of content). */
    val Row = PortraitSizes(
        hero = 72.sp, value = 88.sp, valueWithUnit = 72.sp, valueLong = 52.sp, valueShared = 56.sp,
        title = 20.sp, secondary = 18.sp, figure = 20.sp, figureLabel = 14.sp, date = 24.sp,
        glyph = 80.dp, art = 120.dp, play = 72.dp,
    )
}
