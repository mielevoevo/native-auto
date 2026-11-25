package com.nativeauto.ui.dashboard

import androidx.activity.compose.BackHandler
import com.nativeauto.ui.nav.LinkStatusPill
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.device.BatteryStatus
import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.nativeauto.ui.clock.ClockCard
import com.nativeauto.ui.clock.ClockViewModel
import com.nativeauto.ui.clock.formatClockTime
import java.time.ZoneId
import com.nativeauto.ui.components.AdaptiveRefreshRate
import com.nativeauto.ui.components.LocalMotionDemand
import com.nativeauto.ui.components.MotionDemand
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.settings.RefreshMode
import com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nativeauto.ui.components.LocalPaneMotion
import com.nativeauto.ui.components.LocalPanePicker
import com.nativeauto.ui.components.LocalStatusCorner
import com.nativeauto.ui.components.StatusCorner
import com.nativeauto.ui.history.TripHistoryScreen
import com.nativeauto.ui.call.CallCard
import com.nativeauto.ui.call.CallViewModel
import com.nativeauto.ui.changelog.ChangelogSheet
import com.nativeauto.ui.garage.GarageCard
import com.nativeauto.ui.garage.GarageScreen
import com.nativeauto.ui.garage.GaragePrompts
import com.nativeauto.ui.map.MapPane
import com.nativeauto.ui.media.MediaCard
import com.nativeauto.ui.nav.NextTurnCard
import com.nativeauto.ui.settings.SettingsSheet
import com.nativeauto.ui.speed.AutoStartPill
import com.nativeauto.ui.speed.SpeedCard
import com.nativeauto.ui.speed.rememberAutoStartBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/**
 * Entry point: starts from the layout saved last time (layout, panes per layout, Duo's side
 * and width), then saves every change to the user's own arrangement. Zoom modes and auto
 * layout are session-only, kept by rememberSaveable. Until DataStore answers (a few ms) only
 * the background shows, so the map is created once, already in its saved place.
 *
 * Auto layout reacts to navigation starting and ending ([AutoLayout]). The last state seen is
 * saved too, so recreating the activity mid-navigation isn't mistaken for a new start.
 */
@Composable
fun DashboardRoute(viewModel: DashboardViewModel = hiltViewModel()) {
    val saved by viewModel.initialLayout.collectAsStateWithLifecycle()
    val start = saved
    if (start == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    // 14b: the screen's orientation (the activity handles rotation itself, so this changes without recreation).
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val layout = rememberSaveable(saver = DashboardLayoutState.Saver) { start.toLayoutState(portrait) }
    // The saved state may come from the other orientation (a rotation while the app was away): turn it, no animation lost.
    LaunchedEffect(portrait) { layout.setPortrait(portrait) }
    LaunchedEffect(layout) {
        snapshotFlow { layout.toSaved() }
            .distinctUntilChanged()
            .collect(viewModel::saveLayout)
    }

    val navActive by viewModel.navigationActive.collectAsStateWithLifecycle()
    val autoEnabled by viewModel.autoLayout.collectAsStateWithLifecycle()
    val autoOn by rememberUpdatedState(autoEnabled)
    var lastNavActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(navActive) {
        if (navActive && !lastNavActive) AutoLayout.onNavigationStarted(layout, autoOn)
        if (!navActive && lastNavActive) AutoLayout.onNavigationEnded(layout)
        lastNavActive = navActive
    }

    // 14a: a call ringing or on gives the call card the lowest-priority slot (never a fullscreen one).
    val call: CallViewModel = hiltViewModel()
    val callActive by call.active.collectAsStateWithLifecycle()
    LaunchedEffect(callActive) { layout.setCallActive(callActive) }

    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val refresh by viewModel.refreshMode.collectAsStateWithLifecycle()
    val dragHold by viewModel.dragHoldMs.collectAsStateWithLifecycle()
    val cardLook by viewModel.cardLook.collectAsStateWithLifecycle()
    DashboardScreen(layout, battery, refresh, dragHold, cardLook)
}

/**
 * Every pane is a child of one [Layout], always composed, in a fixed order (rules #3 and #4:
 * nothing is ever removed or reparented, so a layout switch or swap never recreates a pane;
 * the map's only exception is its own sleep). Each child is placed in its pane's rectangle
 * ([DashboardLayoutState.rectOf]), animated with a spring. The animated rectangles are read
 * only in the layout pass, so a transition re-lays out the panes without recomposing them.
 *
 * Panes the layout doesn't show sit at zero size. Gutters are each slot's own padding
 * (rule #5), so a collapsed slot takes its gutter with it.
 */
@Composable
fun DashboardScreen(
    layout: DashboardLayoutState = rememberDashboardLayoutState(),
    battery: BatteryStatus? = null,
    refresh: RefreshMode = RefreshMode.AUTO,
    dragHoldMs: Long = DEFAULT_DRAG_HOLD_MS,
    cardLook: com.nativeauto.ui.components.CardLook = com.nativeauto.ui.components.CardLook(),
) {
    // 14b: everything below, sheets and dialogs included, knows the screen is portrait.
    CompositionLocalProvider(com.nativeauto.ui.components.LocalPortrait provides layout.portrait) {
        DashboardScreenBody(layout, battery, refresh, dragHoldMs, cardLook)
    }
}

@Composable
private fun DashboardScreenBody(
    layout: DashboardLayoutState,
    battery: BatteryStatus?,
    refresh: RefreshMode,
    dragHoldMs: Long,
    cardLook: com.nativeauto.ui.components.CardLook,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var garageOpen by rememberSaveable { mutableStateOf(false) }
    // A full-screen page over the panes (history, Garage): the map can sleep, back belongs to it.
    val covered = historyOpen || garageOpen
    var layoutsOpen by rememberSaveable { mutableStateOf(false) }
    var pickerFor by rememberSaveable { mutableStateOf<Pane?>(null) }
    // The small clock beside the battery, unless a clock pane is on screen (8c).
    val clock: ClockViewModel = hiltViewModel()
    val now by clock.nowMs.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val time = if (layout.isOnScreen(Pane.CLOCK)) null else formatClockTime(now, ZoneId.systemDefault(), is24).digits
    // Remembered, so the headers only recompose when what they show changes (once a minute).
    // 4.3a: the low-fuel pump lives with the battery in the header (was a lone icon on the speed card).
    val garageCard: com.nativeauto.ui.garage.GarageCardViewModel = hiltViewModel()
    val garage by garageCard.ui.collectAsStateWithLifecycle()
    val lowFuel = garage.lowFuel && !layout.isOnScreen(Pane.GARAGE)
    val corner = remember(battery, time, lowFuel) {
        StatusCorner(time, battery, lowFuel, onOpenLayouts = { layoutsOpen = true }, onOpenSettings = { settingsOpen = true })
    }
    // The call card (14a) is nobody's choice: nothing to pick for it.
    val picker = remember { { pane: Pane -> if (pane != Pane.CALL) pickerFor = pane } }

    BackHandler(enabled = !covered && (layout.fullscreen != null || layout.maximized != null)) { layout.back() }

    val rects = Pane.entries.map { pane ->
        animateRectAsState(layout.rectOf(pane), spring(stiffness = Spring.StiffnessMediumLow), label = "rect_${pane.name}")
    }

    // The State objects are the same on every recomposition, so the first ones captured stay right.
    val motion = remember(layout) { { pane: Pane -> rects[pane.ordinal].value != layout.rectOf(pane) } }

    // 12a: hold a card to lift it, drop it on another to swap (R3).
    val dragScope = rememberCoroutineScope()
    val drag = remember { PaneDragState(dragScope) }
    val hold by rememberUpdatedState(dragHoldMs)
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val mapHeaderPx = with(density) { MAP_DRAG_HEADER.toPx() }

    // 12a (R4): the fastest rate while panes slide, a card is held or dragged, or the map's camera moves.
    val demand = remember { MotionDemand() }
    AdaptiveRefreshRate(refresh, demand) { drag.active || Pane.entries.any(motion) }
    // 11c: the Garage card's "Open Garage". Remembered, so the panes don't recompose for a new lambda.
    val openGarage = remember { { garageOpen = true } }
    val headerZoom = remember { androidx.compose.runtime.mutableStateMapOf<Pane, Boolean>() }

    CompositionLocalProvider(
        LocalStatusCorner provides corner,
        LocalPanePicker provides picker,
        com.nativeauto.ui.components.LocalHeaderZoom provides headerZoom,
        com.nativeauto.ui.components.LocalCardLook provides cardLook,
        LocalPaneMotion provides motion,
        LocalMotionDemand provides demand,
        com.nativeauto.ui.components.LocalPortrait provides layout.portrait,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onSizeChanged { drag.screen = Size(it.width.toFloat(), it.height.toFloat()) },
        ) {
            Layout(
                content = {
                    Pane.entries.forEach { pane ->
                        PaneSlot(
                            Modifier
                                .testTag(SlotTag.of(pane))
                                // Before the layer: the gesture measures the finger in the slot's own,
                                // unmoved coordinates while the card follows it.
                                .paneDrag(
                                    pane = pane,
                                    state = drag,
                                    layout = layout,
                                    holdMs = { hold },
                                    // A fullscreen map's body keeps its long-press (a destination): it lifts from its header.
                                    // 4.7.1: the call card too, so a long press on End or Decline stays a press.
                                    allowAt = {
                                        val headerOnly = pane == Pane.CALL || (pane == Pane.MAP && layout.fullscreen == Pane.MAP)
                                        !headerOnly || it.y <= mapHeaderPx
                                    },
                                    onLift = { haptics.lift() },
                                    onArm = { dragScope.launch { haptics.lift(); delay(ARM_BUZZ_GAP_MS); haptics.lift() } },
                                    onPick = picker,
                                    onSwap = { haptics.tap() },
                                )
                                .graphicsLayer {
                                    val moving = drag.lifted == pane || drag.settling == pane
                                    val o = if (moving) drag.offset.value else Offset.Zero
                                    translationX = o.x
                                    translationY = o.y
                                    val s = if (drag.lifted == pane) LIFT_SCALE else 1f
                                    scaleX = s
                                    scaleY = s
                                    alpha = if (drag.lifted == pane && drag.armed) ARMED_ALPHA else 1f
                                },
                        ) {
                            PaneContent(pane, layout, covered = { historyOpen || garageOpen }, openGarage = openGarage)
                        }
                    }
                },
                measurePolicy = paneMeasurePolicy(rects) { i ->
                    val p = Pane.entries[i]
                    if (drag.lifted == p || drag.settling == p) 1f else 0f
                },
                modifier = Modifier.fillMaxSize(),
            )
            DragOverlay(drag, layout)
            // 14b: a call over a fullscreen pane is a slim pill (the card never takes a fullscreen slot).
            if (layout.fullscreen != null && Pane.CALL !in layout.view.panes) {
                com.nativeauto.ui.call.CallPill(Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }
            // Over the panes, never instead of them: the map stays composed underneath (rule #3).
            if (historyOpen) TripHistoryScreen(onClose = { historyOpen = false })
            if (garageOpen) GarageScreen(onClose = { garageOpen = false })
            // 9b: double back closes, only when back has nothing else to do. Sheets live in their
            // own windows and take back first.
            CloseOnDoubleBack(enabled = !covered && layout.fullscreen == null && layout.maximized == null)
            // 10b: a link shared from Google Maps being opened (or why it couldn't be).
            LinkStatusPill()
            // 13b: auto-start began a trip and no speed card is on screen to say so.
            val (autoStarted, dismissAutoStarted) = rememberAutoStartBanner()
            AutoStartPill(visible = autoStarted && !layout.isOnScreen(Pane.SPEED), onDismiss = dismissAutoStarted)
            // 11a: the away-km question and (debug) "which bike?".
            GaragePrompts()
            // 4.3c: the day's first open greets (range first); the changelog waits until it's gone.
            val greeting: com.nativeauto.greeting.GreetingViewModel = hiltViewModel()
            val greetingUp by greeting.showing.collectAsStateWithLifecycle()
            com.nativeauto.ui.greeting.GreetingSheet(onOpenGarage = openGarage, viewModel = greeting)
            // 13a: what changed, once after an update (waits while a trip or navigation runs).
            if (!greetingUp) ChangelogSheet()
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            layout,
            onDismiss = { settingsOpen = false },
            onOpenHistory = {
                settingsOpen = false
                historyOpen = true
            },
            onOpenGarage = {
                settingsOpen = false
                garageOpen = true
            },
        )
    }
    if (layoutsOpen) LayoutSwitcherSheet(layout, onDismiss = { layoutsOpen = false })
    pickerFor?.let { pane -> PanePickerSheet(layout, pane, ownZoom = headerZoom[pane] ?: true, onDismiss = { pickerFor = null }) }
}

@Composable
private fun PaneContent(pane: Pane, layout: DashboardLayoutState, covered: () -> Boolean, openGarage: () -> Unit) {
    when (pane) {
        // Off screen (or under the history / Garage) for a while, the map sleeps.
        Pane.MAP -> MapPane(layout, onScreen = layout.isOnScreen(Pane.MAP) && !covered())
        Pane.MEDIA -> MediaCard(layout.modeOf(Pane.MEDIA), layout)
        Pane.SPEED -> SpeedCard(layout.modeOf(Pane.SPEED), layout)
        Pane.NAV -> NextTurnCard(layout.modeOf(Pane.NAV), layout)
        Pane.CLOCK -> ClockCard(layout.modeOf(Pane.CLOCK), layout)
        Pane.GARAGE -> GarageCard(layout.modeOf(Pane.GARAGE), layout, onOpenGarage = openGarage)
        Pane.CALL -> CallCard(layout.modeOf(Pane.CALL), layout)
    }
}

/**
 * Places child `i` (in [Pane.entries] order) in `rects[i]`, given as fractions of the screen.
 * [zOf] is read here, in the layout pass, so a lifted card comes to the front without recomposing.
 */
private fun paneMeasurePolicy(rects: List<State<Rect>>, zOf: (Int) -> Float) = MeasurePolicy { measurables, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    // Rounding the edges, not the sizes, so neighbours share an edge exactly (no 1 px seams).
    val placed = measurables.mapIndexed { i, m ->
        val r = rects[i].value
        val left = (r.left * w).roundToInt().coerceIn(0, w)
        val right = (r.right * w).roundToInt().coerceIn(left, w)
        val top = (r.top * h).roundToInt().coerceIn(0, h)
        val bottom = (r.bottom * h).roundToInt().coerceIn(top, h)
        Triple(m.measure(Constraints.fixed(right - left, bottom - top)), left, top)
    }
    layout(w, h) { placed.forEachIndexed { i, (p, x, y) -> p.place(x, y, zIndex = zOf(i)) } }
}

/** The lifted card is drawn a little bigger. */
private const val LIFT_SCALE = 1.03f

/** An armed card (letting go opens the picker) is dimmed a little. */
private const val ARMED_ALPHA = 0.8f

/** The double buzz of arming: two pulses this far apart. */
private const val ARM_BUZZ_GAP_MS = 110L

/** The map lifts from its header strip (gutter + header row). */
private val MAP_DRAG_HEADER = 64.dp

/**
 * The hold ring around the finger and the outline of the card a drop would swap with. Everything
 * is read in the draw pass, so a drag redraws this layer only.
 */
@Composable
private fun DragOverlay(drag: PaneDragState, layout: DashboardLayoutState) {
    val colors = AppTheme.colors
    Canvas(Modifier.fillMaxSize().testTag("drag_overlay")) {
        drag.target?.let { t ->
            val r = layout.rectOf(t)
            val inset = 4.dp.toPx()
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = colors.accent,
                topLeft = Offset(r.left * size.width + inset + stroke / 2, r.top * size.height + inset + stroke / 2),
                size = Size(r.width * size.width - 2 * inset - stroke, r.height * size.height - 2 * inset - stroke),
                cornerRadius = CornerRadius(20.dp.toPx()),
                style = Stroke(stroke),
            )
        }
        // 13a stage one: the lifted card has a lit outline; stage two adds a ring filling in its centre.
        drag.lifted?.let { p ->
            val r = layout.rectOf(p)
            val o = drag.offset.value
            val left = r.left * size.width + o.x
            val top = r.top * size.height + o.y
            val w = r.width * size.width
            val h = r.height * size.height
            val inset = 4.dp.toPx()
            val stroke = 3.dp.toPx()
            drawRoundRect(
                color = colors.accent,
                topLeft = Offset(left + inset + stroke / 2, top + inset + stroke / 2),
                size = Size(w - 2 * inset - stroke, h - 2 * inset - stroke),
                cornerRadius = CornerRadius(20.dp.toPx()),
                style = Stroke(stroke),
            )
            val arm = drag.armProgress.value
            if (arm > 0f) {
                val radius = 40.dp.toPx()
                val ringStroke = 6.dp.toPx()
                val centre = Offset(left + w / 2, top + h / 2)
                drawCircle(colors.outline, radius, centre, style = Stroke(ringStroke))
                drawArc(colors.accent, -90f, 360f * arm, false, centre - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(ringStroke, cap = StrokeCap.Round))
            }
        }
        val progress = drag.holdProgress.value
        if (drag.holding != null && progress > 0f) {
            val radius = 36.dp.toPx()
            val stroke = 5.dp.toPx()
            val topLeft = drag.finger - Offset(radius, radius)
            drawCircle(colors.outline, radius, drag.finger, style = Stroke(stroke))
            drawArc(colors.accent, -90f, 360f * progress, false, topLeft, Size(radius * 2, radius * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

internal object SlotTag {
    fun of(pane: Pane) = "slot_${pane.name}"
}
