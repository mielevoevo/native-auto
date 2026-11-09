package com.nativeauto.ui.garage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nativeauto.garage.AmountMode
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.FuelPrice
import com.nativeauto.garage.FuelState
import com.nativeauto.garage.FuelType
import com.nativeauto.garage.RefuelDraft
import com.nativeauto.garage.RefuelRecord
import com.nativeauto.garage.UnknownFuel
import com.nativeauto.garage.costPerKm
import com.nativeauto.garage.formatKmPerL
import com.nativeauto.garage.formatLitres
import com.nativeauto.garage.formatOdometer
import com.nativeauto.garage.formatRupiah
import com.nativeauto.garage.parseNumber
import com.nativeauto.garage.priceOf
import com.nativeauto.garage.refuelAmounts
import com.nativeauto.garage.spend
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/** The last segment of the tank (Rider-Feedback §6): range and number turn red at or below it. */
internal const val LOW_FUEL_FRACTION = 0.2

/**
 * 11b: the bike's fuel in the Garage: range, the Refuelled button, km since the last fill-up,
 * learned km/L, the refill cost (hidden until asked) and the refuel log.
 */
@Composable
internal fun FuelSection(
    bike: BikeRecord,
    fuel: FuelState?,
    refuelArmed: Long?,
    onRefuel: () -> Unit,
    onDeleteRefuel: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    // Never shown by default (§6): refilling shouldn't turn into a sum to worry about.
    var showCost by rememberSaveable(bike.id) { mutableStateOf(false) }
    Text("Fuel", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Range", style = MaterialTheme.typography.labelLarge, color = colors.textDim)
            val level = fuel?.level
            val range = level?.rangeKm
            val low = (level?.fraction ?: 1.0) <= LOW_FUEL_FRACTION
            Text(
                range?.let { "${it.roundToLong()} km" } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (low && range != null) colors.red else colors.text,
                modifier = Modifier.testTag("garage_range"),
            )
        }
        FilledTonalButton(
            onClick = onRefuel,
            modifier = Modifier.heightIn(min = 64.dp).testTag("garage_refuel"),
        ) {
            Icon(AppIcons.Fuel, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Refuelled")
        }
    }
    fuel?.let { f ->
        Text(fuelLine(f), style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.testTag("garage_fuel_line"))
        f.level.sinceRefuelKm?.let {
            Text(
                "${formatOdometer(it)} since the last fill-up",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textDim,
                modifier = Modifier.testTag("garage_since_refuel"),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { haptics.toggle(!showCost); showCost = !showCost },
        modifier = Modifier.heightIn(min = 56.dp).testTag("garage_cost_toggle"),
    ) { Text(if (showCost) "Hide costs" else "Calculate refill") }
    if (showCost && fuel != null) CostPanel(fuel)
    if (fuel != null && fuel.refuels.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Refuel log", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val measures = fuel.consumption.measures.associateBy { it.endRefuelId }
        fuel.refuels.forEach { r ->
            RefuelRow(r, measures[r.id]?.let { m -> m.kmPerL to m.used }, armed = refuelArmed == r.id) { onDeleteRefuel(r.id) }
        }
    }
}

/** "4.1 L of 6.6 L · 36.4 km/L learned", or why the level is unknown. */
private fun fuelLine(f: FuelState): String {
    val kmpl = formatKmPerL(f.consumption.kmPerL) + if (f.consumption.learned) " learned" else " (starting value)"
    val level = f.level
    return when {
        level.litres != null -> "${formatLitres(level.litres)} of ${trimNumber(level.tankLitres)} L · $kmpl"
        level.unknownBecause == UnknownFuel.AWAY -> "Unknown after away km: a full fill-up restarts it · $kmpl"
        else -> "Log a full fill-up to start the tank estimate · $kmpl"
    }
}

@Composable
private fun CostPanel(fuel: FuelState) {
    val dim = AppTheme.colors.textDim
    Column(Modifier.padding(top = 8.dp).testTag("garage_cost"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val toFill = fuel.level.toFillLitres
        Text(
            if (toFill != null) {
                "Next refill ≈ ${formatLitres(toFill)} × ${formatRupiah(fuel.pricePerL)} = ${formatRupiah(toFill * fuel.pricePerL)}"
            } else {
                "Next refill: needs a full fill-up first"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("garage_refill_estimate"),
        )
        val s = spend(fuel.refuels, System.currentTimeMillis(), ZoneId.systemDefault())
        Text(
            "Last 7 days ${formatRupiah(s.last7Days)} · this month ${formatRupiah(s.thisMonth)} · last month ${formatRupiah(s.lastMonth)}",
            style = MaterialTheme.typography.bodyMedium,
            color = dim,
            modifier = Modifier.testTag("garage_spend"),
        )
        costPerKm(fuel.refuels)?.let {
            Text("${formatRupiah(it)} per km", style = MaterialTheme.typography.bodyMedium, color = dim)
        }
    }
}

@Composable
private fun RefuelRow(r: RefuelRecord, measure: Pair<Double, Boolean>?, armed: Boolean, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    HorizontalDivider(color = colors.outline)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("garage_refuel_row"), verticalAlignment = Alignment.CenterVertically) {
        Text(readingDate(r.timeMs), style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.width(110.dp))
        Text(formatOdometer(r.odometerKm), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            "${if (r.full) "Full" else "Partial"} ${formatLitres(r.litres)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(110.dp),
        )
        Text(
            measure?.let { (kmpl, used) -> formatKmPerL(kmpl) + if (used) "" else " (skipped)" } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
            modifier = Modifier.width(150.dp),
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(56.dp).testTag("garage_refuel_delete"),
            colors = if (armed) IconButtonDefaults.iconButtonColors(contentColor = colors.red) else IconButtonDefaults.iconButtonColors(),
        ) { Icon(AppIcons.Delete, contentDescription = if (armed) "Tap again to delete" else "Delete fill-up") }
    }
}

/**
 * The pump, a few taps: full (the default) or partial; what was paid in Rp or litres, the other
 * worked out from the price per litre (prefilled with the fuel's last price, correctable); and
 * the odometer, prefilled with the estimate (the real reading is the truth).
 */
@Composable
internal fun RefuelDialog(
    bike: BikeRecord,
    estimateKm: Double?,
    prices: List<FuelPrice>,
    onSave: (RefuelDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    var full by rememberSaveable { mutableStateOf(true) }
    var fuel by rememberSaveable { mutableStateOf(bike.fuel) }
    var mode by rememberSaveable { mutableStateOf(AmountMode.RUPIAH) }
    var amount by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf(wholeNumber(priceOf(bike.fuel, prices))) }
    var odometer by rememberSaveable { mutableStateOf(estimateKm?.roundToLong()?.toString() ?: "") }

    val amountValue = if (mode == AmountMode.RUPIAH) parseNumber(amount, thousands = true) else parseNumber(amount)
    val priceValue = parseNumber(price, thousands = true)?.takeIf { it in 1_000.0..100_000.0 }
    val amounts = refuelAmounts(mode, amountValue, priceValue)?.takeIf { (litres, _) -> litres <= bike.tankLitres * 2 }
    val odoKm = parseNumber(odometer, thousands = true)
    val valid = amounts != null && odoKm != null

    GarageDialog(
        tag = "garage_refuel_dialog",
        title = "Refuelled · ${bike.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    val (litres, rp) = amounts!!
                    onSave(RefuelDraft(full, litres, priceValue!!, rp, fuel, odoKm!!))
                },
                enabled = valid,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_refuel_save"),
            ) { Text("Save") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segments(listOf(true, false), full, { if (it) "Full tank" else "Partial" }, { if (it) "garage_refuel_full" else "garage_refuel_partial" }) {
                    haptics.tick(); full = it
                }
                Segments(FuelType.entries, fuel, { it.label }, { "garage_refuel_fuel_${it.name}" }) {
                    haptics.tick()
                    fuel = it
                    price = wholeNumber(priceOf(it, prices))
                }
                Segments(AmountMode.entries, mode, { if (it == AmountMode.RUPIAH) "Paid (Rp)" else "Litres" }, { "garage_refuel_mode_${it.name}" }) {
                    haptics.tick(); mode = it; amount = ""
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        amount, { amount = it }, if (mode == AmountMode.RUPIAH) "Paid (Rp)" else "Litres",
                        amount.isNotBlank() && amounts == null, "garage_refuel_amount", Modifier.weight(1f),
                        whole = mode == AmountMode.RUPIAH,
                    )
                    NumberField(price, { price = it }, "Rp per litre", priceValue == null, "garage_refuel_price", Modifier.weight(1f), whole = true)
                }
                Text(
                    amounts?.let { (litres, rp) -> if (mode == AmountMode.RUPIAH) "= ${formatLitres(litres)}" else "= ${formatRupiah(rp)}" } ?: " ",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppTheme.colors.textDim,
                    modifier = Modifier.testTag("garage_refuel_result"),
                )
                NumberField(
                    odometer, { odometer = it }, "Odometer (km)", odometer.isNotBlank() && odoKm == null, "garage_refuel_odo",
                    Modifier.fillMaxWidth(), whole = true,
                )
            }
        }
    }
}

/** "Add untracked km": the rider rode without the app. */
@Composable
internal fun UntrackedKmDialog(bike: BikeRecord, estimateKm: Double?, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    val km = parseNumber(value, thousands = true)?.takeIf { it > 0 && it <= 5_000 }
    GarageDialog(
        tag = "garage_untracked_dialog",
        title = "Add untracked km · ${bike.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(km!!) },
                enabled = km != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_untracked_save"),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) { Text("Add") }
        },
    ) {
        Text(
            "Rode ${bike.name} without the app? Add the km here: they count for fuel and servicing, but not as trips.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        NumberField(
            value, { value = it }, "Km ridden", value.isNotBlank() && km == null, "garage_untracked_input", Modifier.fillMaxWidth(),
            whole = true, onDone = { km?.let(onSave) },
        )
        if (km != null && estimateKm != null) {
            Text(
                "Odometer becomes ${formatOdometer(estimateKm + km)}",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textDim,
            )
        }
    }
}

/** 12500.0 → "12500" (typed back into a field). */
private fun wholeNumber(v: Double): String = String.format(Locale.US, "%d", v.roundToLong())
