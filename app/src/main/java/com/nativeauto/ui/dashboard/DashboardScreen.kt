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
import com.nativeauto.ui.clock.ClockCard
import com.nativeauto.ui.clock.ClockViewModel
import com.nativeauto.ui.clock.formatClockTime
import java.time.ZoneId
import com.nativeauto.ui.components.HighRefreshWhileMoving
import com.nativeauto.ui.components.LocalPaneMotion
import com.nativeauto.ui.components.LocalPanePicker
import com.nativeauto.ui.components.LocalStatusCorner
import com.nativeauto.ui.components.StatusCorner
import com.nativeauto.ui.history.TripHistoryScreen
import com.nativeauto.ui.map.MapPane
import com.nativeauto.ui.media.MediaCard
import com.nativeauto.ui.nav.NextTurnCard
import com.nativeauto.ui.settings.SettingsSheet
import com.nativeauto.ui.speed.SpeedCard
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
    val layout = rememberSaveable(saver = DashboardLayoutState.Saver) { start.toLayoutState() }
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

    val battery by viewModel.battery.collectAsStateWithLifecycle()
    DashboardScreen(layout, battery)
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
fun DashboardScreen(layout: DashboardLayoutState = rememberDashboardLayoutState(), battery: BatteryStatus? = null) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var layoutsOpen by rememberSaveable { mutableStateOf(false) }
    var pickerFor by rememberSaveable { mutableStateOf<Pane?>(null) }
    // The small clock beside the battery, unless a clock pane is on screen (8c).
    val clock: ClockViewModel = hiltViewModel()
    val now by clock.nowMs.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val time = if (layout.isOnScreen(Pane.CLOCK)) null else formatClockTime(now, ZoneId.systemDefault(), is24).digits
    // Remembered, so the headers only recompose when what they show changes (once a minute).
    val corner = remember(battery, time) {
        StatusCorner(time, battery, onOpenLayouts = { layoutsOpen = true }, onOpenSettings = { settingsOpen = true })
    }
    val picker = remember { { pane: Pane -> pickerFor = pane } }

    BackHandler(enabled = !historyOpen && (layout.fullscreen != null || layout.maximized != null)) { layout.back() }

    val rects = Pane.entries.map { pane ->
        animateRectAsState(layout.rectOf(pane), spring(stiffness = Spring.StiffnessMediumLow), label = "rect_${pane.name}")
    }

    // The State objects are the same on every recomposition, so the first ones captured stay right.
    val motion = remember(layout) { { pane: Pane -> rects[pane.ordinal].value != layout.rectOf(pane) } }

    HighRefreshWhileMoving(motion)

    CompositionLocalProvider(LocalStatusCorner provides corner, LocalPanePicker provides picker, LocalPaneMotion provides motion) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Layout(
                content = {
                    Pane.entries.forEach { pane ->
                        PaneSlot(Modifier.testTag(SlotTag.of(pane))) { PaneContent(pane, layout) { historyOpen } }
                    }
                },
                measurePolicy = paneMeasurePolicy(rects),
                modifier = Modifier.fillMaxSize(),
            )
            // Over the panes, never instead of them: the map stays composed underneath (rule #3).
            if (historyOpen) TripHistoryScreen(onClose = { historyOpen = false })
            // 9b: double back closes, only when back has nothing else to do. Sheets live in their
            // own windows and take back first.
            CloseOnDoubleBack(enabled = !historyOpen && layout.fullscreen == null && layout.maximized == null)
            // 10b: a link shared from Google Maps being opened (or why it couldn't be).
            LinkStatusPill()
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
        )
    }
    if (layoutsOpen) LayoutSwitcherSheet(layout, onDismiss = { layoutsOpen = false })
    pickerFor?.let { pane -> PanePickerSheet(layout, pane, onDismiss = { pickerFor = null }) }
}

@Composable
private fun PaneContent(pane: Pane, layout: DashboardLayoutState, historyOpen: () -> Boolean) {
    when (pane) {
        // Off screen (or under the history) for a while, the map sleeps.
        Pane.MAP -> MapPane(layout, onScreen = layout.isOnScreen(Pane.MAP) && !historyOpen())
        Pane.MEDIA -> MediaCard(layout.modeOf(Pane.MEDIA), layout)
        Pane.SPEED -> SpeedCard(layout.modeOf(Pane.SPEED), layout)
        Pane.NAV -> NextTurnCard(layout.modeOf(Pane.NAV), layout)
        Pane.CLOCK -> ClockCard(layout.modeOf(Pane.CLOCK), layout)
    }
}

/** Places child `i` (in [Pane.entries] order) in `rects[i]`, given as fractions of the screen. */
private fun paneMeasurePolicy(rects: List<State<Rect>>) = MeasurePolicy { measurables, constraints ->
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
    layout(w, h) { placed.forEach { (p, x, y) -> p.place(x, y) } }
}

internal object SlotTag {
    fun of(pane: Pane) = "slot_${pane.name}"
}
