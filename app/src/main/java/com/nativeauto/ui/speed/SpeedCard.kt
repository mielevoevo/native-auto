package com.nativeauto.ui.speed

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nativeauto.ui.components.rememberHaptics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import com.nativeauto.ui.theme.AppTheme
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.location.TripService
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.TripState
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.cardModeTransition
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.permissions.LocalLocationAccess
import java.util.Locale
import kotlin.time.Duration

/**
 * Compact: the big speed only. Expanded / Fullscreen: speed plus the full telemetry grid.
 * Content is chosen by [mode], never by measured size (rule #6); sizes inside each mode are
 * fixed, so a card that is still growing or shrinking just clips instead of re-flowing.
 */
@Composable
fun SpeedCard(
    mode: CardMode,
    layout: DashboardLayoutState,
    viewModel: SpeedometerViewModel = hiltViewModel(),
    logViewModel: TripLogViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val recent by logViewModel.recent.collectAsStateWithLifecycle()
    val outcome by logViewModel.outcome.collectAsStateWithLifecycle()
    val endArmed by logViewModel.endArmed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val actions = remember(logViewModel) {
        TripLogActions(
            start = { haptics.tap(); TripService.start(context) },
            pause = { haptics.tap(); logViewModel.pause() },
            resume = { haptics.tap(); TripService.resume(context) },
            end = { if (logViewModel.onEndTapped()) haptics.confirm() else haptics.tap() },
            dismissSummary = { haptics.tap(); logViewModel.dismissSummary() },
        )
    }
    SpeedCardContent(mode, layout, ui, TripLogUi(recent, outcome, endArmed), actions)
}

/**
 * Stateless body, driven entirely by [ui] and [log]; previews and tests can call it directly.
 * Two faces, switched by a horizontal swipe (9b): the speed face (page 0) and the log face
 * (page 1). While the trip is Ended, the summary covers both.
 */
@Composable
fun SpeedCardContent(
    mode: CardMode,
    layout: DashboardLayoutState,
    ui: SpeedometerUiState,
    log: TripLogUi = TripLogUi(),
    actions: TripLogActions = TripLogActions.None,
) {
    DashboardCard(title = "Speed", pane = Pane.SPEED, mode = mode, layout = layout) {
        val pager = rememberPagerState { FACES }
        // After a trip ends, dismissing the summary lands on the log face, ready for the next Start.
        LaunchedEffect(ui.tripState) { if (ui.tripState == TripState.Ended) pager.scrollToPage(LOG_FACE) }
        // Clip so content overflowing a still-small card never draws over the header.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("speed_faces")) { page ->
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = { cardModeTransition() },
                    contentAlignment = Alignment.Center,
                    label = "speedMode",
                    modifier = Modifier.fillMaxSize(),
                ) { m ->
                    if (page == LOG_FACE) {
                        LogFace(m, ui, log, actions)
                    } else when (m) {
                        CardMode.Compact -> SpeedCompact(ui)
                        CardMode.Wide -> SpeedWide(ui)
                        CardMode.Expanded -> SpeedExpanded(ui)
                        CardMode.Fullscreen -> SpeedFullscreen(ui)
                    }
                }
            }
            FaceDots(pager.currentPage, FACES, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
            if (ui.tripState == TripState.Ended) TripSummary(mode, ui, log.outcome, actions.dismissSummary)
        }
    }
}

private const val FACES = 2
private const val LOG_FACE = 1

@Composable
private fun SpeedCompact(ui: SpeedometerUiState) {
    // Natural height as a floor: a collapsing card clips the hero rather than squashing it.
    Box(Modifier.requiredHeightIn(min = 112.dp).fillMaxSize(), contentAlignment = Alignment.Center) {
        HeroArea(ui, size = 96.sp, explain = false)
    }
}

/**
 * The Rows layout: a full-width, half-height strip. The hero on the left, then one line of
 * the trip's main figures, so the strip says as much as an expanded card without the grid.
 */
@Composable
private fun SpeedWide(ui: SpeedometerUiState) {
    Row(
        modifier = Modifier.requiredHeightIn(min = 112.dp).fillMaxSize().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            HeroArea(ui, size = 96.sp, explain = false)
        }
        Spacer(Modifier.width(24.dp))
        Row(
            modifier = Modifier.weight(1.4f).testTag("speed_inline_stats"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ui.inlineStats().forEach { InlineStat(it) }
        }
    }
}

/** The four figures a rider checks most; the rest are a maximize away. */
internal fun SpeedometerUiState.inlineStats(): List<Stat> = telemetry().filter {
    it.tag in setOf("stat_max", "stat_avg", "stat_distance", "stat_elapsed")
}.sortedBy { listOf("stat_max", "stat_avg", "stat_distance", "stat_elapsed").indexOf(it.tag) }

@Composable
internal fun InlineStat(stat: Stat) {
    Column(Modifier.testTag("inline_${stat.tag}")) {
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (stat.unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = stat.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

@Composable
private fun SpeedExpanded(ui: SpeedometerUiState) {
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            HeroArea(ui, size = 120.sp)
        }
        TelemetryGrid(ui, columns = 3, tileHeight = 76.dp)
    }
}

@Composable
private fun SpeedFullscreen(ui: SpeedometerUiState) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) {
            HeroArea(ui, size = 180.sp)
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f)) {
            TelemetryGrid(ui, columns = 2, tileHeight = 88.dp)
        }
    }
}

/**
 * The hero, or, when there can be no live speed, a smaller "--" with the way out:
 * access denied → permission dialog, location off → settings, paused → Resume,
 * (9b: no prompt for idle or ended; the log face and the summary handle those).
 * [explain] adds a one-line reason where the card has room for it (not in Compact).
 */
@Composable
private fun HeroArea(ui: SpeedometerUiState, size: TextUnit, explain: Boolean = true) {
    val context = LocalContext.current
    val access = LocalLocationAccess.current
    val prompt = when {
        ui.gps == GpsStatus.PermissionDenied ->
            HeroPrompt(R.string.location_denied, R.string.location_allow, access.requestPermission)
        ui.gps == GpsStatus.Disabled ->
            HeroPrompt(R.string.location_off, R.string.location_turn_on, access.openLocationSettings)
        ui.tripState == TripState.Paused ->
            HeroPrompt(if (ui.autoPaused) R.string.trip_auto_paused else R.string.trip_paused, R.string.trip_resume) {
                TripService.resume(context)
            }
        // Idle and Ended have no prompt (9b): the log face holds Start, and the summary covers an end.
        else -> null
    }
    if (prompt == null) {
        SpeedHero(ui, size)
        return
    }
    Column(
        modifier = Modifier.wrapContentSize(unbounded = true),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpeedHero(ui, size * 0.6f)
        if (explain) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(prompt.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = prompt.onClick, modifier = Modifier.testTag("hero_action")) {
            Text(stringResource(prompt.action), maxLines = 1, softWrap = false)
        }
    }
}

/** Why there's no live speed, and the one action that fixes it. */
private class HeroPrompt(@StringRes val message: Int, @StringRes val action: Int, val onClick: () -> Unit)

/**
 * Number + unit. `wrapContentSize(unbounded = true)` measures the hero at its natural size
 * even while its slot is still tiny mid-animation; the card clips the overflow instead of
 * squashing the text.
 */
@Composable
private fun SpeedHero(ui: SpeedometerUiState, size: TextUnit) {
    Row(
        modifier = Modifier.wrapContentSize(unbounded = true).testTag("speed_hero"),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = ui.speed?.toString() ?: "--",
            style = heroStyle(size),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline().testTag("speed_value"),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = ui.unit.speedLabel,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

private fun heroStyle(size: TextUnit) = TextStyle(
    fontSize = size,
    lineHeight = size,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

internal data class Stat(val tag: String, val label: String, val value: String, val unit: String?)

internal fun SpeedometerUiState.telemetry(): List<Stat> = listOf(
    Stat("stat_max", "MAX", maxSpeed.toString(), unit.speedLabel),
    Stat("stat_avg", "AVERAGE", avgSpeed.toString(), unit.speedLabel),
    Stat("stat_moving_avg", "MOVING AVG", movingAvgSpeed.toString(), unit.speedLabel),
    Stat("stat_elapsed", "ELAPSED", formatElapsed(elapsed), null),
    Stat("stat_distance", "DISTANCE", "%.1f".format(distance), unit.distanceLabel),
    Stat("stat_gps", "GPS", statusLabel, null),
)

@Composable
internal fun TelemetryGrid(ui: SpeedometerUiState, columns: Int, tileHeight: Dp, tagPrefix: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.telemetry().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { StatTile(it.copy(tag = tagPrefix + it.tag), Modifier.weight(1f).height(tileHeight)) }
            }
        }
    }
}

@Composable
internal fun StatTile(stat: Stat, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.buttonQuiet)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(stat.tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stat.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (stat.unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stat.unit,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** `H:MM:SS` once past an hour, otherwise `M:SS`. */
internal fun formatElapsed(elapsed: Duration): String {
    val total = elapsed.inWholeSeconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** The GPS tile: the trip's state when it isn't running, since "Lost" would mislead. */
internal val SpeedometerUiState.statusLabel: String
    get() = when (tripState) {
        TripState.Paused -> "Paused"
        TripState.Ended -> "Ended"
        TripState.Idle, TripState.Running -> gps.label
    }

/** Kept to 6 characters or fewer: longer words clip in the Expanded grid's narrow tiles. */
internal val GpsStatus.label: String
    get() = when (this) {
        GpsStatus.Searching -> "No fix"
        GpsStatus.Fix -> "Locked"
        GpsStatus.Stale -> "Lost"
        GpsStatus.Disabled -> "Off"
        GpsStatus.PermissionDenied -> "Denied"
    }
