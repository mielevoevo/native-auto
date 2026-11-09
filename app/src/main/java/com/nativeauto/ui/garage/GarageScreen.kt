package com.nativeauto.ui.garage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.garage.BikeModel
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.OdometerReading
import com.nativeauto.garage.OdometerState
import com.nativeauto.garage.ReadingKind
import com.nativeauto.garage.formatOdometer
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 11a: the rider's bikes, which one is being ridden, and each one's odometer. Drawn over the
 * dashboard like the history (rule #3: the live map stays composed underneath). 11b/11c add
 * the refuel log, services and the Garage card.
 */
@Composable
fun GarageScreen(onClose: () -> Unit, viewModel: GarageViewModel = hiltViewModel()) {
    val bikes by viewModel.bikes.collectAsStateWithLifecycle()
    val armed by viewModel.deleteArmed.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // The editor: null closed; "" a new bike (with [newModel]); otherwise the bike's id.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var newModel by rememberSaveable { mutableStateOf(BikeModel.NMAX_GEN1) }
    var correcting by rememberSaveable { mutableStateOf<String?>(null) }

    val list = bikes
    val selected = list?.firstOrNull { it.id == selectedId } ?: list?.firstOrNull { it.active } ?: list?.firstOrNull()

    BackHandler { onClose() }

    Surface(Modifier.fillMaxSize().testTag("garage_screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { haptics.tap(); onClose() }, modifier = Modifier.size(56.dp).testTag("garage_back")) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back")
                }
                Text("Garage", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!list.isNullOrEmpty()) {
                    FilledTonalButton(
                        onClick = { haptics.tap(); newModel = BikeModel.NMAX_GEN1; editing = "" },
                        modifier = Modifier.heightIn(min = 56.dp).testTag("garage_add"),
                    ) { Text("Add bike") }
                }
                Spacer(Modifier.width(16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Unit
                    list.isEmpty() -> EmptyGarage { model -> haptics.tap(); newModel = model; editing = "" }
                    selected != null -> Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            list.forEach { bike ->
                                BikeTile(bike, isSelected = bike.id == selected.id, viewModel) { haptics.tap(); selectedId = bike.id }
                            }
                        }
                        BikeDetail(
                            bike = selected,
                            viewModel = viewModel,
                            onRide = { haptics.confirm(); viewModel.ride(selected.id) },
                            onEdit = { haptics.tap(); editing = selected.id },
                            onCorrect = { haptics.tap(); correcting = selected.id },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    editing?.let { id ->
        val bike = list?.firstOrNull { it.id == id }
        BikeEditor(
            bike = bike,
            newModel = newModel,
            deleteArmed = bike != null && armed == bike.id,
            onSave = { draft, odometer ->
                haptics.confirm()
                if (bike == null) viewModel.add(draft, odometer) { selectedId = it } else viewModel.update(bike.id, draft)
                editing = null
            },
            onDelete = bike?.let {
                {
                    if (viewModel.onDeleteTapped(it.id)) {
                        haptics.confirm()
                        editing = null
                        selectedId = null
                    } else {
                        haptics.tap()
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }
    correcting?.let { id ->
        val bike = list?.firstOrNull { it.id == id }
        if (bike == null) {
            correcting = null
        } else {
            val odo by remember(id) { viewModel.odometer(id) }.collectAsStateWithLifecycle(null)
            OdometerDialog(
                title = if (odo?.lastReading == null) "${bike.name}'s odometer" else "Correct ${bike.name}'s odometer",
                text = if (odo?.lastReading == null) {
                    "Enter the reading on the bike. From here on, every trip on ${bike.name} adds to it."
                } else {
                    "Enter the reading on the bike. GPS drifts a little from it; each correction teaches the app by how much."
                },
                prefillKm = odo?.estimateKm,
                skipLabel = "Cancel",
                onSave = { km -> haptics.confirm(); viewModel.enterReading(id, km); correcting = null },
                onSkip = { correcting = null },
            )
        }
    }
}

@Composable
private fun EmptyGarage(onAdd: (BikeModel) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Bike, contentDescription = null, modifier = Modifier.size(48.dp), tint = AppTheme.colors.textDim)
        Spacer(Modifier.height(12.dp))
        Text("No bikes yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add the bike you ride: trips are counted on its odometer. Your trips so far go to the first bike.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.textDim,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BikeModel.entries.forEach { model ->
                FilledTonalButton(
                    onClick = { onAdd(model) },
                    modifier = Modifier.heightIn(min = 64.dp).testTag("garage_add_${model.name}"),
                ) { Text(model.label) }
            }
        }
    }
}

@Composable
private fun BikeTile(bike: BikeRecord, isSelected: Boolean, viewModel: GarageViewModel, onClick: () -> Unit) {
    val odo by remember(bike.id) { viewModel.odometer(bike.id) }.collectAsStateWithLifecycle(null)
    val colors = AppTheme.colors
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) colors.navy else colors.surface,
        contentColor = if (isSelected) colors.onNavy else colors.text,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).testTag("garage_bike_${bike.name}"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bike.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (bike.active) Text("Riding", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("garage_riding_${bike.name}"))
            }
            Text(bike.bikeModel.label, style = MaterialTheme.typography.bodyMedium)
            Text(odo?.estimateKm?.let(::formatOdometer) ?: "Odometer not set", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BikeDetail(
    bike: BikeRecord,
    viewModel: GarageViewModel,
    onRide: () -> Unit,
    onEdit: () -> Unit,
    onCorrect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val odo by remember(bike.id) { viewModel.odometer(bike.id) }.collectAsStateWithLifecycle(null)
    val readings by remember(bike.id) { viewModel.readings(bike.id) }.collectAsStateWithLifecycle(emptyList())
    val dim = AppTheme.colors.textDim
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bike.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${bike.bikeModel.label} · ${trimNumber(bike.tankLitres)} L tank · ${bike.fuel.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = dim,
                )
            }
            FilledTonalButton(onClick = onEdit, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_edit")) { Text("Edit") }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Odometer", style = MaterialTheme.typography.labelLarge, color = dim)
                Text(
                    odo?.estimateKm?.let(::formatOdometer) ?: "Not set",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("garage_odometer"),
                )
            }
            FilledTonalButton(onClick = onCorrect, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_correct")) {
                Text(if (odo?.lastReading == null) "Set odometer" else "Correct")
            }
        }
        odo?.let { FactorLine(it) }
        Spacer(Modifier.height(16.dp))
        if (bike.active) {
            Text("Riding this bike: new trips are counted on it.", style = MaterialTheme.typography.bodyMedium, color = dim)
        } else {
            FilledTonalButton(onClick = onRide, modifier = Modifier.heightIn(min = 64.dp).fillMaxWidth().testTag("garage_ride")) {
                Icon(AppIcons.Bike, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Ride ${bike.name}")
            }
        }
        if (readings.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Odometer log", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            readings.forEach { ReadingRow(it) }
        }
    }
}

@Composable
private fun FactorLine(odo: OdometerState) {
    if (odo.lastReading == null) return
    val pct = (odo.factor - 1.0) * 100
    val text = if (kotlin.math.abs(pct) < 0.05) {
        "Correct the odometer now and then (at a fill-up): the app learns how far GPS drifts from it."
    } else {
        "GPS reads ${String.format(Locale.US, "%.1f", kotlin.math.abs(pct))}% ${if (pct > 0) "short" else "long"}; trips are scaled to match."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim, modifier = Modifier.testTag("garage_factor"))
}

@Composable
private fun ReadingRow(r: OdometerReading) {
    HorizontalDivider(color = AppTheme.colors.outline)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("garage_reading"), verticalAlignment = Alignment.CenterVertically) {
        Text(readingDate(r.timeMs), style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim, modifier = Modifier.width(110.dp))
        Text(formatOdometer(r.km), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            when (r.readingKind) {
                ReadingKind.SET -> "First reading"
                ReadingKind.CORRECTION -> "Corrected"
                ReadingKind.AWAY -> "Away +${formatOdometer(r.awayKm)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (r.readingKind == ReadingKind.AWAY) AppTheme.colors.amber else AppTheme.colors.textDim,
        )
    }
}

private val READING_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

private fun readingDate(ms: Long): String = READING_DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 6.6 → "6.6", 7.0 → "7". */
internal fun trimNumber(v: Double): String =
    if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
