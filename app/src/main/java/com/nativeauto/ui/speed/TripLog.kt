package com.nativeauto.ui.speed

import com.nativeauto.settings.TotalPeriod
import com.nativeauto.garage.formatKmPerL
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.heightIn
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nativeauto.R
import com.nativeauto.history.TripOutcome
import com.nativeauto.garage.ServiceDue
import com.nativeauto.ui.garage.ServiceBadge
import com.nativeauto.history.TripRecord
import com.nativeauto.trip.TripState
import com.nativeauto.settings.TelemetryStat
import androidx.compose.ui.unit.Dp
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.LocalPortrait
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.history.TrackSketch
import com.nativeauto.ui.history.formatDay
import com.nativeauto.ui.history.formatDistance
import com.nativeauto.ui.theme.AppTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/** What the log face and the summary show, besides the trip figures in [SpeedometerUiState]. */
data class TripLogUi(
    /** 12b (R2): the period of the distance total, and its metres (saved trips + the live one). */
    val period: TotalPeriod = TotalPeriod.DAY,
    val periodM: Double = 0.0,
    val outcome: TripOutcome? = null,
    val endArmed: Boolean = false,
    /** The figures shown on the log face and the summary (9c). */
    val visible: Set<TelemetryStat> = TelemetryStat.entries.toSet() - TelemetryStat.DEFAULT_HIDDEN,
    /** 11c: a service due soon or overdue on the active bike (shown at trip start and end, never as a pop-up). */
    val reminder: ServiceDue? = null,
    /** 12b: the active bike, which the summary's "Bike's km/L" goes to (null: no prompt). */
    val bikeName: String? = null,
    /** 12b: the km/L given for this summary's trip, once saved. */
    val dashKmPerL: Double? = null,
)

/** The log face's buttons and the summary's dismiss. */
class TripLogActions(
    val start: () -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    /** First tap arms (⏹ turns red), the second ends; the card adds the haptics. */
    val end: () -> Unit,
    val dismissSummary: () -> Unit,
    /** 12b: the summary's optional "Bike's km/L". */
    val askDashKmPerL: () -> Unit = {},
) {
    companion object {
        val None = TripLogActions({}, {}, {}, {}, {})
    }
}

// ------------------------------------------------------------------ log face

/**
 * The log face (Rider-Feedback §2): music-player-style controls with no text labels
 * (▶ start / resume, ⏸ pause, ⏹ end, red on the first tap), the trip's figures (zero with no
 * trip), and (12b) the day / week / month distance total.
 */
@Composable
internal fun LogFace(mode: CardMode, ui: SpeedometerUiState, log: TripLogUi, actions: TripLogActions) {
    val portrait = LocalPortrait.current
    when {
        // 14b: portrait rows, four-stack and half-width cards all use the small centred face (a Wide row is only
        // 400 dp across there, too narrow for controls beside four figures).
        mode == CardMode.Compact || mode == CardMode.Mini || mode == CardMode.Narrow || (mode == CardMode.Wide && portrait) ->
            LogCompact(ui, log, actions, small = mode == CardMode.Narrow)
        mode == CardMode.Fullscreen && portrait -> Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                LogControls(ui.tripState, log.endArmed, actions)
            }
            StatGrid(ui.tripStats(log.visible, prefix = "log_"), columns = 2, tileHeight = 76.dp)
            log.reminder?.let { ServiceBadge(it, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PeriodTotal(log, ui, small = false) }
        }
        mode == CardMode.Wide -> Row(
            Modifier.requiredHeightIn(min = 112.dp).fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LogControls(ui.tripState, log.endArmed, actions) }
            Spacer(Modifier.width(24.dp))
            Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.SpaceBetween) {
                // Own tags: the pager keeps both faces composed side by side. A strip fits four.
                ui.tripStats(log.visible, prefix = "log_").take(4).forEach { InlineStat(it) }
            }
        }
        mode == CardMode.Expanded -> Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                LogControls(ui.tripState, log.endArmed, actions)
            }
            log.reminder?.let { ServiceBadge(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) }
            StatGrid(ui.tripStats(log.visible, prefix = "log_"), columns = if (portrait) 2 else 3, tileHeight = 76.dp)
            Spacer(Modifier.height(12.dp))
            PeriodTotal(log, ui, small = false)
        }
        else -> Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    LogControls(ui.tripState, log.endArmed, actions)
                }
                StatGrid(ui.tripStats(log.visible, prefix = "log_"), columns = 3, tileHeight = 88.dp)
                log.reminder?.let { ServiceBadge(it, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) }
            }
            Spacer(Modifier.width(24.dp))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { PeriodTotal(log, ui, small = false) }
        }
    }
}

/** The small log face: the controls, one line of figures, and the service badge or the period total. */
@Composable
private fun LogCompact(ui: SpeedometerUiState, log: TripLogUi, actions: TripLogActions, small: Boolean) {
    Column(
        Modifier.requiredHeightIn(min = 112.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogControls(ui.tripState, log.endArmed, actions, small = small)
        Spacer(Modifier.height(10.dp))
        Text(
            "${formatElapsed(ui.elapsed)} · ${"%.1f".format(ui.distance)} ${ui.unit.distanceLabel}",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = AppTheme.colors.textDim,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("log_line"),
        )
        // A due service outranks the total on the small face: it's rare and it matters.
        log.reminder?.let { ServiceBadge(it, Modifier.padding(top = 4.dp)) } ?: PeriodTotal(log, ui, small = true)
    }
}

/**
 * ▶ is Start (no trip, or after an end) or Resume (paused); ⏸ only while running; ⏹ while
 * running or paused. Buttons that can't act are dimmed, never hidden, so they don't move.
 */
@Composable
private fun LogControls(state: TripState, endArmed: Boolean, actions: TripLogActions, small: Boolean = false) {
    val running = state == TripState.Running
    val paused = state == TripState.Paused
    // 14b: a half-width card (about 190 dp) takes 54 dp buttons; everywhere else they stay glove-sized (64 dp).
    val size = if (small) 54.dp else 64.dp
    Row(horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LogButton(
            size = size,
            icon = AppIcons.Play,
            description = if (paused) R.string.log_resume else R.string.log_start,
            enabled = !running,
            tag = "log_start",
            onClick = if (paused) actions.resume else actions.start,
        )
        LogButton(size = size, icon = AppIcons.Pause, description = R.string.log_pause, enabled = running, tag = "log_pause", onClick = actions.pause)
        LogButton(
            size = size,
            icon = AppIcons.Stop,
            description = if (endArmed) R.string.log_end_confirm else R.string.log_end,
            enabled = running || paused,
            tag = "log_end",
            armed = endArmed,
            onClick = actions.end,
        )
    }
}

@Composable
private fun LogButton(
    size: Dp,
    icon: ImageVector,
    @StringRes description: Int,
    enabled: Boolean,
    tag: String,
    armed: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = if (armed) {
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = AppTheme.colors.red,
            contentColor = AppTheme.colors.bg,
        )
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        // Gloves: well over the 56 dp minimum.
        modifier = Modifier.size(size).testTag(tag),
    ) {
        Icon(icon, contentDescription = stringResource(description), modifier = Modifier.size(if (size < 60.dp) 28.dp else 32.dp))
    }
}

/** 12b (R2): "Today 42.3 km", in the speed unit's distance, saved trips plus the live one. */
@Composable
private fun PeriodTotal(log: TripLogUi, ui: SpeedometerUiState, small: Boolean) {
    Text(
        "${log.period.lead} ${formatDistance(log.periodM, ui.unit)}",
        style = (if (small) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge).copy(fontFeatureSettings = "tnum"),
        color = if (small) AppTheme.colors.textDim else AppTheme.colors.text,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(top = if (small) 4.dp else 0.dp).testTag("log_period"),
    )
}

// ------------------------------------------------------------------ summary

/**
 * The end-of-trip summary (Rider-Feedback §2), inside the speed card: the frozen figures count
 * up quickly, with the route sketch when the trip was saved. A tap or a swipe dismisses it,
 * which zeroes the trip until the next Start.
 */
@Composable
internal fun TripSummary(mode: CardMode, ui: SpeedometerUiState, log: TripLogUi, onDismiss: () -> Unit, onAskDash: () -> Unit = {}) {
    val outcome = log.outcome
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(COUNT_UP_MS, easing = FastOutSlowInEasing)) }
    val p = progress.value
    val figures = ui.tripStats(log.visible, prefix = "sum_", progress = p)
    // The compact line always shows distance and time, whatever is hidden.
    val distanceText = "%.1f".format(ui.distance * p)
    val timeText = formatElapsed((ui.elapsed.inWholeMilliseconds * p).roundToLong().milliseconds)
    val saved = (outcome as? TripOutcome.Saved)?.trip
    val portrait = LocalPortrait.current
    val dragged = remember { floatArrayOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.card)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .draggable(
                state = rememberDraggableState { dragged[0] += it },
                orientation = Orientation.Horizontal,
                onDragStarted = { dragged[0] = 0f },
                onDragStopped = { if (abs(dragged[0]) > SWIPE_PX) onDismiss() },
            )
            .semantics { contentDescription = "Trip summary" }
            .testTag("trip_summary"),
    ) {
        when {
            // 13a: the compact form puts the dash km/L button beside the figures, not under them, so a
            // small card still has room for it (and for the service badge below). 14b: portrait rows, the four-stack
            // and half-width cards use it too (a half-width card puts the button under the figures).
            mode == CardMode.Compact || mode == CardMode.Mini || mode == CardMode.Narrow || (mode == CardMode.Wide && portrait) -> Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val stacked = mode == CardMode.Narrow
                val figuresColumn = @Composable {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SummaryTitle()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "$distanceText ${ui.unit.distanceLabel} · $timeText",
                            style = (if (stacked) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).copy(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (stacked) {
                    figuresColumn()
                    if (saved != null && log.bikeName != null) DashTile(log, onAskDash, Modifier.padding(top = 8.dp))
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    figuresColumn()
                    if (saved != null && log.bikeName != null) {
                        Spacer(Modifier.width(16.dp))
                        DashTile(log, onAskDash)
                    }
                }
                if (outcome == TripOutcome.TooShort) SummaryNote(R.string.summary_too_short)
                log.reminder?.let { ServiceBadge(it, Modifier.padding(top = 6.dp)) }
            }
            // 4.3a: starts right under the header like the other faces (it sat low in the card), and the dash km/L
            // is a tile in the figures row instead of a small button under it.
            mode == CardMode.Wide -> Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.4f)) {
                    SummaryTitle()
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        figures.take(4).forEach { InlineStat(it) }
                        if (saved != null) DashTile(log, onAskDash)
                    }
                }
                Spacer(Modifier.width(16.dp))
                SummaryRoute(saved, outcome, Modifier.weight(1f).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                SummaryTitle()
                Spacer(Modifier.height(12.dp))
                StatGrid(figures, columns = if (portrait) 2 else if (mode == CardMode.Fullscreen) 4 else 3, tileHeight = 76.dp)
                Spacer(Modifier.height(12.dp))
                SummaryRoute(saved, outcome, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        log.reminder?.let { ServiceBadge(it) }
                        SummaryNote(R.string.summary_close)
                    }
                    if (saved != null) DashTile(log, onAskDash)
                }
            }
        }
    }
}

/**
 * 12b (R1): the optional "what did the bike's dash say?" after a saved ride. Only with a bike in
 * the Garage; once answered it shows the figure instead. Never asked again for the same trip.
 * 4.3a: a figure-sized tile that sits with the figures (label over value) in every layout; before an
 * answer it has a quiet plate so it reads as a button (DESIGN.md G2), after it it is a plain figure.
 */
@Composable
private fun DashTile(log: TripLogUi, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    if (log.bikeName == null) return
    val given = log.dashKmPerL
    val content = @Composable { value: String ->
        Column {
            Text(
                "DASH KM/L",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    if (given != null) {
        Box(modifier.testTag("sum_dash_saved")) { content(formatKmPerL(given).removeSuffix(" km/L")) }
    } else {
        Surface(
            onClick = onAsk,
            shape = RoundedCornerShape(14.dp),
            color = AppTheme.colors.buttonQuiet,
            modifier = modifier.heightIn(min = 56.dp).semantics { contentDescription = "Add the bike's km/L" }.testTag("sum_dash"),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) { content("+ Add") }
        }
    }
}

/** The one cyan element on the card while the summary is up (8c's theme rule). */
@Composable
private fun SummaryTitle() {
    Text(
        stringResource(R.string.summary_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = AppTheme.colors.accent,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun SummaryNote(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.textDim,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SummaryRoute(saved: TripRecord?, outcome: TripOutcome?, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(AppTheme.colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        when {
            saved != null -> TrackSketch(saved, Modifier.fillMaxSize().padding(12.dp).testTag("summary_route"))
            outcome == TripOutcome.TooShort -> SummaryNote(R.string.summary_too_short)
        }
    }
}

/** Two small dots under the card: which face is showing. */
@Composable
internal fun FaceDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.log_faces)
    Row(
        modifier.semantics { contentDescription = description }.testTag("face_dots"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { i ->
            Box(
                Modifier.size(6.dp).clip(CircleShape)
                    .background(if (i == current) AppTheme.colors.text else AppTheme.colors.outline),
            )
        }
    }
}

private const val COUNT_UP_MS = 700
private const val SWIPE_PX = 80f
