package com.nativeauto.ui.garage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.nativeauto.garage.DATE_NAMES
import com.nativeauto.garage.DueLevel
import com.nativeauto.garage.BikeType
import com.nativeauto.garage.serviceNames
import com.nativeauto.garage.ServiceDraft
import com.nativeauto.garage.ServiceDue
import com.nativeauto.garage.ServiceItem
import com.nativeauto.garage.ServiceKind
import com.nativeauto.garage.ServiceRecord
import com.nativeauto.garage.dueText
import com.nativeauto.garage.formatDate
import com.nativeauto.garage.formatOdometer
import com.nativeauto.garage.formatRupiah
import com.nativeauto.garage.intervalText
import com.nativeauto.garage.parseDate
import com.nativeauto.garage.parseNumber
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * 11c: the bike's service items (the rider's own intervals: km and/or months, or a due date),
 * each with its state, a Done button, and the service history below.
 */
@Composable
internal fun ServicesSection(
    services: List<ServiceDue>,
    history: List<ServiceRecord>,
    onAdd: () -> Unit,
    onEdit: (ServiceItem) -> Unit,
    onDone: (ServiceItem) -> Unit,
) {
    val colors = AppTheme.colors
    val zone = ZoneId.systemDefault()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Service", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onAdd, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_service_add")) { Text("Add item") }
    }
    if (services.isEmpty()) {
        Text(
            "Add what you service (oil, tires, brakes…) with your own intervals, and dates like vehicle tax.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
        )
    }
    services.forEach { due ->
        HorizontalDivider(color = colors.outline)
        Row(
            Modifier.fillMaxWidth().clickable { onEdit(due.item) }.padding(vertical = 6.dp).testTag("garage_service_${due.item.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(due.item.name, style = MaterialTheme.typography.titleMedium)
                Text(intervalText(due.item, zone), style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
            }
            Text(
                dueText(due),
                style = MaterialTheme.typography.bodyLarge,
                color = when (due.level) {
                    DueLevel.OVERDUE -> colors.red
                    DueLevel.SOON -> colors.amber
                    else -> colors.textDim
                },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("garage_service_due"),
            )
            FilledTonalButton(onClick = { onDone(due.item) }, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_service_done_${due.item.name}")) {
                Text("Done")
            }
        }
    }
    if (history.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Service history", style = MaterialTheme.typography.titleMedium)
        history.forEach { r ->
            HorizontalDivider(color = colors.outline)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("garage_service_record"), verticalAlignment = Alignment.CenterVertically) {
                Text(readingDate(r.timeMs), style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.width(110.dp))
                Text(r.itemName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(r.odometerKm?.let(::formatOdometer), r.costRp?.let(::formatRupiah), r.note).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Adds ([item] null) or edits a service item. Km and/or months (whichever comes first), or a
 * date item with its due date and how often it recurs. Names are suggestions; intervals are the
 * rider's own (no presets: bikes get modified).
 */
@Composable
internal fun ServiceEditor(
    item: ServiceItem?,
    type: BikeType,
    estimateKm: Double?,
    onSave: (ServiceDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    val zone = ZoneId.systemDefault()
    var kind by rememberSaveable { mutableStateOf(item?.serviceKind ?: ServiceKind.DISTANCE) }
    var name by rememberSaveable { mutableStateOf(item?.name ?: "") }
    var km by rememberSaveable { mutableStateOf(item?.intervalKm?.roundToLong()?.toString() ?: "") }
    var months by rememberSaveable { mutableStateOf(item?.intervalMonths?.toString() ?: "") }
    var due by rememberSaveable { mutableStateOf(item?.dueDateMs?.let { formatDate(it, zone) } ?: "") }
    var lastKm by rememberSaveable { mutableStateOf((item?.lastDoneKm ?: estimateKm)?.roundToLong()?.toString() ?: "") }
    var deleteArmed by rememberSaveable { mutableStateOf(false) }

    val kmValue = if (km.isBlank()) null else parseNumber(km, thousands = true)?.takeIf { it in 1.0..200_000.0 }
    val monthsValue = if (months.isBlank()) null else months.trim().toIntOrNull()?.takeIf { it in 1..120 }
    val dueValue = parseDate(due, zone)
    val lastKmValue = if (lastKm.isBlank()) null else parseNumber(lastKm, thousands = true)
    val valid = name.isNotBlank() && when (kind) {
        // An item may have no interval yet (§6: names without intervals are fine).
        ServiceKind.DISTANCE -> (km.isBlank() || kmValue != null) && (months.isBlank() || monthsValue != null) &&
            (lastKm.isBlank() || lastKmValue != null)
        ServiceKind.DATE -> dueValue != null && (months.isBlank() || monthsValue != null)
    }

    GarageDialog(
        tag = "garage_service_editor",
        title = if (item == null) "Add service item" else "Edit ${item.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    onSave(
                        ServiceDraft(
                            name = name.trim(),
                            kind = kind,
                            intervalKm = kmValue.takeIf { kind == ServiceKind.DISTANCE },
                            intervalMonths = monthsValue ?: if (kind == ServiceKind.DATE) 12 else null,
                            dueDateMs = dueValue.takeIf { kind == ServiceKind.DATE },
                            lastDoneKm = lastKmValue.takeIf { kind == ServiceKind.DISTANCE },
                        ),
                    )
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_service_save"),
            ) { Text("Save") }
        },
    ) {
        if (item == null) {
            Segments(ServiceKind.entries, kind, { if (it == ServiceKind.DISTANCE) "By km / months" else "By date" }, { "garage_service_kind_${it.name}" }) {
                haptics.tick(); kind = it
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kind == ServiceKind.DISTANCE) {
                serviceNames(type).forEach { n -> AssistChip(onClick = { haptics.tick(); name = n }, label = { Text(n) }, modifier = Modifier.heightIn(min = 48.dp)) }
            } else {
                DATE_NAMES.forEach { (n, m) ->
                    AssistChip(onClick = { haptics.tick(); name = n; months = m.toString() }, label = { Text(n) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag("garage_service_name"),
            )
            if (kind == ServiceKind.DISTANCE) {
                NumberField(km, { km = it }, "Every (km)", km.isNotBlank() && kmValue == null, "garage_service_km", Modifier.weight(0.6f), whole = true)
                NumberField(months, { months = it }, "Every (months)", months.isNotBlank() && monthsValue == null, "garage_service_months", Modifier.weight(0.6f), whole = true)
            } else {
                OutlinedTextField(
                    value = due,
                    onValueChange = { v -> due = v.filter { it.isDigit() || it == '/' || it == '-' || it == '.' }.take(10) },
                    label = { Text("Due (d/m/yyyy)") },
                    singleLine = true,
                    isError = due.isNotBlank() && dueValue == null,
                    modifier = Modifier.weight(0.7f).testTag("garage_service_due_input"),
                )
                NumberField(months, { months = it }, "Every (months)", months.isNotBlank() && monthsValue == null, "garage_service_months", Modifier.weight(0.5f), whole = true)
            }
        }
        if (kind == ServiceKind.DISTANCE) {
            NumberField(
                lastKm, { lastKm = it }, "Last done at (km)", lastKm.isNotBlank() && lastKmValue == null, "garage_service_last_km",
                Modifier.fillMaxWidth(), whole = true,
            )
            Text(
                "Due at whichever comes first. Leave both empty to keep it as a name only.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textDim,
            )
        }
        if (onDelete != null) {
            FilledTonalButton(
                onClick = { if (deleteArmed) onDelete() else { haptics.tap(); deleteArmed = true } },
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_service_delete"),
                colors = if (deleteArmed) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) { Text(if (deleteArmed) "Tap again to delete" else "Delete item") }
        }
    }
}

/** "Mark done": the odometer (prefilled), and an optional cost and note. */
@Composable
internal fun ServiceDoneDialog(
    item: ServiceItem,
    estimateKm: Double?,
    onSave: (odometerKm: Double?, costRp: Double?, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var odo by rememberSaveable { mutableStateOf(estimateKm?.roundToLong()?.toString() ?: "") }
    var cost by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val odoValue = if (odo.isBlank()) null else parseNumber(odo, thousands = true)
    val costValue = if (cost.isBlank()) null else parseNumber(cost, thousands = true)
    val valid = (odo.isBlank() || odoValue != null) && (cost.isBlank() || costValue != null)
    GarageDialog(
        tag = "garage_service_done_dialog",
        title = "${item.name} done",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(odoValue, costValue, note) },
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_service_done_save"),
            ) { Text("Save") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(odo, { odo = it }, "Odometer (km)", odo.isNotBlank() && odoValue == null, "garage_service_done_odo", Modifier.weight(1f), whole = true)
            NumberField(cost, { cost = it }, "Cost (Rp, optional)", cost.isNotBlank() && costValue == null, "garage_service_done_cost", Modifier.weight(1f), whole = true)
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(60) },
            label = { Text("Note (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("garage_service_done_note"),
        )
    }
}
