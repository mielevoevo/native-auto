package com.nativeauto.ui.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.garage.BikeDraft
import com.nativeauto.garage.BikeModel
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.DEFAULT_KM_PER_L
import com.nativeauto.garage.FuelType
import com.nativeauto.garage.GaragePrompt
import com.nativeauto.garage.parseNumber
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.components.sheetTag
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.appSegmentedColors
import kotlin.math.roundToLong

/** The shared frame: a wide card that fits a landscape phone, the action beside the title. */
@Composable
private fun GarageDialog(
    tag: String,
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = AppTheme.colors.surface,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 900.dp).sheetTag(tag),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    action()
                }
                content()
            }
        }
    }
}

/**
 * Adds ([bike] null, starting from [newModel]) or edits a bike. Picking a model refills the
 * tank and reserve; everything stays editable. The odometer is asked only when adding.
 */
@Composable
internal fun BikeEditor(
    bike: BikeRecord?,
    newModel: BikeModel,
    deleteArmed: Boolean,
    onSave: (BikeDraft, Double?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf(bike?.name ?: "") }
    var model by rememberSaveable { mutableStateOf(bike?.bikeModel ?: newModel) }
    var fuel by rememberSaveable { mutableStateOf(bike?.fuel ?: FuelType.PERTAMAX) }
    var tank by rememberSaveable { mutableStateOf(trimNumber(bike?.tankLitres ?: model.tankLitres)) }
    var reserve by rememberSaveable { mutableStateOf(trimNumber(bike?.reserveLitres ?: model.reserveLitres)) }
    var kmPerL by rememberSaveable { mutableStateOf(trimNumber(bike?.startKmPerL ?: DEFAULT_KM_PER_L)) }
    var odometer by rememberSaveable { mutableStateOf("") }

    val tankL = parseNumber(tank)?.takeIf { it in 1.0..50.0 }
    val reserveL = parseNumber(reserve)?.takeIf { tankL != null && it < tankL }
    val kmL = parseNumber(kmPerL)?.takeIf { it in 5.0..150.0 }
    val odoKm = if (odometer.isBlank()) null else parseNumber(odometer, thousands = true)
    val valid = name.isNotBlank() && tankL != null && reserveL != null && kmL != null && (odometer.isBlank() || odoKm != null)

    GarageDialog(
        tag = "garage_editor",
        title = if (bike == null) "Add bike" else "Edit ${bike.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    onSave(BikeDraft(name.trim(), model, tankL!!, reserveL!!, fuel, kmL!!), odoKm)
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_save"),
            ) { Text("Save") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    label = { Text("Name (e.g. Gen 1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("garage_name"),
                )
                Text("Model", style = MaterialTheme.typography.titleMedium)
                Segments(BikeModel.entries, model, { it.shortLabel }, { "garage_model_${it.name}" }) {
                    haptics.tick()
                    model = it
                    tank = trimNumber(it.tankLitres)
                    reserve = trimNumber(it.reserveLitres)
                }
                Text("Fuel", style = MaterialTheme.typography.titleMedium)
                Segments(FuelType.entries, fuel, { it.label }, { "garage_fuel_${it.name}" }) { haptics.tick(); fuel = it }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(tank, { tank = it }, "Tank (L)", tankL == null, "garage_tank", Modifier.weight(1f))
                    NumberField(reserve, { reserve = it }, "Reserve (L)", reserveL == null, "garage_reserve", Modifier.weight(1f))
                }
                NumberField(kmPerL, { kmPerL = it }, "Starting km/L", kmL == null, "garage_kmpl", Modifier.fillMaxWidth())
                if (bike == null) {
                    NumberField(
                        odometer, { odometer = it }, "Odometer now (km, optional)", odometer.isNotBlank() && odoKm == null,
                        "garage_odo_input", Modifier.fillMaxWidth(), whole = true,
                    )
                }
                if (onDelete != null) {
                    FilledTonalButton(
                        onClick = onDelete,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("garage_delete"),
                        colors = if (deleteArmed) {
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) { Text(if (deleteArmed) "Tap again to delete" else "Delete bike") }
                }
            }
        }
    }
}

@Composable
private fun <T> Segments(options: List<T>, selected: T, text: (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { if (option != selected) onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                colors = appSegmentedColors(),
                modifier = Modifier.heightIn(min = 56.dp).testTag(tag(option)),
            ) { Text(text(option), maxLines = 1) }
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    tag: String,
    modifier: Modifier,
    whole: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() ?: defaultKeyboardAction(ImeAction.Done) }),
        modifier = modifier.testTag(tag),
    )
}

/** One reading: correcting from the Garage, or the away prompt ("Gen 2's odometer now?"). */
@Composable
internal fun OdometerDialog(
    title: String,
    text: String,
    prefillKm: Double?,
    skipLabel: String,
    onSave: (Double) -> Unit,
    onSkip: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(prefillKm?.roundToLong()?.toString() ?: "") }
    val km = parseNumber(value, thousands = true)
    GarageDialog(
        tag = "garage_odometer_dialog",
        title = title,
        onDismiss = onSkip,
        action = {
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_odo_skip")) { Text(skipLabel) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(km!!) },
                enabled = km != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_odo_save"),
            ) { Text("Save") }
        },
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim)
        // The keyboard's ✓ saves too: one tap fewer at the pump.
        NumberField(
            value, { value = it }, "Odometer (km)", value.isNotBlank() && km == null, "garage_odo_input", Modifier.fillMaxWidth(),
            whole = true, onDone = { km?.let(onSave) },
        )
    }
}

/**
 * The Garage's questions, over the dashboard: the away check after switching to a bike that
 * sat for days, and (debug builds) "which bike?" when a trip starts far from the last ride.
 */
@Composable
fun GaragePrompts(viewModel: GarageViewModel = hiltViewModel()) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    when (val p = prompt) {
        null -> Unit
        is GaragePrompt.OdometerNow -> OdometerDialog(
            title = "${p.bike.name}'s odometer now?",
            text = "${p.bike.name} hasn't been ridden with the app for a while. If someone else rode it, the difference " +
                "is logged as away km: it counts toward servicing, but not as your trips.",
            prefillKm = p.estimateKm,
            skipLabel = "Skip",
            onSave = { haptics.confirm(); viewModel.answerOdometerPrompt(it) },
            onSkip = { haptics.tap(); viewModel.answerOdometerPrompt(null) },
        )
        is GaragePrompt.WhichBike -> {
            val current = p.bikes.firstOrNull { it.id == p.activeId }
            GarageDialog(
                tag = "which_bike",
                title = "Which bike?",
                onDismiss = { viewModel.dismissPrompt() },
                action = {},
            ) {
                Text(
                    "This trip starts ${p.distanceKm.roundToLong()} km from where ${current?.name ?: "your bike"} was last ridden.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.textDim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    p.bikes.forEach { bike ->
                        FilledTonalButton(
                            onClick = { haptics.confirm(); viewModel.answerWhichBike(bike.id) },
                            modifier = Modifier.weight(1f).heightIn(min = 72.dp).testTag("which_bike_${bike.name}"),
                        ) { Text(bike.name, style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
        }
    }
}
