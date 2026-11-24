package com.nativeauto.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nativeauto.R
import com.nativeauto.ui.components.AppDialogSurface
import com.nativeauto.ui.components.DialogTwoColumns
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.components.sheetTag
import com.nativeauto.ui.theme.AccentPreset
import com.nativeauto.ui.theme.AccentPresets
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.accentWarnings
import com.nativeauto.ui.theme.parseHexColor
import com.nativeauto.ui.theme.appSwitchColors
import com.nativeauto.ui.theme.toHex

/**
 * 13c: the accent colour (presets or any hex). The accent is the one highlight on a card, so a bad choice is easy to
 * see at once; the hex field says when a colour is hard to read on black or white (the themes then lighten or darken
 * it). 4.6.2: the route line has its own dialog, on the Map and layout page.
 */
@Composable
internal fun AccentDialog(accent: Int, onAccent: (Int) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    AppDialogSurface("colours_dialog", onDismiss, maxWidth = 640.dp, spacing = 12.dp) {
        DialogTitle(R.string.settings_accent, "colours_done", onDismiss)
        Text("The one highlight on a card: progress, switches, the lit outline.", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim)
        ColourPicker(
            current = Color(accent),
            tagPrefix = "accent",
            onPick = { haptics.tick(); onAccent(it.toArgb()) },
        )
    }
}

/** 4.6.2: the map's route line: the accent, or a colour of its own. */
@Composable
internal fun RouteColourDialog(accent: Int, route: Int?, onRoute: (Int?) -> Unit, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    AppDialogSurface("route_dialog", onDismiss, maxWidth = 640.dp, spacing = 12.dp) {
        DialogTitle(R.string.settings_route_colour, "route_done", onDismiss)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = route == null, role = Role.Switch) { follow ->
                    haptics.toggle(follow)
                    onRoute(if (follow) null else accent)
                }
                .testTag("route_follow"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Follow the accent", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = route == null, onCheckedChange = null, colors = appSwitchColors())
        }
        if (route != null) {
            ColourPicker(
                current = Color(route),
                tagPrefix = "route",
                onPick = { haptics.tick(); onRoute(it.toArgb()) },
            )
        }
    }
}

/** Preset swatches and a hex field. Applies a valid colour at once; keeps what is typed while it isn't one yet. */
@Composable
private fun ColourPicker(current: Color, tagPrefix: String, onPick: (Color) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentPresets.forEach { preset -> Swatch(preset, selected = preset.dark.toArgb() == current.toArgb(), tagPrefix) { onPick(preset.dark) } }
    }
    var text by rememberSaveable(tagPrefix) { mutableStateOf(current.toHex()) }
    // A preset tapped (or the colour changed elsewhere) rewrites the field, unless it already means the same colour.
    var lastCurrent by remember { mutableStateOf(current) }
    if (current != lastCurrent) {
        lastCurrent = current
        if (parseHexColor(text)?.toArgb() != current.toArgb()) text = current.toHex()
    }
    val parsed = parseHexColor(text)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(7)
            // Only a full six digits applies: "#3DD" on the way to "#3DDC84" is the shorthand for another colour.
            if (v.trim().removePrefix("#").length == 6) parseHexColor(v)?.let(onPick)
        },
        label = { Text("Custom colour (hex)") },
        singleLine = true,
        isError = parsed == null,
        supportingText = {
            val warnings = parsed?.let(::accentWarnings).orEmpty()
            when {
                parsed == null -> Text("Six hex digits, like #3DDC84", modifier = Modifier.testTag("${tagPrefix}_hex_error"))
                warnings.isNotEmpty() -> Text(warnings.joinToString(" "), color = AppTheme.colors.amber, modifier = Modifier.testTag("${tagPrefix}_hex_warning"))
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_hex"),
    )
}

@Composable
private fun Swatch(preset: AccentPreset, selected: Boolean, tagPrefix: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(preset.dark)
            .border(BorderStroke(if (selected) 4.dp else 1.dp, if (selected) colors.text else colors.outline), CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = preset.label
            }
            .testTag("${tagPrefix}_preset_${preset.key}"),
    )
}
