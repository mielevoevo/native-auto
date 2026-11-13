package com.nativeauto.ui.dashboard

import com.nativeauto.ui.components.sheetTag
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics

internal val LayoutPreset.label: String
    get() = when (this) {
        LayoutPreset.DUO -> "Duo"
        LayoutPreset.SIDE_BY_SIDE -> "Side by side"
        LayoutPreset.ROWS -> "Rows"
        LayoutPreset.QUAD -> "Quad"
    }

internal val Pane.label: String
    get() = when (this) {
        Pane.MAP -> "Map"
        Pane.MEDIA -> "Media"
        Pane.SPEED -> "Speed"
        Pane.NAV -> "Navigation"
        Pane.CLOCK -> "Clock"
        Pane.GARAGE -> "Garage"
    }

internal val Pane.icon: ImageVector
    get() = when (this) {
        Pane.MAP -> AppIcons.Map
        Pane.MEDIA -> AppIcons.MusicNote
        Pane.SPEED -> AppIcons.Speed
        Pane.NAV -> AppIcons.Directions
        Pane.CLOCK -> AppIcons.Clock
        Pane.GARAGE -> AppIcons.Fuel
    }

private val ThumbShape = RoundedCornerShape(12.dp)

/**
 * The layout switcher, from the status corner: a strip of big thumbnails, one tap switches.
 * Each thumbnail is drawn from the real geometry with the panes that layout would show.
 * Not a swipe: swipes already pan the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutSwitcherSheet(layout: DashboardLayoutState, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 900.dp,
        modifier = Modifier.sheetTag("layouts_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Layout", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            ) {
                LayoutPreset.entries.forEach { preset ->
                    val selected = layout.view.preset == preset
                    PresetThumb(
                        preset = preset,
                        panes = layout.assignments.getValue(preset),
                        bigOnLeft = layout.bigOnLeft,
                        split = layout.split,
                        selected = selected,
                    ) {
                        haptics.tick()
                        layout.setPreset(preset)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetThumb(
    preset: LayoutPreset,
    panes: List<Pane>,
    bigOnLeft: Boolean,
    split: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .clip(ThumbShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("preset_${preset.name}")
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier
                .size(width = 168.dp, height = 96.dp)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, outline), ThumbShape)
                .padding(5.dp),
        ) {
            val rects = computeRects(LayoutView(preset, panes), bigOnLeft, split)
            panes.forEach { pane ->
                val r = rects.getValue(pane)
                val gap = 2.dp
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * r.left + gap, y = maxHeight * r.top + gap)
                        .size(width = maxWidth * r.width - gap * 2, height = maxHeight * r.height - gap * 2)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(pane.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            preset.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The pane picker, from a long-press on a pane's header: what this slot shows. A pane that's
 * already elsewhere in the layout trades places with this one; any other replaces it (the
 * replaced pane leaves the layout, and a map left out goes to sleep).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanePickerSheet(layout: DashboardLayoutState, slotPane: Pane, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    val shown = layout.view.panes
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 900.dp,
        modifier = Modifier.sheetTag("picker_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show in this pane", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                // 13a: what a compact header no longer has room for lives here, under the hold.
                TextButton(
                    onClick = { haptics.tap(); layout.toggleFullscreen(slotPane); onDismiss() },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("pick_fullscreen"),
                ) { Text(if (layout.fullscreen == slotPane) "Exit fullscreen" else "Fullscreen") }
                if (layout.canMaximize(slotPane) && layout.fullscreen == null) {
                    TextButton(
                        onClick = { haptics.tap(); layout.toggleMaximized(slotPane); onDismiss() },
                        modifier = Modifier.heightIn(min = 56.dp).testTag("pick_maximize"),
                    ) { Text(if (layout.maximized == slotPane) "Restore" else "Maximize") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                Pane.entries.forEach { pane ->
                    val current = pane == slotPane
                    val elsewhere = !current && pane in shown
                    Surface(
                        onClick = {
                            if (current) {
                                onDismiss()
                            } else {
                                haptics.tick()
                                layout.assign(slotPane, pane)
                                onDismiss()
                            }
                        },
                        modifier = Modifier
                            .size(width = 128.dp, height = 104.dp)
                            .semantics { selected = current }
                            .testTag("pick_${pane.name}"),
                        shape = ThumbShape,
                        color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = if (current) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(pane.icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(pane.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                when {
                                    current -> "Showing"
                                    elsewhere -> "Swap places"
                                    else -> " "
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
