package com.nativeauto.ui.garage

import com.nativeauto.ui.settings.SwitchRow
import com.nativeauto.ui.settings.fadingBottom
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.nativeauto.garage.GAUGE_BARS_RANGE
import kotlin.math.roundToInt
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
import com.nativeauto.ui.components.AppDialogSurface
import com.nativeauto.ui.components.DialogTwoColumns
import com.nativeauto.ui.components.LocalPortrait
import com.nativeauto.ui.components.PinnedActionDialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.garage.BikeDraft
import com.nativeauto.garage.BikeType
import com.nativeauto.garage.CustomFuel
import com.nativeauto.garage.Fuel
import com.nativeauto.garage.RangeCaution
import com.nativeauto.garage.fuelChoices
import com.nativeauto.garage.fuelOf
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import com.nativeauto.ui.theme.appSwitchColors
import com.nativeauto.garage.DEFAULT_LOW_FUEL_KM
import com.nativeauto.garage.LOW_FUEL_CHOICES_KM
import com.nativeauto.garage.LOW_FUEL_RANGE_KM
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
internal fun GarageDialog(
    tag: String,
    title: String,
    onDismiss: () -> Unit,
    action: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // 14b: in portrait the form is a bottom sheet with its action pinned underneath; landscape keeps the old card.
    if (LocalPortrait.current) {
        PinnedActionDialog(tag, title, onDismiss, action, content)
        return
    }
    // 4.7: the title row stays put and the form scrolls under it, so Save can't scroll away on a long form.
    AppDialogSurface(tag, onDismiss, maxWidth = 900.dp, scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            action()
        }
        val scroll = rememberScrollState()
        Column(
            Modifier.weight(1f, fill = false).fadingBottom(scroll).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/**
 * Adds ([bike] null, starting from [newModel]) or edits a bike. Picking a model refills the
 * tank, reserve and gauge bars (Other keeps them); everything stays editable. The odometer is asked only when adding.
 * 4.7: the figures few riders change are under Fine-tuning; [learnedKmPerL] says what the app has learned meanwhile.
 */
@Composable
internal fun BikeEditor(
    bike: BikeRecord?,
    newModel: BikeModel,
    deleteArmed: Boolean,
    onSave: (BikeDraft, Double?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    learnedKmPerL: String? = null,
    fuels: List<CustomFuel> = emptyList(),
) {
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf(bike?.name ?: "") }
    var model by rememberSaveable { mutableStateOf(bike?.bikeModel ?: newModel) }
    var fuelKey by rememberSaveable { mutableStateOf(bike?.fuelType ?: FuelType.PERTAMAX.key) }
    val fuel = fuelOf(fuelKey, fuels)
    var type by rememberSaveable { mutableStateOf(bike?.bikeType ?: BikeType.SCOOTER_CVT) }
    var caution by rememberSaveable { mutableStateOf(bike?.caution ?: RangeCaution.NORMAL) }
    var tank by rememberSaveable { mutableStateOf(trimNumber(bike?.tankLitres ?: model.tankLitres)) }
    var reserve by rememberSaveable { mutableStateOf(trimNumber(bike?.reserveLitres ?: model.reserveLitres)) }
    var kmPerL by rememberSaveable { mutableStateOf(trimNumber(bike?.startKmPerL ?: DEFAULT_KM_PER_L)) }
    var odometer by rememberSaveable { mutableStateOf("") }
    // 4.7: the gauge, and the odometer correction in tenths of a percent (null = the learned one).
    var bars by rememberSaveable { mutableIntStateOf(bike?.gaugeBars ?: model.gaugeBars) }
    var tuning by rememberSaveable { mutableStateOf(false) }
    val learnedTenths = ((bike?.odoFactor ?: 1.0) - 1.0).times(1000).roundToInt()
    var factorTenths by rememberSaveable { mutableStateOf(bike?.odoFactorManual?.let { ((it - 1.0) * 1000).roundToInt() }) }
    // 4.6.1: the low-fuel warning; null = a custom number in the field.
    val savedLow = bike?.lowFuelKm ?: DEFAULT_LOW_FUEL_KM
    var lowChoice by rememberSaveable { mutableStateOf(savedLow.takeIf { it in LOW_FUEL_CHOICES_KM }) }
    var lowCustom by rememberSaveable { mutableStateOf("$savedLow") }
    val lowKm = lowChoice ?: parseNumber(lowCustom)?.toInt()?.takeIf { it in LOW_FUEL_RANGE_KM }

    val tankL = parseNumber(tank)?.takeIf { it in 1.0..50.0 }
    val reserveL = parseNumber(reserve)?.takeIf { tankL != null && it < tankL }
    val kmL = parseNumber(kmPerL)?.takeIf { it in 5.0..150.0 }
    val odoKm = if (odometer.isBlank()) null else parseNumber(odometer, thousands = true)
    val valid = name.isNotBlank() && tankL != null && reserveL != null && kmL != null && lowKm != null && (odometer.isBlank() || odoKm != null)

    GarageDialog(
        tag = "garage_editor",
        title = if (bike == null) "Add bike" else "Edit ${bike.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    onSave(BikeDraft(name.trim(), model, tankL!!, reserveL!!, fuel, kmL!!, lowKm!!, bars, factorTenths?.let { 1.0 + it / 1000.0 }, type, caution), odoKm)
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_save"),
            ) { Text("Save") }
        },
    ) {
        DialogTwoColumns {
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    label = { Text("Name (e.g. Gen 1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("garage_name"),
                )
                ModelPicker(model) {
                    haptics.tick()
                    model = it
                    // "Other" is any bike: what is typed stays.
                    if (it != BikeModel.OTHER) {
                        tank = trimNumber(it.tankLitres)
                        reserve = trimNumber(it.reserveLitres)
                        bars = it.gaugeBars
                        // The presets are all scooters.
                        type = BikeType.SCOOTER_CVT
                    }
                }
                // 4.8: which service parts it has. A preset says so itself.
                if (model == BikeModel.OTHER) {
                    Segments(BikeType.entries, type, { it.label }, { "garage_type_${it.name}" }) { haptics.tick(); type = it }
                }
                val choices = fuelChoices(fuels, fuel)
                // The row that opens tiles says it itself.
                if (choices.size <= FuelType.entries.size) Text("Fuel", style = MaterialTheme.typography.titleMedium)
                FuelPicker(choices, fuel, "garage_fuel") { haptics.tick(); fuelKey = it.key }
                Stepper(
                    title = "Fuel gauge bars",
                    hint = "Count them on the dash",
                    value = "$bars",
                    tag = "garage_bars",
                    canLower = bars > GAUGE_BARS_RANGE.first,
                    canRaise = bars < GAUGE_BARS_RANGE.last,
                    onLower = { haptics.tick(); bars-- },
                    onRaise = { haptics.tick(); bars++ },
                )
            }
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(tank, { tank = it }, "Tank (L)", tankL == null, "garage_tank", Modifier.fillMaxWidth())
                Text("Warn when low, km left", style = MaterialTheme.typography.titleMedium)
                Segments<Int?>(LOW_FUEL_CHOICES_KM + null, lowChoice, { it?.toString() ?: "Other" }, { "garage_low_${it ?: "OTHER"}" }) {
                    haptics.tick()
                    lowChoice = it
                }
                if (lowChoice == null) {
                    NumberField(
                        lowCustom, { lowCustom = it }, "Warn at (${LOW_FUEL_RANGE_KM.first} to ${LOW_FUEL_RANGE_KM.last} km)", lowKm == null,
                        "garage_low_input", Modifier.fillMaxWidth(), whole = true,
                    )
                }
                // 4.8: lower than the estimate, for a rider who wants a margin.
                Text("Range shown", style = MaterialTheme.typography.titleMedium)
                Segments(RangeCaution.entries, caution, { it.label }, { "garage_caution_${it.name}" }) { haptics.tick(); caution = it }
                if (bike == null) {
                    NumberField(
                        odometer, { odometer = it }, "Odometer now (km, optional)", odometer.isNotBlank() && odoKm == null,
                        "garage_odo_input", Modifier.fillMaxWidth(), whole = true,
                    )
                }
                // Open by itself while something in it is wrong: Save must not be off for a reason that can't be seen.
                val tuningOpen = tuning || reserveL == null || kmL == null
                FormRow("Fine-tuning", if (tuningOpen) "▴" else "▾", "garage_tuning") { haptics.tap(); tuning = !tuning }
                if (tuningOpen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(kmPerL, { kmPerL = it }, "Starting km/L", kmL == null, "garage_kmpl", Modifier.weight(1f))
                        NumberField(reserve, { reserve = it }, "Reserve (L)", reserveL == null, "garage_reserve", Modifier.weight(1f))
                    }
                    Text(
                        learnedKmPerL ?: "The range starts from this km/L and learns from your fill-ups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTheme.colors.textDim,
                        modifier = Modifier.testTag("garage_learned"),
                    )
                    val shown = factorTenths ?: learnedTenths
                    val step = { by: Int ->
                        haptics.tick()
                        factorTenths = (shown + by).coerceIn(FACTOR_TENTHS)
                    }
                    Stepper(
                        title = "Odometer vs GPS",
                        hint = when {
                            factorTenths == null -> "Learned"
                            else -> "Set by you"
                        },
                        value = percentOf(shown),
                        tag = "garage_odo_factor",
                        canLower = shown > FACTOR_TENTHS.first,
                        canRaise = shown < FACTOR_TENTHS.last,
                        onLower = { step(-FACTOR_STEP) },
                        onRaise = { step(FACTOR_STEP) },
                    )
                    if (factorTenths != null) {
                        FilledTonalButton(
                            onClick = { haptics.tap(); factorTenths = null },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("garage_odo_factor_learned"),
                        ) { Text("Use learned (${percentOf(learnedTenths)})") }
                    }
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

/** The odometer correction: ±10 % ([com.nativeauto.garage.FACTOR_RANGE]) in steps of 0.5 %, kept in tenths of a percent. */
private val FACTOR_TENTHS = -100..100
private const val FACTOR_STEP = 5

private fun percentOf(tenths: Int): String = String.format(java.util.Locale.US, "%+.1f%%", tenths / 10.0).replace('-', '−').let { if (tenths == 0) "0%" else it }

/** A row of the form that opens something: a title, what it stands at now, an arrow. */
@Composable
internal fun FormRow(title: String, value: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.surface,
        contentColor = AppTheme.colors.text,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim)
        }
    }
}

/** 4.7: ten presets don't fit a segmented row: the model is a row that opens them as big tiles, all in view. */
@Composable
private fun ModelPicker(model: BikeModel, onPick: (BikeModel) -> Unit) =
    TilePicker("Model", BikeModel.entries, model, { it.label }, "garage_model", "garage_models", { "garage_model_${it.name}" }, onPick)

/** 4.8: the four pump fuels as a segmented row; with fuels of the rider's own, a row that opens them all as tiles. */
@Composable
internal fun FuelPicker(fuels: List<Fuel>, fuel: Fuel, tag: String, onPick: (Fuel) -> Unit) {
    if (fuels.size <= FuelType.entries.size) {
        Segments(fuels, fuel, { it.label }, { "${tag}_${fuelTag(it)}" }, label = { FuelLabel(it) }, onSelect = onPick)
    } else {
        TilePicker("Fuel", fuels, fuel, { it.label }, "${tag}_row", "${tag}_tiles", { "${tag}_${fuelTag(it)}" }, onPick)
    }
}

/** A form row that opens its choices as big tiles. */
@Composable
private fun <T> TilePicker(
    title: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    rowTag: String,
    dialogTag: String,
    tag: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    FormRow(title, "${text(selected)}  ▾", rowTag) { open = true }
    if (!open) return
    AppDialogSurface(dialogTag, onDismiss = { open = false }, maxWidth = 900.dp, spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
        }
        val columns = if (LocalPortrait.current) 2 else 3
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { m ->
                    val on = m == selected
                    Surface(
                        onClick = { open = false; if (!on) onPick(m) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (on) AppTheme.colors.navy else AppTheme.colors.surface,
                        contentColor = if (on) AppTheme.colors.onNavy else AppTheme.colors.text,
                        border = BorderStroke(1.dp, AppTheme.colors.outline),
                        modifier = Modifier.weight(1f).testTag(tag(m)),
                    ) {
                        Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                            Text(text(m), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A title and hint on the left, − value + on the right, both buttons glove-sized. */
@Composable
private fun Stepper(
    title: String,
    hint: String,
    value: String,
    tag: String,
    canLower: Boolean,
    canRaise: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim)
        }
        StepButton("−", "Lower: $title", "${tag}_minus", canLower, onLower)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(84.dp).testTag("${tag}_value"),
        )
        StepButton("+", "Raise: $title", "${tag}_plus", canRaise, onRaise)
    }
}

@Composable
internal fun StepButton(sign: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(56.dp).testTag(tag).semantics { contentDescription = description },
    ) { Text(sign, style = MaterialTheme.typography.headlineSmall) }
}

/** A switch as a whole row of the form. */
@Composable
internal fun FormSwitch(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, colors = appSwitchColors())
    }
}

@Composable
internal fun <T> Segments(
    options: List<T>,
    selected: T,
    text: (T) -> String,
    tag: (T) -> String,
    label: (@Composable (T) -> Unit)? = null,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { if (option != selected) onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                colors = appSegmentedColors(),
                modifier = Modifier.heightIn(min = 56.dp).testTag(tag(option)),
            ) { if (label != null) label(option) else Text(text(option), maxLines = 1) }
        }
    }
}

@Composable
internal fun NumberField(
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

/**
 * One reading: correcting from the Garage, or the away prompt ("Gen 2's odometer now?"). [askOtherPhone] (4.7.2): offers
 * "Recorded on another phone"; above the field, as the keyboard's ✓ saves at once.
 */
@Composable
internal fun OdometerDialog(
    title: String,
    text: String,
    prefillKm: Double?,
    skipLabel: String,
    askOtherPhone: Boolean,
    onSave: (km: Double, otherPhone: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(prefillKm?.roundToLong()?.toString() ?: "") }
    var otherPhone by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val km = parseNumber(value, thousands = true)
    GarageDialog(
        tag = "garage_odometer_dialog",
        title = title,
        onDismiss = onSkip,
        action = {
            TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_odo_skip")) { Text(skipLabel) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(km!!, otherPhone) },
                enabled = km != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_odo_save"),
            ) { Text("Save") }
        },
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim)
        if (askOtherPhone) {
            SwitchRow(R.string.garage_other_phone, R.string.garage_other_phone_hint, otherPhone, "garage_odo_other_phone") {
                haptics.toggle(it)
                otherPhone = it
            }
        }
        // The keyboard's ✓ saves too: one tap fewer at the pump.
        NumberField(
            value, { value = it }, "Odometer (km)", value.isNotBlank() && km == null, "garage_odo_input", Modifier.fillMaxWidth(),
            whole = true, onDone = { km?.let { onSave(it, otherPhone) } },
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
            askOtherPhone = p.estimateKm != null,
            onSave = { km, otherPhone -> haptics.confirm(); viewModel.answerOdometerPrompt(km, otherPhone) },
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

/** 12c: the fuel's button label: brand small on top, grade below ("Pertamax" / "Turbo"); Pertalite on one line. */
@Composable
internal fun FuelLabel(fuel: Fuel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        fuel.brand?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
        Text(fuel.grade, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
    }
}
