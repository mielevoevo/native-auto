package com.nativeauto.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.garage.BikeModel
import com.nativeauto.settings.OrientationMode
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.trip.AUTO_START_HOLD_CHOICES_S
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.garage.BikeEditor
import com.nativeauto.ui.garage.GarageViewModel
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.appSwitchColors

/** How many pages there are; the dots and the last page's button follow it. */
internal const val SETUP_PAGES = 7

/**
 * 4.2: the first-run setup (a new install only). Seven pages, landscape first (two columns: the title and its line on the
 * left, the choices and Back / Next on the right), the same content stacked in one column in portrait. No page scrolls:
 * every row and button is one fixed size, so the busiest page (Riding) still shows Back and Next on a 411 dp tall phone.
 * Every toggle asks Android straight away; nothing is asked before the rider taps it.
 */
@Composable
fun SetupScreen(viewModel: SetupViewModel = hiltViewModel()) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val context = LocalContext.current

    val location by viewModel.locationGranted.collectAsStateWithLifecycle()
    val access by viewModel.notificationAccess.collectAsStateWithLifecycle()
    var postGranted by remember { mutableStateOf(postNotificationsGranted(context)) }
    val notifications = postGranted && access

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        postGranted = postNotificationsGranted(context)
        onPauseOrDispose { }
    }

    BackHandler(enabled = page > 0) { haptics.tap(); page -= 1 }

    fun next() {
        haptics.tap()
        // Leaving the permissions page with either one off asks once.
        if (page == PAGE_PERMISSIONS && (!location || !notifications)) confirm = true else page += 1
    }

    if (confirm) {
        ConfirmWithout(location = location, notifications = notifications, onBack = { confirm = false }, onContinue = { confirm = false; page += 1 })
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(AppTheme.colors.bg)
            .safeDrawingPadding()
            .testTag("setup_screen"),
    ) {
        val landscape = maxWidth > maxHeight
        val nav: @Composable () -> Unit = {
            NavButtons(
                showBack = page > 0,
                nextLabel = if (page == SETUP_PAGES - 1) "Start riding" else "Next",
                nextTag = if (page == SETUP_PAGES - 1) "setup_finish" else "setup_next",
                onBack = { haptics.tap(); page -= 1 },
                onNext = { if (page == SETUP_PAGES - 1) { haptics.confirm(); viewModel.finish() } else next() },
            )
        }
        val content = pageContent(page, viewModel, location = location, notifications = notifications, onPostGranted = { postGranted = it })
        if (page == 0) {
            WelcomePage(content.title, content.why, landscape, nav)
        } else if (landscape) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp).testTag("setup_page_$page"),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(Modifier.weight(0.95f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(content.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textDim)
                    Text(content.title, fontSize = TITLE_SP, lineHeight = TITLE_SP * 1.15f, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text)
                    Text(content.why, fontSize = BODY_SP, lineHeight = BODY_SP * 1.4f, color = AppTheme.colors.textDim)
                    Spacer(Modifier.weight(1f))
                    Dots(page)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    content.body(this)
                    Spacer(Modifier.weight(1f))
                    nav()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp).testTag("setup_page_$page"),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                Text(content.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.textDim)
                Text(content.title, fontSize = TITLE_SP, lineHeight = TITLE_SP * 1.15f, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text)
                Text(content.why, fontSize = BODY_SP, lineHeight = BODY_SP * 1.4f, color = AppTheme.colors.textDim)
                Spacer(Modifier.height(4.dp))
                content.body(this)
                Spacer(Modifier.weight(1f))
                Dots(page)
                nav()
            }
        }
    }
}

private const val PAGE_APPEARANCE = 1
private const val PAGE_PERMISSIONS = 2
private const val PAGE_CALLS = 3
private const val PAGE_BIKE = 4
private const val PAGE_RIDING = 5
private const val PAGE_READY = 6

/** Body text 16 sp; the page title is 1.75 times that. */
private val BODY_SP = 16.sp
private val TITLE_SP = 28.sp
private val ROW_HEIGHT = 48.dp
private val BUTTON_HEIGHT = 52.dp
private val ROW_GAP = 8.dp

private class PageContent(val label: String, val title: String, val why: String, val body: @Composable ColumnScope.() -> Unit)

@Composable
private fun pageContent(
    page: Int,
    vm: SetupViewModel,
    location: Boolean,
    notifications: Boolean,
    onPostGranted: (Boolean) -> Unit,
): PageContent = when (page) {
    0 -> PageContent(
        "", "Native Auto",
        "Turns your phone on the handlebars into a motorcycle dashboard: speed, map, music, navigation and your garage, all readable at a glance.",
    ) { }
    PAGE_APPEARANCE -> PageContent(
        "Appearance", "Make it yours",
        "How the phone sits on the bike and how the app looks. You can change all of this later in Settings.",
    ) { AppearanceRows(vm) }
    PAGE_PERMISSIONS -> PageContent(
        "Permissions", "Location and notifications",
        "Location gives you speed, trip distance and the map. Notifications keep a running trip alive and let the app read what is playing for the Media card.",
    ) { PermissionRows(vm, location, notifications, onPostGranted) }
    PAGE_CALLS -> PageContent(
        "Calls", "Calls",
        "Show incoming calls on the dashboard with Answer and Decline. You can turn call controls off any time in Settings.",
    ) { CallRows(vm) }
    PAGE_BIKE -> PageContent(
        "Your bike", "Add your bike",
        "Optional. A bike turns on the odometer, fuel range and service reminders.",
    ) { BikeBody() }
    PAGE_RIDING -> PageContent(
        "Riding", "On the road",
        "What the app may do on its own while it is open.",
    ) { RidingRows(vm) }
    else -> PageContent(
        "Ready", "You are set",
        "What is on and off. Everything is also in Settings, and Settings, Privacy and data lists the permissions.",
    ) { ReadyRows(vm, location, notifications) }
}

// ---- page 1 ----------------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage(title: String, why: String, landscape: Boolean, nav: @Composable () -> Unit) {
    val logo: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier.clip(RoundedCornerShape(22)).background(Color.Black).border(1.dp, AppTheme.colors.outline, RoundedCornerShape(22)),
            contentAlignment = Alignment.Center,
        ) {
            // The launcher's mark; its safe-zone margin is scaled away.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize().scale(1.45f))
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp).testTag("setup_page_0"), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Box(Modifier.weight(0.95f).fillMaxHeight(), contentAlignment = Alignment.Center) { logo(Modifier.size(190.dp)) }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Text(title, fontSize = TITLE_SP, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text)
                Spacer(Modifier.height(8.dp))
                Text(why, fontSize = BODY_SP, lineHeight = BODY_SP * 1.4f, color = AppTheme.colors.textDim)
                Spacer(Modifier.height(24.dp))
                nav()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp).testTag("setup_page_0"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            Spacer(Modifier.weight(1f))
            logo(Modifier.size(180.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, fontSize = TITLE_SP, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text)
            Text(why, fontSize = BODY_SP, lineHeight = BODY_SP * 1.4f, color = AppTheme.colors.textDim)
            Spacer(Modifier.weight(1f))
            Dots(0)
            nav()
        }
    }
}

// ---- page 2: appearance ----------------------------------------------------------------------------------------

@Composable
private fun AppearanceRows(vm: SetupViewModel) {
    val haptics = rememberHaptics()
    val orientation by vm.orientation.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val unit by vm.unit.collectAsStateWithLifecycle()
    ChoiceRow("Orientation", orientation.label, OrientationMode.entries, "setup_orientation", { it.label }, { it.name }) { haptics.tick(); vm.setOrientation(it) }
    ChoiceRow("Theme", theme.label, ThemeMode.entries, "setup_theme", { it.label }, { it.name }) { haptics.tick(); vm.setTheme(it) }
    ChoiceRow("Units", unitLabel(unit), SpeedUnit.entries, "setup_units", ::unitLabel, { it.name }) { haptics.tick(); vm.setUnit(it) }
}

private fun unitLabel(unit: SpeedUnit) = if (unit == SpeedUnit.KMH) "Metric (km/h)" else "Imperial (mph)"

// ---- page 3: location and notifications -------------------------------------------------------------------------

@Composable
private fun PermissionRows(vm: SetupViewModel, location: Boolean, notifications: Boolean, onPostGranted: (Boolean) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val access by vm.notificationAccess.collectAsStateWithLifecycle()

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val postLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPostGranted(granted)
        vm.refresh()
        // Reading what is playing is a system screen, not a prompt: open it right after the prompt.
        if (granted && !access) com.nativeauto.ui.media.openNotificationAccessSettings(context)
    }

    SwitchRow("Location", location, "setup_location") {
        haptics.toggle(!location)
        if (location) context.openAppSettings()
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    SwitchRow("Notifications", notifications, "setup_notifications") {
        haptics.toggle(!notifications)
        when {
            notifications -> com.nativeauto.ui.media.openNotificationAccessSettings(context)
            !postNotificationsGranted(context) -> postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> com.nativeauto.ui.media.openNotificationAccessSettings(context)
        }
    }
}

@Composable
private fun ConfirmWithout(location: Boolean, notifications: Boolean, onBack: () -> Unit, onContinue: () -> Unit) {
    val message = buildString {
        if (!location) append("Without location there is no speed and the map cannot follow you. ")
        if (!notifications) append("Without notifications a running trip may stop when the app is in the background, and the Media card will show nothing.")
    }.trim()
    AlertDialog(
        onDismissRequest = onBack,
        containerColor = AppTheme.colors.surface,
        title = { Text(if (!location && !notifications) "Continue without them?" else if (!location) "Continue without location?" else "Continue without notifications?") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = 52.dp).testTag("setup_confirm_continue")) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 52.dp).testTag("setup_confirm_back")) { Text("Go back") } },
        modifier = Modifier.testTag("setup_confirm"),
    )
}

// ---- page 4: calls -----------------------------------------------------------------------------------------------

@Composable
private fun CallRows(vm: SetupViewModel) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val on by vm.callControls.collectAsStateWithLifecycle()
    val read by vm.phoneRead.collectAsStateWithLifecycle()
    val control by vm.phoneControl.collectAsStateWithLifecycle()
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val controlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    SwitchRow("Call controls", on, "setup_call_controls") { haptics.toggle(!on); vm.setCallControls(!on) }
    SwitchRow("Phone state", read, "setup_phone_state") {
        haptics.toggle(!read)
        if (read) context.openAppSettings() else readLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }
    SwitchRow("Answer and end calls", control, "setup_answer_calls") {
        haptics.toggle(!control)
        if (control) context.openAppSettings() else controlLauncher.launch(Manifest.permission.ANSWER_PHONE_CALLS)
    }
}

// ---- page 5: bike ------------------------------------------------------------------------------------------------

@Composable
private fun BikeBody(garage: GarageViewModel = hiltViewModel()) {
    val haptics = rememberHaptics()
    val bikes by garage.bikes.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    val list = bikes.orEmpty()
    if (adding) {
        BikeEditor(
            bike = null,
            newModel = BikeModel.NMAX_GEN1,
            deleteArmed = false,
            onSave = { draft, odometer -> haptics.confirm(); garage.add(draft, odometer) { }; adding = false },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    list.forEach { bike ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = AppTheme.colors.buttonQuiet,
            contentColor = AppTheme.colors.onButtonQuiet,
            border = BorderStroke(1.dp, AppTheme.colors.outline),
            modifier = Modifier.fillMaxWidth().testTag("setup_bike_${bike.name}"),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("${bike.name} · ${bike.bikeModel.label}", fontSize = BODY_SP, fontWeight = FontWeight.SemiBold)
                Text("Tank ${"%.1f".format(bike.tankLitres)} L", fontSize = 14.sp, color = AppTheme.colors.textDim)
            }
        }
    }
    FilledTonalButton(
        onClick = { haptics.tap(); adding = true },
        modifier = Modifier.heightIn(min = BUTTON_HEIGHT).testTag("setup_add_bike"),
    ) { Text(if (list.isEmpty()) "Add bike" else "Add another bike") }
    Text(
        if (list.isEmpty()) "Opens the same model menu as the Garage." else "You can add more bikes any time in Settings, Trip and Garage, Garage.",
        fontSize = 14.sp,
        color = AppTheme.colors.textDim,
    )
}

// ---- page 6: riding ----------------------------------------------------------------------------------------------

@Composable
private fun RidingRows(vm: SetupViewModel) {
    val haptics = rememberHaptics()
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val hold by vm.autoStartHoldS.collectAsStateWithLifecycle()
    val layout by vm.autoLayout.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val on = autoStart != AutoStartMode.OFF
    SwitchRow("Auto-start trip", on, "setup_auto_start") { haptics.toggle(!on); vm.setAutoStart(!on) }
    ChoiceRow("Start after", "$hold s", AUTO_START_HOLD_CHOICES_S, "setup_start_after", { "$it s" }, { it.toString() }) { haptics.tick(); vm.setAutoStartHold(it) }
    SwitchRow("Auto layout on navigation", layout, "setup_auto_layout") { haptics.toggle(!layout); vm.setAutoLayout(!layout) }
    SwitchRow("Voice guidance", voice, "setup_voice") { haptics.toggle(!voice); vm.setVoice(!voice) }
}

// ---- page 7: ready -----------------------------------------------------------------------------------------------

@Composable
private fun ReadyRows(vm: SetupViewModel, location: Boolean, notifications: Boolean, garage: GarageViewModel = hiltViewModel()) {
    val callsOn by vm.callControls.collectAsStateWithLifecycle()
    val read by vm.phoneRead.collectAsStateWithLifecycle()
    val bikes by garage.bikes.collectAsStateWithLifecycle()
    val bike = bikes.orEmpty().firstOrNull()
    SummaryRow("Location", location, "setup_ready_location")
    SummaryRow("Notifications", notifications, "setup_ready_notifications")
    SummaryRow("Calls", callsOn && read, "setup_ready_calls")
    SummaryRow(if (bike != null) "Bike: ${bike.name}" else "Bike", bike != null, "setup_ready_bike", onText = if (bike != null) "Added" else "None")
}

// ---- pieces ------------------------------------------------------------------------------------------------------

@Composable
private fun NavButtons(showBack: Boolean, nextLabel: String, nextTag: String, onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showBack) {
            FilledTonalButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = BUTTON_HEIGHT).testTag("setup_back")) { Text("Back", fontSize = BODY_SP) }
        }
        Button(onClick = onNext, modifier = Modifier.weight(1.6f).heightIn(min = BUTTON_HEIGHT).testTag(nextTag)) { Text(nextLabel, fontSize = BODY_SP, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Dots(page: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(SETUP_PAGES) { i ->
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(if (i == page) AppTheme.colors.accent else AppTheme.colors.outline),
            )
        }
    }
}

@Composable
private fun RowShell(tag: String, onClick: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val base = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).testTag(tag)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.buttonQuiet,
        contentColor = AppTheme.colors.onButtonQuiet,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
        modifier = if (onClick != null) base.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick) else base,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).heightIn(min = ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onClick: () -> Unit) {
    RowShell(tag, onClick) {
        Text(label, fontSize = BODY_SP, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = appSwitchColors())
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    value: String,
    options: List<T>,
    tag: String,
    text: (T) -> String,
    key: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RowShell(tag, onClick = { open = true }) {
            Text(label, fontSize = BODY_SP, modifier = Modifier.weight(1f))
            Text("$value  ▾", fontSize = BODY_SP, color = AppTheme.colors.textDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text(option), fontSize = BODY_SP) },
                    onClick = { open = false; onPick(option) },
                    modifier = Modifier.heightIn(min = ROW_HEIGHT).testTag("${tag}_${key(option)}"),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, ok: Boolean, tag: String, onText: String = "On") {
    RowShell(tag, onClick = null) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(if (ok) AppTheme.colors.accent else AppTheme.colors.outline),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ok) "✓" else "–", fontSize = 14.sp, color = if (ok) Color.Black else AppTheme.colors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = BODY_SP, modifier = Modifier.weight(1f))
        Text(if (ok) onText else if (onText == "On") "Off" else onText, fontSize = BODY_SP, color = AppTheme.colors.textDim)
    }
}

private fun postNotificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
