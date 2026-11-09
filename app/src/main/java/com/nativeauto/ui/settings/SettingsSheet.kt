package com.nativeauto.ui.settings

import com.nativeauto.ui.components.sheetTag
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.FilterChipDefaults
import com.nativeauto.settings.TelemetryStat
import com.nativeauto.ui.theme.AppTheme
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.BuildConfig
import com.nativeauto.R
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.ui.theme.appSegmentedColors
import com.nativeauto.ui.theme.appSwitchColors
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.DashboardLayoutState
import kotlin.math.abs

/**
 * Everything that isn't a card control. Few rows, each a big target (56–64 dp): it's meant
 * to be usable at a stop with gloves on. Columns: display (theme, unit, battery), layout and
 * navigation, trip. In debug builds the trip column also holds the debug tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    layout: DashboardLayoutState,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val unit by viewModel.speedUnit.collectAsStateWithLifecycle()
    val showBattery by viewModel.showBattery.collectAsStateWithLifecycle()
    val fakeGps by viewModel.useFakeGps.collectAsStateWithLifecycle()
    val resetArmed by viewModel.resetArmed.collectAsStateWithLifecycle()
    val voice by viewModel.voiceGuidance.collectAsStateWithLifecycle()
    val autoPause by viewModel.autoPauseMinutes.collectAsStateWithLifecycle()
    val autoEnd by viewModel.autoEndMinutes.collectAsStateWithLifecycle()
    val hiddenStats by viewModel.hiddenStats.collectAsStateWithLifecycle()
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    val simulate by viewModel.simulateNavigation.collectAsStateWithLifecycle()
    val autoLayout by viewModel.autoLayout.collectAsStateWithLifecycle()
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    if (optionsOpen) {
        Dialog(onDismissRequest = { optionsOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = AppTheme.colors.surface,
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 1000.dp).sheetTag("trip_options"),
            ) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Done beside the title: the dialog fits a landscape phone without scrolling.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_trip_options), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = { optionsOpen = false },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("settings_trip_options_done"),
                        ) { Text(stringResource(R.string.settings_done)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 9a: "forgot to close the app". 0 = off.
                    val off = stringResource(R.string.settings_off)
                    val minutes = { m: Int -> if (m == 0) off else "$m min" }
                    ChoiceRow(
                        label = R.string.settings_auto_pause,
                        options = AUTO_PAUSE_CHOICES,
                        selected = autoPause,
                        text = { minutes(it) },
                        tag = { "settings_auto_pause_$it" },
                        stacked = true,
                    ) { haptics.tick(); viewModel.setAutoPauseMinutes(it) }
                    ChoiceRow(
                        label = R.string.settings_auto_end,
                        options = AUTO_END_CHOICES,
                        selected = autoEnd,
                        text = { minutes(it) },
                        tag = { "settings_auto_end_$it" },
                        stacked = true,
                    ) { haptics.tick(); viewModel.setAutoEndMinutes(it) }

                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.settings_telemetry), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.settings_telemetry_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TelemetryStat.entries.forEach { stat ->
                            val shown = stat !in hiddenStats
                            FilterChip(
                                selected = shown,
                                onClick = { haptics.toggle(!shown); viewModel.setStatShown(stat, !shown) },
                                label = { Text(stat.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppTheme.colors.navy,
                                    selectedLabelColor = AppTheme.colors.onNavy,
                                ),
                                modifier = Modifier.heightIn(min = 56.dp).testTag("settings_stat_${stat.key}"),
                            )
                        }
                    }
                        }
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Landscape has little height: open fully rather than half-way, and no drag handle
        // (swiping the sheet down and Done both still close it).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 1200.dp, // room for the three landscape columns
        dragHandle = null,
        modifier = Modifier.sheetTag("settings_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag("settings_done")) {
                    Text(stringResource(R.string.settings_done))
                }
            }

            // Landscape is short: three columns (two on a narrower screen), so every control is
            // on screen without scrolling. In a narrow column the choices sit under their label.
            BoxWithConstraints {
                val columns = when {
                    maxWidth >= 840.dp -> 3
                    maxWidth >= 600.dp -> 2
                    else -> 1
                }
                val stacked = columns == 3
                val display = @Composable {
                    ChoiceRow(
                        label = R.string.settings_theme,
                        options = ThemeMode.entries,
                        selected = theme,
                        text = { it.label },
                        tag = { "settings_theme_${it.name}" },
                        stacked = stacked,
                    ) { haptics.tick(); viewModel.setThemeMode(it) }

                    ChoiceRow(
                        label = R.string.settings_speed_unit,
                        options = SpeedUnit.entries,
                        selected = unit,
                        text = { it.speedLabel },
                        tag = { "settings_unit_${it.name}" },
                        stacked = stacked,
                    ) { haptics.tick(); viewModel.setSpeedUnit(it) }

                    SwitchRow(
                        label = R.string.settings_battery,
                        hint = R.string.settings_battery_hint,
                        checked = showBattery,
                        tag = "settings_battery",
                    ) { haptics.toggle(it); viewModel.setShowBattery(it) }
                }
                val layoutAndNav = @Composable {
                    ChoiceRow(
                        label = R.string.settings_map_width,
                        options = SPLIT_PRESETS,
                        selected = SPLIT_PRESETS.minBy { abs(it - layout.split) },
                        text = { "${(it * 100).toInt()}%" },
                        tag = { "settings_split_${(it * 100).toInt()}" },
                        stacked = stacked,
                    ) { haptics.tick(); layout.setSplit(it) }

                    SwitchRow(
                        label = R.string.settings_auto_layout,
                        hint = R.string.settings_auto_layout_hint,
                        checked = autoLayout,
                        tag = "settings_auto_layout",
                    ) { haptics.toggle(it); viewModel.setAutoLayout(it) }

                    SwitchRow(
                        label = R.string.settings_voice,
                        hint = R.string.settings_voice_hint,
                        checked = voice,
                        tag = "settings_voice",
                    ) { haptics.toggle(it); viewModel.setVoiceGuidance(it) }
                }
                val trip = @Composable {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_trip), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = { haptics.tap(); onOpenHistory() },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("settings_history"),
                        ) {
                            Icon(AppIcons.History, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_history))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_reset_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        FilledTonalButton(
                            onClick = { if (viewModel.onResetTripTapped()) haptics.confirm() else haptics.tap() },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("settings_reset_trip"),
                            colors = if (resetArmed) {
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                        ) {
                            Text(stringResource(if (resetArmed) R.string.settings_reset_confirm else R.string.settings_reset_trip))
                        }
                    }

                    // 9a/9c: auto-pause, auto-end and the trip figures live in one dialog, so the
                    // sheet still fits a landscape phone without scrolling.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_trip_options), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.settings_trip_options_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledTonalButton(
                            onClick = { haptics.tap(); optionsOpen = true },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("settings_trip_options"),
                        ) { Text(stringResource(R.string.settings_telemetry_choose)) }
                    }

                    if (BuildConfig.DEBUG) {
                        HorizontalDivider()
                        val context = LocalContext.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_debug), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            // 10a: Photon vs TomTom on local queries. The activity exists in debug builds only.
                            FilledTonalButton(
                                onClick = {
                                    haptics.tap()
                                    context.startActivity(Intent().setClassName(context, "com.nativeauto.debug.SearchTestActivity"))
                                },
                                modifier = Modifier.heightIn(min = 56.dp).testTag("debug_search_test"),
                            ) { Text(stringResource(R.string.settings_search_test)) }
                        }
                        SwitchRow(label = R.string.settings_fake_gps, hint = null, checked = fakeGps, tag = "debug_fake_gps") {
                            haptics.toggle(it); viewModel.setUseFakeGps(it)
                        }
                        SwitchRow(label = R.string.settings_simulate_nav, hint = R.string.settings_simulate_nav_hint, checked = simulate, tag = "debug_simulate_nav") {
                            haptics.toggle(it); viewModel.setSimulateNavigation(it)
                        }
                    }
                }
                val gap = Arrangement.spacedBy(12.dp)
                when (columns) {
                    3 -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = gap) { display() }
                        Column(Modifier.weight(1f), verticalArrangement = gap) { layoutAndNav() }
                        Column(Modifier.weight(1f), verticalArrangement = gap) { trip() }
                    }
                    2 -> Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = gap) { display(); layoutAndNav() }
                        Column(Modifier.weight(1f), verticalArrangement = gap) { trip() }
                    }
                    else -> Column(verticalArrangement = gap) {
                        display()
                        layoutAndNav()
                        HorizontalDivider()
                        trip()
                    }
                }
            }
        }
    }
}

private val SPLIT_PRESETS = listOf(0.4f, 0.5f, 0.6f)
private val AUTO_PAUSE_CHOICES = listOf(0, 3, 5, 10)
private val AUTO_END_CHOICES = listOf(0, 15, 30, 60)

@Composable
private fun <T> ChoiceRow(
    @StringRes label: Int,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    tag: (T) -> String,
    stacked: Boolean = false,
    onSelect: (T) -> Unit,
) {
    val segments = @Composable { modifier: Modifier ->
        SingleChoiceSegmentedButtonRow(modifier) {
            options.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { if (option != selected) onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    colors = appSegmentedColors(),
                    modifier = Modifier.heightIn(min = 56.dp).testTag(tag(option)),
                ) {
                    Text(text(option), maxLines = 1)
                }
            }
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
            segments(Modifier.fillMaxWidth())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            segments(Modifier)
        }
    }
}

/** The whole row toggles, not just the switch: a much bigger target. */
@Composable
private fun SwitchRow(
    @StringRes label: Int,
    @StringRes hint: Int?,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, colors = appSwitchColors())
    }
}
