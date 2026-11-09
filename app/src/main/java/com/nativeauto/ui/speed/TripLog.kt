package com.nativeauto.ui.speed

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
import com.nativeauto.ui.components.AppIcons
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
    val recent: List<TripRecord> = emptyList(),
    val outcome: TripOutcome? = null,
    val endArmed: Boolean = false,
    /** The figures shown on the log face and the summary (9c). */
    val visible: Set<TelemetryStat> = TelemetryStat.entries.toSet() - TelemetryStat.DEFAULT_HIDDEN,
    /** 11c: a service due soon or overdue on the active bike (shown at trip start and end, never as a pop-up). */
    val reminder: ServiceDue? = null,
)

/** The log face's buttons and the summary's dismiss. */
class TripLogActions(
    val start: () -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    /** First tap arms (⏹ turns red), the second ends; the card adds the haptics. */
    val end: () -> Unit,
    val dismissSummary: () -> Unit,
) {
    companion object {
        val None = TripLogActions({}, {}, {}, {}, {})
    }
}

// ------------------------------------------------------------------ log face

/**
 * The log face (Rider-Feedback §2): music-player-style controls with no text labels
 * (▶ start / resume, ⏸ pause, ⏹ end, red on the first tap), the trip's figures (zero with no
 * trip), and the last rides where the card has room.
 */
@Composable
internal fun LogFace(mode: CardMode, ui: SpeedometerUiState, log: TripLogUi, actions: TripLogActions) {
    when (mode) {
        CardMode.Compact -> Column(
            Modifier.requiredHeightIn(min = 112.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogControls(ui.tripState, log.endArmed, actions)
            Spacer(Modifier.height(10.dp))
            Text(
                "${formatElapsed(ui.elapsed)} · ${"%.1f".format(ui.distance)} ${ui.unit.distanceLabel}",
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = AppTheme.colors.textDim,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("log_line"),
            )
            log.reminder?.let { ServiceBadge(it, Modifier.padding(top = 4.dp)) }
        }
        CardMode.Wide -> Row(
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
        CardMode.Expanded -> Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                LogControls(ui.tripState, log.endArmed, actions)
            }
            log.reminder?.let { ServiceBadge(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) }
            StatGrid(ui.tripStats(log.visible, prefix = "log_"), columns = 3, tileHeight = 76.dp)
            Spacer(Modifier.height(12.dp))
            RecentRides(log.recent, ui, Modifier.weight(1f))
        }
        CardMode.Fullscreen -> Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    LogControls(ui.tripState, log.endArmed, actions)
                }
                StatGrid(ui.tripStats(log.visible, prefix = "log_"), columns = 3, tileHeight = 88.dp)
                log.reminder?.let { ServiceBadge(it, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp)) }
            }
            Spacer(Modifier.width(24.dp))
            RecentRides(log.recent, ui, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * ▶ is Start (no trip, or after an end) or Resume (paused); ⏸ only while running; ⏹ while
 * running or paused. Buttons that can't act are dimmed, never hidden, so they don't move.
 */
@Composable
private fun LogControls(state: TripState, endArmed: Boolean, actions: TripLogActions) {
    val running = state == TripState.Running
    val paused = state == TripState.Paused
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LogButton(
            icon = AppIcons.Play,
            description = if (paused) R.string.log_resume else R.string.log_start,
            enabled = !running,
            tag = "log_start",
            onClick = if (paused) actions.resume else actions.start,
        )
        LogButton(AppIcons.Pause, R.string.log_pause, enabled = running, tag = "log_pause", onClick = actions.pause)
        LogButton(
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
        modifier = Modifier.size(64.dp).testTag(tag),
    ) {
        Icon(icon, contentDescription = stringResource(description), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun RecentRides(recent: List<TripRecord>, ui: SpeedometerUiState, modifier: Modifier) {
    Column(modifier.testTag("log_recent"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.log_recent), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textDim)
        if (recent.isEmpty()) {
            Text(stringResource(R.string.log_no_rides), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim)
        }
        recent.forEach { trip ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDay(trip.startedAtMs),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDistance(trip.distanceM, ui.unit),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ summary

/**
 * The end-of-trip summary (Rider-Feedback §2), inside the speed card: the frozen figures count
 * up quickly, with the route sketch when the trip was saved. A tap or a swipe dismisses it,
 * which zeroes the trip until the next Start.
 */
@Composable
internal fun TripSummary(mode: CardMode, ui: SpeedometerUiState, log: TripLogUi, onDismiss: () -> Unit) {
    val outcome = log.outcome
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(COUNT_UP_MS, easing = FastOutSlowInEasing)) }
    val p = progress.value
    val figures = ui.tripStats(log.visible, prefix = "sum_", progress = p)
    // The compact line always shows distance and time, whatever is hidden.
    val distanceText = "%.1f".format(ui.distance * p)
    val timeText = formatElapsed((ui.elapsed.inWholeMilliseconds * p).roundToLong().milliseconds)
    val saved = (outcome as? TripOutcome.Saved)?.trip
    val dragged = remember { floatArrayOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.surface)
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
        when (mode) {
            CardMode.Compact -> Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SummaryTitle()
                Spacer(Modifier.height(8.dp))
                Text(
                    "$distanceText ${ui.unit.distanceLabel} · $timeText",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    softWrap = false,
                )
                if (outcome == TripOutcome.TooShort) SummaryNote(R.string.summary_too_short)
                log.reminder?.let { ServiceBadge(it, Modifier.padding(top = 6.dp)) }
            }
            CardMode.Wide -> Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.4f)) {
                    SummaryTitle()
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { figures.take(4).forEach { InlineStat(it) } }
                }
                Spacer(Modifier.width(16.dp))
                SummaryRoute(saved, outcome, Modifier.weight(1f).fillMaxHeight())
            }
            CardMode.Expanded, CardMode.Fullscreen -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                SummaryTitle()
                Spacer(Modifier.height(12.dp))
                StatGrid(figures, columns = if (mode == CardMode.Fullscreen) 4 else 3, tileHeight = 76.dp)
                Spacer(Modifier.height(12.dp))
                SummaryRoute(saved, outcome, Modifier.weight(1f).fillMaxWidth())
                log.reminder?.let { ServiceBadge(it, Modifier.padding(top = 6.dp)) }
                SummaryNote(R.string.summary_close)
            }
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
        color = AppTheme.colors.cyan,
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
