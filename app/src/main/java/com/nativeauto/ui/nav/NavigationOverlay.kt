package com.nativeauto.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativeauto.nav.NavFailure
import com.nativeauto.nav.NavState
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.ui.components.rememberHaptics

private val CardShape = RoundedCornerShape(20.dp)

/**
 * The next turn, top of the map: arrow, distance, road. The distance is the biggest thing on
 * it, since it's what a rider checks between glances at the road.
 */
@Composable
internal fun NavigationBanner(state: NavState.Navigating, unit: SpeedUnit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("nav_banner"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        val instruction = state.instruction
        if (state.rerouting || (state.offRoute && instruction == null)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text("Rerouting…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            return@Surface
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (instruction != null) {
                ManeuverGlyph(instruction, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(60.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    formatNavDistance(state.distanceToManeuverM, unit),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.testTag("nav_distance"),
                )
                Text(
                    instruction?.bannerText() ?: "Follow the route",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("nav_instruction"),
                )
            }
        }
    }
}

/** Bottom of the map while navigating: what's left, and when you'll be there. */
@Composable
internal fun NavigationSummary(state: NavState.Navigating, unit: SpeedUnit, nowMs: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag("nav_summary"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                formatEta(nowMs, state.durationRemainingS),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${formatNavDuration(state.durationRemainingS)} · ${formatNavDistance(state.distanceRemainingM, unit)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The route is ready: where, how far, how long; Start or Cancel (both full size for gloves). */
@Composable
internal fun RoutePreviewCard(
    state: NavState.Preview,
    unit: SpeedUnit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Surface(
        modifier = modifier.fillMaxWidth().testTag("nav_preview"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        // One row: landscape has little height, and the route must stay visible above it.
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.destination.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatNavDuration(state.plan.durationS)} · ${formatNavDistance(state.plan.distanceM, unit)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.testTag("nav_preview_summary"),
                )
                state.destination.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { haptics.tap(); onCancel() }, modifier = Modifier.heightIn(min = 56.dp).testTag("nav_cancel")) {
                Text("Cancel", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { haptics.confirm(); onStart() },
                modifier = Modifier.heightIn(min = 56.dp).widthIn(min = 112.dp).testTag("nav_start"),
            ) {
                Text("Start", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Planning, failed, or arrived: one line of status and the way out. */
@Composable
internal fun NavStatusCard(
    state: NavState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val (title, detail) = when (state) {
        is NavState.Routing -> "Finding a route…" to state.destination.name
        is NavState.Arrived -> "You've arrived" to state.destination.name
        is NavState.Failed -> when (state.reason) {
            NavFailure.NoLocation -> "Waiting for GPS" to "No position yet to route from"
            NavFailure.Offline -> "Can't reach the route server" to "Check the connection and retry"
            NavFailure.NoRoute -> "No route found" to "Nothing rideable to ${state.destination.name}"
        }
        else -> return
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("nav_status"),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state is NavState.Routing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state is NavState.Failed) {
                TextButton(onClick = { haptics.tap(); onRetry() }, modifier = Modifier.heightIn(min = 56.dp).testTag("nav_retry")) { Text("Retry") }
            }
            TextButton(onClick = { haptics.tap(); onDismiss() }, modifier = Modifier.heightIn(min = 56.dp).testTag("nav_dismiss")) {
                Text(if (state is NavState.Arrived) "Done" else "Cancel")
            }
        }
    }
}

/**
 * 13b: a compact map's whole navigation readout: the turn arrow and the distance to it in one slim
 * chip. The road name, ETA and totals are for the bigger map or the Navigation pane.
 */
@Composable
internal fun NavigationChip(state: NavState.Navigating, unit: SpeedUnit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag("nav_chip"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        val instruction = state.instruction
        Row(Modifier.padding(start = 10.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.rerouting || (state.offRoute && instruction == null)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(10.dp))
                Text("Rerouting…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            } else {
                if (instruction != null) {
                    ManeuverGlyph(instruction, MaterialTheme.colorScheme.onPrimaryContainer, Modifier.size(36.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    formatNavDistance(state.distanceToManeuverM, unit),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("nav_chip_distance"),
                )
            }
        }
    }
}
