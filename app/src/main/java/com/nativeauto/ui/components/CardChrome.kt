package com.nativeauto.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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

private val CardShape = RoundedCornerShape(20.dp)

/**
 * The shared pane container: rounded surface, [CardChrome] header, then [content].
 *
 * Gestures on the body (every pane but the map, whose touches pan and zoom it): tap toggles
 * maximize where the pane can be maximized (it shares a column), long-press toggles
 * fullscreen. A tap on a fullscreen card does nothing; leave fullscreen with a long-press,
 * the header icon or Back. Long-pressing the **header** (any pane, the map included) opens
 * the pane picker instead; the header has its own gestures, so the two never both fire.
 */
@Composable
fun DashboardCard(
    title: String,
    pane: Pane,
    mode: CardMode,
    layout: DashboardLayoutState,
    color: Color = AppTheme.colors.surface,
    chromeOverContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = rememberHaptics()
    // Long-press already gets the system's long-press buzz from combinedClickable.
    val body = if (pane == Pane.MAP) Modifier else Modifier.combinedClickable(
        onClickLabel = when {
            !layout.canMaximize(pane) -> null
            layout.maximized == pane -> "Restore $title"
            else -> "Maximize $title"
        },
        onLongClickLabel = if (mode == CardMode.Fullscreen) "Exit fullscreen" else "Fullscreen $title",
        onClick = {
            if (mode != CardMode.Fullscreen && layout.canMaximize(pane)) {
                layout.toggleMaximized(pane)
                haptics.toggle(on = layout.maximized == pane)
            }
        },
        onLongClick = { layout.toggleFullscreen(pane) },
    )
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(CardShape)
            .testTag("pane_${pane.name}")
            .semantics { stateDescription = mode.name },
        shape = CardShape,
        color = color,
        contentColor = AppTheme.colors.text,
        // OLED: a near-black card on black, told apart by a faint outline, not a grey fill.
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
                    modifier = Modifier.background(
                        Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), Color.Transparent)),
                    ),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                CardChrome(title = title, pane = pane, mode = mode, layout = layout)
                Column(Modifier.weight(1f).fillMaxWidth().then(body)) { content() }
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
 * Card header: title plus maximize (where the pane shares a column), fullscreen and swap,
 * wired straight to [layout]. Swap trades places with the pane above or below, or moves the
 * whole column across when the pane is alone in it. A tap on the header acts like a tap on
 * the body; a long-press opens the pane picker ([LocalPanePicker]).
 */
@Composable
fun CardChrome(
    title: String,
    pane: Pane,
    mode: CardMode,
    layout: DashboardLayoutState,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val picker = LocalPanePicker.current
    val canMaximize = layout.canMaximize(pane)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = null,
                onLongClickLabel = "Choose what this pane shows",
                onClick = {
                    if (mode != CardMode.Fullscreen && canMaximize) {
                        layout.toggleMaximized(pane)
                        haptics.toggle(on = layout.maximized == pane)
                    }
                },
                onLongClick = { picker?.invoke(pane) },
            )
            .testTag("header_${pane.name}")
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = PaneTitle,
            color = AppTheme.colors.textDim,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f),
        )
        if (layout.statusPane == pane) StatusCluster()
        if (canMaximize) {
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
        val fullscreen = layout.fullscreen == pane
        ChromeButton(
            icon = if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
            description = if (fullscreen) "Exit fullscreen" else "Fullscreen $title",
            tag = "fullscreen_${pane.name}",
        ) {
            layout.toggleFullscreen(pane)
            haptics.toggle(on = layout.fullscreen == pane)
        }
        if (!fullscreen) {
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

/** Opens the pane picker for a slot (by the pane in it). Provided by DashboardScreen; null in isolation. */
val LocalPanePicker = compositionLocalOf<((Pane) -> Unit)?> { null }

@Composable
internal fun ChromeButton(icon: ImageVector, description: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = description)
    }
}
