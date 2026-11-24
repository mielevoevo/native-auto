package com.nativeauto.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import com.nativeauto.ui.theme.PaneTitlePortrait
import com.nativeauto.ui.theme.PortraitScale
import com.nativeauto.ui.theme.PortraitSizes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.PaneTitle
import androidx.compose.foundation.layout.size
import com.nativeauto.ui.dashboard.icon
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds

private val CardShape = RoundedCornerShape(20.dp)

/**
 * The shared pane container: rounded surface, [CardChrome] header, then [content].
 *
 * Gestures on the body (every pane but the map, whose touches pan and zoom it): a tap toggles
 * maximize where the pane can be maximized (it shares a column). A tap on a fullscreen card does
 * nothing; leave fullscreen with the header icon or Back. Holding a card lifts it to be dragged
 * or, let go without moving, opens the pane picker (12a, `paneDrag` on the dashboard's slots;
 * it replaced long-press = fullscreen and the header's long-press = picker).
 */
@Composable
fun DashboardCard(
    title: String,
    pane: Pane,
    mode: CardMode,
    layout: DashboardLayoutState,
    color: Color = AppTheme.colors.card,
    chromeOverContent: Boolean = false,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    val body = if (pane == Pane.MAP) Modifier else Modifier.clickable(
        onClickLabel = when {
            !layout.canMaximize(pane) -> null
            layout.maximized == pane -> "Restore $title"
            else -> "Maximize $title"
        },
    ) {
        if (mode != CardMode.Fullscreen && layout.canMaximize(pane)) {
            layout.toggleMaximized(pane)
            haptics.toggle(on = layout.maximized == pane)
        }
    }
    // 4.6.3: derived from the layout, not measured (rule #6).
    val slim = LocalPortrait.current && mode == CardMode.Mini && layout.maximized != null
    CompositionLocalProvider(LocalSlimRow provides slim) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(CardShape)
            .testTag("pane_${pane.name}")
            .semantics { stateDescription = mode.name },
        shape = CardShape,
        color = color,
        contentColor = AppTheme.colors.text,
        // OLED: a true-black card on black (12a), told apart by a faint outline, not a grey fill.
        border = BorderStroke(1.dp, AppTheme.colors.outline),
    ) {
        if (chromeOverContent) {
            // Full-bleed content (the map) with the header floating on a fade, so the content
            // keeps its whole area and the header stays legible over it.
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().then(body)) { content() }
                CardChrome(
                    title = title,
                    pane = pane,
                    mode = mode,
                    layout = layout,
                    headerActions = headerActions,
                    modifier = Modifier.background(
                        Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), Color.Transparent)),
                    ),
                )
            }
        } else {
            Column(Modifier.fillMaxSize().then(paneWatermark(pane, mode))) {
                CardChrome(title = title, pane = pane, mode = mode, layout = layout, headerActions = headerActions)
                Column(Modifier.weight(1f).fillMaxWidth().then(body)) { content() }
            }
        }
    }
    }
}

/**
 * Transition between [CardMode]s: a quick fade-through with **no** size transform. The slot's
 * animated rectangle (DashboardScreen) is the only thing that resizes the card; content never animates its own
 * bounds, so text never re-wraps mid-flight.
 */
internal fun <S> AnimatedContentTransitionScope<S>.cardModeTransition(): ContentTransform =
    (fadeIn(tween(durationMillis = 220, delayMillis = 90)) togetherWith fadeOut(tween(durationMillis = 90)))
        .using(null)

/**
 * Card header: title plus maximize (where the pane shares a column), fullscreen and, where it is
 * the clearest way to rearrange, swap ([DashboardLayoutState.canSwap]). Swap trades places with the
 * pane above or below, or moves the whole column across when the pane is alone in it. A tap on
 * the header acts like a tap on the body; holding it lifts the card (12a), as anywhere else.
 *
 * 13a: the title comes first. Whatever the corner adds (clock, battery) yields when the header is
 * too narrow, the clock before the battery ([HeaderRow]); a pane's own buttons are at most
 * maximize + fullscreen in a Compact card (swap lives only on Expanded and Wide panes), and a
 * fullscreen pane drops maximize.
 */
@Composable
fun CardChrome(
    title: String,
    pane: Pane,
    mode: CardMode,
    layout: DashboardLayoutState,
    modifier: Modifier = Modifier,
    headerActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val picker = LocalPanePicker.current
    val canMaximize = layout.canMaximize(pane)
    val fullscreen = layout.fullscreen == pane
    val corner = if (layout.statusPane == pane) LocalStatusCorner.current else null
    // 4.3a: tells the pane picker whether this header has its own zoom buttons (it offers them only where not).
    LocalHeaderZoom.current?.let { zoom ->
        val own = headerActions == null && mode != CardMode.Mini && mode != CardMode.Narrow
        SideEffect { if (zoom[pane] != own) zoom[pane] = own }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                if (mode != CardMode.Fullscreen && canMaximize) {
                    layout.toggleMaximized(pane)
                    haptics.toggle(on = layout.maximized == pane)
                }
            }
            // Screen readers can't hold-and-release: the picker as an action.
            .semantics { customActions = listOf(CustomAccessibilityAction("Choose what this pane shows") { picker?.invoke(pane); true }) }
            .testTag("header_${pane.name}")
            // 4.1.1: a portrait card's title sits lower, as it does in landscape: a little extra above it and the header at least as tall as
            // a header with buttons (Narrow has none). 4.6.1: four stacked cards too (215 dp each, there is room). 4.6.3: the slim
            // rows beside a maximized card keep the small lift of 4.1.1 (their content needs the height).
            .then(
                if (LocalPortrait.current) {
                    if (LocalSlimRow.current) Modifier.padding(top = 2.dp) else Modifier.padding(top = 6.dp).heightIn(min = 48.dp)
                } else Modifier,
            )
            .padding(start = 16.dp, end = 4.dp),
    ) {
        HeaderRow(
            title = {
                // 4.3b: the pane's icon instead of its name, if the rider chose it (screen readers still hear the name).
                if (LocalCardLook.current.iconTitles) {
                    Icon(
                        imageVector = pane.icon,
                        contentDescription = title,
                        tint = AppTheme.colors.textDim,
                        modifier = Modifier.size(if (LocalPortrait.current) 28.dp else 24.dp).testTag("header_title_${pane.name}"),
                    )
                } else {
                    Text(
                        text = title,
                        style = if (LocalPortrait.current) PaneTitlePortrait else PaneTitle,
                        color = AppTheme.colors.textDim,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("header_title_${pane.name}"),
                    )
                }
            },
            clock = corner?.time?.let { time -> { StatusClock(time) } },
            battery = corner?.battery?.let { battery -> { BatteryIndicator(battery) } },
            fuel = if (corner?.lowFuel == true) {
                { com.nativeauto.ui.garage.LowFuelIcon(size = 22.dp, modifier = Modifier.padding(horizontal = 6.dp)) }
            } else null,
        ) {
            if (corner != null) StatusButtons(corner)
            // 13b: a pane may put its own buttons here (the map while navigating: mute, End). Then
            // maximize, fullscreen and swap are in the pane picker (hold the card), so the header
            // keeps to the title plus two buttons.
            if (headerActions != null) {
                headerActions()
                return@HeaderRow
            }
            // 14b: a four-stack or half-width card has a title and nothing else (the status corner aside):
            // Fullscreen and the other choices are in the pane picker, under the hold.
            if (mode == CardMode.Mini || mode == CardMode.Narrow) return@HeaderRow
            if (canMaximize && !fullscreen) {
                val maximized = layout.maximized == pane
                ChromeButton(
                    icon = if (maximized) AppIcons.Restore else AppIcons.Maximize,
                    description = if (maximized) "Restore $title" else "Maximize $title",
                    tag = "maximize_${pane.name}",
                ) {
                    layout.toggleMaximized(pane)
                    haptics.toggle(on = layout.maximized == pane)
                }
            }
            ChromeButton(
                icon = if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                description = if (fullscreen) "Exit fullscreen" else "Fullscreen $title",
                tag = "fullscreen_${pane.name}",
            ) {
                layout.toggleFullscreen(pane)
                haptics.toggle(on = layout.fullscreen == pane)
            }
            if (layout.canSwap(pane)) {
                val vertical = layout.swapsVertically(pane)
                ChromeButton(
                    icon = if (vertical) AppIcons.SwapVert else AppIcons.SwapHoriz,
                    description = if (vertical) "Swap cards" else "Swap sides",
                    tag = "swap_${pane.name}",
                ) {
                    layout.swap(pane)
                    haptics.tap()
                }
            }
        }
    }
}

private const val SLOT_TITLE = "title"
private const val SLOT_CLOCK = "clock"
private const val SLOT_BATTERY = "battery"
private const val SLOT_FUEL = "fuel"
private const val SLOT_BUTTONS = "buttons"

/**
 * The header's one-line layout (13a). The buttons sit at the right end and always fit; the title
 * takes what it needs next to them (shortened with an ellipsis only when even that is too wide);
 * the battery and then the clock use what is left and are left out when there is no room. A plain
 * Row would have given the title only the leftovers, and the corner clock cut it ("Next tu").
 */
@Composable
private fun HeaderRow(
    title: @Composable () -> Unit,
    clock: (@Composable () -> Unit)?,
    battery: (@Composable () -> Unit)?,
    fuel: (@Composable () -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Box(Modifier.layoutId(SLOT_TITLE)) { title() }
            if (clock != null) Box(Modifier.layoutId(SLOT_CLOCK)) { clock() }
            if (battery != null) Box(Modifier.layoutId(SLOT_BATTERY)) { battery() }
            if (fuel != null) Box(Modifier.layoutId(SLOT_FUEL)) { fuel() }
            Row(Modifier.layoutId(SLOT_BUTTONS), verticalAlignment = Alignment.CenterVertically) { buttons() }
        },
    ) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        fun find(id: String) = measurables.firstOrNull { it.layoutId == id }

        val buttonsP = find(SLOT_BUTTONS)!!.measure(loose)
        var room = (constraints.maxWidth - buttonsP.width).coerceAtLeast(0)

        val titleM = find(SLOT_TITLE)!!
        val titleWidth = minOf(titleM.maxIntrinsicWidth(constraints.maxHeight.coerceAtMost(MAX_HEADER_H)), room)
        val titleP = titleM.measure(Constraints(maxWidth = titleWidth, maxHeight = constraints.maxHeight))
        room -= titleP.width

        // Natural size; dropped (never squeezed) when it doesn't fit beside the title.
        fun fit(id: String) = find(id)?.measure(loose)?.takeIf { it.width <= room }?.also { room -= it.width }
        val batteryP = fit(SLOT_BATTERY)
        val clockP = fit(SLOT_CLOCK)
        // 4.3a: the low-fuel pump yields first (the Garage card and the trip figures still say it).
        val fuelP = fit(SLOT_FUEL)

        val height = maxOf(buttonsP.height, titleP.height, batteryP?.height ?: 0, clockP?.height ?: 0, fuelP?.height ?: 0)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = constraints.maxWidth
        layout(width, height) {
            titleP.place(0, (height - titleP.height) / 2)
            var x = width - buttonsP.width
            buttonsP.place(x, (height - buttonsP.height) / 2)
            fuelP?.let { x -= it.width; it.place(x, (height - it.height) / 2) }
            batteryP?.let { x -= it.width; it.place(x, (height - it.height) / 2) }
            clockP?.let { x -= it.width; it.place(x, (height - it.height) / 2) }
        }
    }
}

private const val MAX_HEADER_H = 4096

/**
 * 14b: the dashboard is portrait (a vertically mounted phone). Cards read it where a portrait card is shaped differently
 * from its landscape namesake (a Wide row is 400 dp across, not 800; a Fullscreen card is tall, not wide).
 */
val LocalPortrait = staticCompositionLocalOf { false }

/** 4.6.3: this card is a slim row beside a maximized portrait card. Provided by [DashboardCard]. */
val LocalSlimRow = compositionLocalOf { false }

/** 4.6.3: the portrait sizes for a card of this [mode] (the small shapes; a tall card has its own). */
@Composable
internal fun portraitSizes(mode: CardMode): PortraitSizes = when {
    mode == CardMode.Mini -> if (LocalSlimRow.current) PortraitScale.Slim else PortraitScale.Mini
    mode == CardMode.Narrow -> PortraitScale.Narrow
    else -> PortraitScale.Row
}

/** 4.3b: how cards look: icon titles, the faint pane icon. Provided by DashboardScreen from the settings. */
@androidx.compose.runtime.Immutable
data class CardLook(val iconTitles: Boolean = false, val watermark: Boolean = false)

val LocalCardLook = staticCompositionLocalOf { CardLook() }

/** 4.3b: the faint icon's strength, a fraction of the text colour (~5–6 %, DESIGN.md: it must not touch contrast). */
internal const val WATERMARK_ALPHA = 0.055f

/**
 * 4.3b: a very faint pane icon behind the card's content. Only in Expanded / Fullscreen, and never on the map
 * (full-bleed), the speed card (its hero number) or a call. Drawn in the draw phase only: nothing recomposes for it.
 * 4.6.3 (rider): small, cut off by the card's right edge at the bottom; above the button row of the cards that have one
 * there (media, clock), so it never sits behind a button.
 */
@Composable
private fun paneWatermark(pane: Pane, mode: CardMode): Modifier {
    val show = LocalCardLook.current.watermark &&
        (mode == CardMode.Expanded || mode == CardMode.Fullscreen) &&
        pane != Pane.MAP && pane != Pane.SPEED && pane != Pane.CALL
    if (!show) return Modifier
    val painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(pane.icon)
    val tint = AppTheme.colors.text
    return Modifier
        .clipToBounds()
        .drawBehind {
            val side = (if (mode == CardMode.Fullscreen) 128.dp else 96.dp).toPx()
            val buttons = if (pane == Pane.MEDIA || pane == Pane.CLOCK) 108.dp.toPx() else 0f
            val bottom = if (buttons > 0f) side + buttons else side * 0.75f
            translate(left = size.width - side * 0.75f, top = size.height - bottom) {
                with(painter) {
                    draw(
                        androidx.compose.ui.geometry.Size(side, side),
                        alpha = WATERMARK_ALPHA,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint),
                    )
                }
            }
        }
}

/**
 * 4.3a: per pane, whether its header shows its own maximize / fullscreen buttons. Filled by [CardChrome], read by
 * the pane picker. Provided by DashboardScreen; null in isolation.
 */
val LocalHeaderZoom = staticCompositionLocalOf<MutableMap<Pane, Boolean>?> { null }

/** Opens the pane picker for a slot (by the pane in it). Provided by DashboardScreen; null in isolation. */
val LocalPanePicker = compositionLocalOf<((Pane) -> Unit)?> { null }

/**
 * Whether a pane is still animating towards the rectangle its layout gives it (8d). Read it in a
 * layout or draw pass, not in composition: it changes every frame of a move.
 */
val LocalPaneMotion = staticCompositionLocalOf<(Pane) -> Boolean> { { false } }

@Composable
internal fun ChromeButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = description)
    }
}
