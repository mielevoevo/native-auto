package com.nativeauto.ui.settings

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nativeauto.ui.components.AppDialogSurface
import com.nativeauto.ui.components.DialogTwoColumns
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.BuildConfig
import com.nativeauto.R
import com.nativeauto.settings.DRAG_HOLD_CHOICES_MS
import com.nativeauto.settings.RefreshMode
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.TelemetryStat
import com.nativeauto.settings.ThemeMode
import com.nativeauto.trip.AUTO_START_HOLD_CHOICES_S
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.components.sheetTag
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.garage.GarageViewModel
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.appSegmentedColors
import com.nativeauto.ui.theme.appSwitchColors
import kotlin.math.abs

/**
 * The settings, in categories (13c): Display, Layout, Navigation, Trip and Garage, Audio, Backup and (debug
 * builds) Debug. A category picks what the page below the header shows; each page is two short columns on a
 * landscape phone, so a category fits without scrolling. Every control is a big target (56 dp+): it is meant
 * to be usable at a stop with gloves on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    layout: DashboardLayoutState,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGarage: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var categoryName by rememberSaveable { mutableStateOf(SettingsCategory.DISPLAY.name) }
    val category = SettingsCategory.entries.first { it.name == categoryName }
    var screenOpen by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var coloursOpen by rememberSaveable { mutableStateOf(false) }
    var changelogOpen by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()

    if (changelogOpen) com.nativeauto.ui.changelog.ChangelogHistoryDialog(onDismiss = { changelogOpen = false })
    if (screenOpen) ScreenOptionsDialog(viewModel, onDismiss = { screenOpen = false })
    if (optionsOpen) TripOptionsDialog(viewModel, onDismiss = { optionsOpen = false })
    if (coloursOpen) {
        val accent by viewModel.accentColor.collectAsStateWithLifecycle()
        val route by viewModel.routeColor.collectAsStateWithLifecycle()
        ColoursDialog(
            accent = accent,
            route = route,
            onAccent = viewModel::setAccentColor,
            onRoute = viewModel::setRouteColor,
            onDismiss = { coloursOpen = false },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Landscape has little height: open fully rather than half-way, and no drag handle
        // (swiping the sheet down and Done both still close it).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 1200.dp,
        dragHandle = null,
        modifier = Modifier.sheetTag("settings_sheet"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tabs = @Composable { modifier: Modifier ->
                    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsCategory.entries.filter { it != SettingsCategory.DEBUG || BuildConfig.DEBUG }.forEach { c ->
                            FilterChip(
                                selected = c == category,
                                onClick = { haptics.tick(); categoryName = c.name },
                                label = { Text(c.label, maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppTheme.colors.navy,
                                    selectedLabelColor = AppTheme.colors.onNavy,
                                ),
                                modifier = Modifier.heightIn(min = 56.dp).testTag("settings_cat_${c.name}"),
                            )
                        }
                    }
                }
                if (wide) {
                    // Title, the categories and Done on one line: landscape has no height to spare.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(20.dp))
                        tabs(Modifier.weight(1f))
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag("settings_done")) {
                            Text(stringResource(R.string.settings_done))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag("settings_done")) {
                            Text(stringResource(R.string.settings_done))
                        }
                    }
                    tabs(Modifier.fillMaxWidth())
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("settings_page_${category.name}")) {
                    when (category) {
                        SettingsCategory.DISPLAY -> DisplayPage(viewModel, wide, onColours = { coloursOpen = true }, onScreen = { screenOpen = true }, onChangelog = { changelogOpen = true })
                        SettingsCategory.LAYOUT -> LayoutPage(viewModel, layout, wide)
                        SettingsCategory.NAVIGATION -> NavigationPage(viewModel, wide)
                        SettingsCategory.TRIP_GARAGE -> TripGaragePage(viewModel, wide, onOpenHistory, onOpenGarage, onOptions = { optionsOpen = true })
                        SettingsCategory.AUDIO -> AudioPage(viewModel, wide)
                        SettingsCategory.BACKUP -> PageColumns(wide, left = { BackupSection() })
                        SettingsCategory.DEBUG -> DebugPage(viewModel, wide)
                    }
                }
            }
        }
    }
}

/** The settings categories, in the order of the header. */
enum class SettingsCategory(val label: String) {
    DISPLAY("Display"),
    LAYOUT("Layout"),
    NAVIGATION("Navigation"),
    TRIP_GARAGE("Trip and Garage"),
    AUDIO("Audio"),
    BACKUP("Backup"),
    DEBUG("Debug"),
}

/** Two columns of rows on a wide screen, one stacked column on a narrow one. */
@Composable
private fun PageColumns(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit = {}) {
    val gap = Arrangement.spacedBy(12.dp)
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = gap, content = left)
            Column(Modifier.weight(1f), verticalArrangement = gap, content = right)
        }
    } else {
        Column(verticalArrangement = gap) {
            left()
            right()
        }
    }
}

@Composable
private fun DisplayPage(
    viewModel: SettingsViewModel,
    wide: Boolean,
    
    onColours: () -> Unit,
    onScreen: () -> Unit,
    onChangelog: () -> Unit,
) {
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val accent by viewModel.accentColor.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    PageColumns(wide,
        left = {
            ChoiceRow(
                label = R.string.settings_theme,
                options = ThemeMode.entries,
                selected = theme,
                text = { it.label },
                tag = { "settings_theme_${it.name}" },
                stacked = true,
            ) { haptics.tick(); viewModel.setThemeMode(it) }
            ActionRow(
                title = "Colours",
                hint = "Accent and route line",
                action = "Choose",
                tag = "settings_colours",
                leading = { Box(Modifier.size(28.dp).clip(CircleShape).background(Color(accent)).testTag("settings_accent_swatch")) },
            ) { haptics.tap(); onColours() }
        },
        right = {
            ActionRow(
                title = stringResource(R.string.settings_screen_options),
                hint = "Refresh rate, battery",
                action = stringResource(R.string.settings_telemetry_choose),
                tag = "settings_screen_options",
            ) { haptics.tap(); onScreen() }
            ActionRow(
                title = stringResource(R.string.settings_whats_new),
                hint = stringResource(R.string.settings_whats_new_hint),
                action = stringResource(R.string.settings_open),
                tag = "settings_whats_new",
            ) { haptics.tap(); onChangelog() }
        },
    )
}

@Composable
private fun LayoutPage(viewModel: SettingsViewModel, layout: DashboardLayoutState, wide: Boolean) {
    val dragHold by viewModel.dragHoldMs.collectAsStateWithLifecycle()
    val orientation by viewModel.orientationMode.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    PageColumns(wide,
        left = {
            ChoiceRow(
                label = R.string.settings_orientation,
                options = com.nativeauto.settings.OrientationMode.entries,
                selected = orientation,
                text = { it.label },
                tag = { "settings_orientation_${it.name}" },
                stacked = true,
            ) { haptics.tick(); viewModel.setOrientationMode(it) }
            Text(
                stringResource(R.string.settings_orientation_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textDim,
                modifier = Modifier.testTag("settings_orientation_hint"),
            )
            ChoiceRow(
                label = R.string.settings_map_width,
                options = SPLIT_PRESETS,
                selected = SPLIT_PRESETS.minBy { abs(it - layout.split) },
                text = { "${(it * 100).toInt()}%" },
                tag = { "settings_split_${(it * 100).toInt()}" },
                stacked = true,
            ) { haptics.tick(); layout.setSplit(it) }
        },
        right = {
            ChoiceRow(
                label = R.string.settings_drag_hold,
                options = DRAG_HOLD_CHOICES_MS,
                selected = dragHold,
                text = { "${it / 1000} s" },
                tag = { "settings_drag_hold_${it / 1000}" },
                stacked = true,
            ) { haptics.tick(); viewModel.setDragHoldMs(it) }
        },
    )
}

@Composable
private fun NavigationPage(viewModel: SettingsViewModel, wide: Boolean) {
    val unit by viewModel.speedUnit.collectAsStateWithLifecycle()
    val autoLayout by viewModel.autoLayout.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    PageColumns(wide,
        left = {
            ChoiceRow(
                label = R.string.settings_speed_unit,
                options = SpeedUnit.entries,
                selected = unit,
                text = { it.speedLabel },
                tag = { "settings_unit_${it.name}" },
                stacked = true,
            ) { haptics.tick(); viewModel.setSpeedUnit(it) }
        },
        right = {
            SwitchRow(
                label = R.string.settings_auto_layout,
                hint = R.string.settings_auto_layout_hint,
                checked = autoLayout,
                tag = "settings_auto_layout",
            ) { haptics.toggle(it); viewModel.setAutoLayout(it) }
        },
    )
}

@Composable
private fun AudioPage(viewModel: SettingsViewModel, wide: Boolean) {
    val voice by viewModel.voiceGuidance.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    PageColumns(wide,
        left = {
            SwitchRow(
                label = R.string.settings_voice,
                hint = R.string.settings_voice_hint,
                checked = voice,
                tag = "settings_voice",
            ) { haptics.toggle(it); viewModel.setVoiceGuidance(it) }
        },
        right = {
            CallControlsRow()
        },
    )
}

@Composable
private fun TripGaragePage(
    viewModel: SettingsViewModel,
    wide: Boolean,
    
    onOpenHistory: () -> Unit,
    onOpenGarage: () -> Unit,
    onOptions: () -> Unit,
    garage: GarageViewModel = hiltViewModel(),
) {
    val activeBike by garage.active.collectAsStateWithLifecycle()
    val resetArmed by viewModel.resetArmed.collectAsStateWithLifecycle()
    val autoStartMode by viewModel.autoStartMode.collectAsStateWithLifecycle()
    val autoStartHold by viewModel.autoStartHoldS.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    PageColumns(wide,
        left = {
            ActionRow(
                title = stringResource(R.string.settings_trip),
                hint = null,
                action = stringResource(R.string.settings_history),
                tag = "settings_history",
                icon = AppIcons.History,
            ) { haptics.tap(); onOpenHistory() }
            // 11a: the Garage (bikes, odometer); the hint names the bike being ridden.
            ActionRow(
                title = stringResource(R.string.settings_garage),
                hint = activeBike?.let { stringResource(R.string.settings_garage_riding, it.name) } ?: stringResource(R.string.settings_garage_hint),
                hintTag = "settings_garage_bike",
                action = stringResource(R.string.settings_garage_open),
                tag = "settings_garage",
                icon = AppIcons.Bike,
            ) { haptics.tap(); onOpenGarage() }
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
        },
        right = {
            // 9a/9c: auto-pause, auto-end, the period total and the trip figures live in one dialog.
            ActionRow(
                title = stringResource(R.string.settings_trip_options),
                hint = stringResource(R.string.settings_trip_options_hint),
                action = stringResource(R.string.settings_telemetry_choose),
                tag = "settings_trip_options",
            ) { haptics.tap(); onOptions() }
            // 13b: the app starts the logging by itself while it is open (never in the background).
            ChoiceRow(
                label = R.string.settings_auto_start,
                options = AutoStartMode.entries,
                selected = autoStartMode,
                text = { it.label },
                tag = { "settings_auto_start_${it.name}" },
                stacked = true,
            ) { haptics.tick(); viewModel.setAutoStartMode(it) }
            ChoiceRow(
                label = R.string.settings_auto_start_hold,
                options = AUTO_START_HOLD_CHOICES_S,
                selected = autoStartHold,
                text = { "$it s" },
                tag = { "settings_auto_start_hold_$it" },
                stacked = true,
            ) { haptics.tick(); viewModel.setAutoStartHoldS(it) }
            Text(
                stringResource(R.string.settings_auto_start_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun DebugPage(viewModel: SettingsViewModel, wide: Boolean) {
    val fakeGps by viewModel.useFakeGps.collectAsStateWithLifecycle()
    val simulate by viewModel.simulateNavigation.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    PageColumns(wide,
        left = {
            // 10a: Photon vs TomTom on local queries. The activity exists in debug builds only.
            ActionRow(
                title = stringResource(R.string.settings_debug),
                hint = null,
                action = stringResource(R.string.settings_search_test),
                tag = "debug_search_test",
            ) {
                haptics.tap()
                context.startActivity(Intent().setClassName(context, "com.nativeauto.debug.SearchTestActivity"))
            }
        },
        right = {
            SwitchRow(label = R.string.settings_fake_gps, hint = null, checked = fakeGps, tag = "debug_fake_gps") {
                haptics.toggle(it); viewModel.setUseFakeGps(it)
            }
            SwitchRow(label = R.string.settings_simulate_nav, hint = R.string.settings_simulate_nav_hint, checked = simulate, tag = "debug_simulate_nav") {
                haptics.toggle(it); viewModel.setSimulateNavigation(it)
            }
        },
    )
}

/** 12a: refresh rate and the battery indicator (the hold time moved to Layout in 13c). */
@Composable
private fun ScreenOptionsDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val refresh by viewModel.refreshMode.collectAsStateWithLifecycle()
    val showBattery by viewModel.showBattery.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    AppDialogSurface("screen_options", onDismiss, maxWidth = 1000.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_screen_options), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("settings_screen_options_done"),
                    ) { Text(stringResource(R.string.settings_done)) }
                }
                DialogTwoColumns {
                    Column(column, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ChoiceRow(
                            label = R.string.settings_refresh,
                            options = RefreshMode.entries,
                            selected = refresh,
                            text = { it.label },
                            tag = { "settings_refresh_${it.name}" },
                            stacked = true,
                        ) { haptics.tick(); viewModel.setRefreshMode(it) }
                        Text(
                            stringResource(R.string.settings_refresh_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(column, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SwitchRow(
                            label = R.string.settings_battery,
                            hint = R.string.settings_battery_hint,
                            checked = showBattery,
                            tag = "settings_battery",
                        ) { haptics.toggle(it); viewModel.setShowBattery(it) }
                    }
                }
    }
}

/** 9a/9c/12b: auto-pause, auto-end, the period total and the figures on the log face (auto-start has its own place now). */
@Composable
private fun TripOptionsDialog(viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    val autoPause by viewModel.autoPauseMinutes.collectAsStateWithLifecycle()
    val autoEnd by viewModel.autoEndMinutes.collectAsStateWithLifecycle()
    val totalPeriod by viewModel.totalPeriod.collectAsStateWithLifecycle()
    val hiddenStats by viewModel.hiddenStats.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    AppDialogSurface("trip_options", onDismiss, maxWidth = 1000.dp) {
                // Done beside the title: the dialog fits a landscape phone without scrolling.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_trip_options), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("settings_trip_options_done"),
                    ) { Text(stringResource(R.string.settings_done)) }
                }
                DialogTwoColumns {
                    Column(column, verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        // 12b (R2): the log face's distance total.
                        ChoiceRow(
                            label = R.string.settings_total_period,
                            options = com.nativeauto.settings.TotalPeriod.entries,
                            selected = totalPeriod,
                            text = { it.label },
                            tag = { "settings_period_${it.name}" },
                            stacked = true,
                        ) { haptics.tick(); viewModel.setTotalPeriod(it) }
                    }
                    Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

private val SPLIT_PRESETS = listOf(0.4f, 0.5f, 0.6f)
private val AUTO_PAUSE_CHOICES = listOf(0, 3, 5, 10)
private val AUTO_END_CHOICES = listOf(0, 15, 30, 60)

/** A title with a hint under it and one button: the row shape of every "open something" setting. */
@Composable
private fun ActionRow(
    title: String,
    hint: String?,
    action: String,
    tag: String,
    hintTag: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (hintTag != null) Modifier.testTag(hintTag) else Modifier,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 56.dp).testTag(tag)) {
            if (icon != null) {
                Icon(icon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(action)
        }
    }
}

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
