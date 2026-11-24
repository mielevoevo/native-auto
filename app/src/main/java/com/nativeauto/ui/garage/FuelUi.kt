package com.nativeauto.ui.garage

import com.nativeauto.ui.history.formatDay
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import com.nativeauto.garage.Consumption
import com.nativeauto.garage.KMPL_PLAUSIBLE
import com.nativeauto.garage.gaugeBars
import com.nativeauto.garage.gaugeLitres
import com.nativeauto.garage.costPerKm
import com.nativeauto.garage.formatKmPerL
import com.nativeauto.garage.formatLitres
import com.nativeauto.garage.formatOdometer
import com.nativeauto.garage.formatRupiah
import com.nativeauto.garage.parseNumber
import com.nativeauto.garage.priceOf
import com.nativeauto.garage.refuelAmounts
import com.nativeauto.garage.refuelLitresFit
import com.nativeauto.ui.components.DialogTwoColumns
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import com.nativeauto.ui.theme.appSwitchColors
import com.nativeauto.garage.spend
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 11b: the bike's fuel in the Garage: range, the Refuel button, km since the last fill-up,
 * learned km/L, the refill cost (hidden until asked) and the refuel log.
 */
@Composable
internal fun FuelSection(
    bike: BikeRecord,
    fuel: FuelState?,
    refuelArmed: Long?,
    onRefuel: () -> Unit,
    onDeleteRefuel: (Long) -> Unit,
    onSetLevel: () -> Unit = {},
    prices: List<FuelPrice> = emptyList(),
    onEditPrice: (FuelType) -> Unit = {},
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
            val range = level?.cautiousRangeKm
            val low = level?.low == true
            Text(
                range?.let { "$it km" } ?: "—",
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
            Text("Refuel")
        }
    }
    fuel?.let { f ->
        // 4.3d: only when the riding lately moves the range by 5 % or more.
        f.load.note?.let { LoadNote(it) }
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 12b: a level from the bike's own gauge, before (or between) full fill-ups.
        OutlinedButton(onClick = onSetLevel, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_set_level")) {
            Text("Set fuel level")
        }
        OutlinedButton(
            onClick = { haptics.toggle(!showCost); showCost = !showCost },
            modifier = Modifier.heightIn(min = 56.dp).testTag("garage_cost_toggle"),
        ) { Text(if (showCost) "Hide costs" else "Calculate refill") }
    }
    if (showCost && fuel != null) CostPanel(fuel)
    FuelPrices(prices, onEditPrice)
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

/** "4.1 L of 6.6 L · 36.4 km/L from 3 fill-ups", or why the level is unknown. */
private fun fuelLine(f: FuelState): String {
    val kmpl = formatKmPerL(f.consumption.kmPerL) + " " + kmplSource(f.consumption)
    val level = f.level
    return when {
        level.litres != null -> "${formatLitres(level.litres)} of ${trimNumber(level.tankLitres)} L" +
            (if (level.fromGauge) " (from the gauge)" else "") + " · $kmpl" + cautiousNote(f)
        level.unknownBecause == UnknownFuel.AWAY -> "Unknown after away km: set the level or fill up · $kmpl"
        else -> "Set the level from the gauge, or log a full fill-up · $kmpl"
    }
}

/** 12b: where the km/L comes from: "from 3 fill-ups + 2 rides", or "(starting value)". */
internal fun kmplSource(c: Consumption): String {
    if (!c.learned) return "(starting value)"
    val parts = buildList {
        if (c.tanksUsed > 0) add(if (c.tanksUsed == 1) "1 fill-up" else "${c.tanksUsed} fill-ups")
        if (c.dashUsed > 0) add(if (c.dashUsed == 1) "1 ride" else "${c.dashUsed} rides")
    }
    return "from " + parts.joinToString(" + ")
}

/** 12b: " · range at 34.0 km/L" when recent tanks are below the long run (the range uses those). */
/** 4.3d: "Riding hard: −12%" under the range. textDim like the fuel line: it explains the number, it's no warning. */
@Composable
internal fun LoadNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim, modifier = Modifier.testTag("garage_load_note"))
}

internal fun cautiousNote(f: FuelState): String =
    if (f.consumption.cautiousKmPerL < f.consumption.kmPerL - 0.05) " · range at ${formatKmPerL(f.consumption.cautiousKmPerL)}" else ""

/**
 * 12b "Set fuel level": the bars lit on the bike's gauge (6 on the NMAX gen 1, 8 on gen 2) and
 * the odometer. The level counts the last bar as half used, so the range leans low.
 */
@Composable
internal fun FuelLevelDialog(bike: BikeRecord, estimateKm: Double?, onSave: (bars: Int, ofBars: Int, odometerKm: Double) -> Unit, onDismiss: () -> Unit) {
    val of = gaugeBars(bike.bikeModel)
    var bars by rememberSaveable { mutableStateOf<Int?>(null) }
    var odoText by rememberSaveable { mutableStateOf(estimateKm?.let(::wholeNumber) ?: "") }
    // The estimate can arrive after the dialog opens (the Garage screen reads it as a flow): fill it in then.
    var prefilled by rememberSaveable { mutableStateOf(estimateKm != null) }
    LaunchedEffect(estimateKm) {
        if (!prefilled && estimateKm != null && odoText.isBlank()) odoText = wholeNumber(estimateKm)
        if (estimateKm != null) prefilled = true
    }
    val odo = parseNumber(odoText, thousands = true)?.takeIf { it >= 0 }
    val ok = bars != null && odo != null
    GarageDialog(
        tag = "garage_level_dialog",
        title = "Set fuel level · ${bike.name}",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(bars!!, of, odo!!) },
                enabled = ok,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_level_save"),
            ) { Text("Save") }
        },
    ) {
        Text(
            "How many bars does the gauge show? The range works from there, on the low side, until the next full fill-up.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        Segments<Int?>((0..of).toList(), bars, { "$it" }, { "garage_level_bar_$it" }) { bars = it }
        bars?.let { b ->
            Text(
                "About ${formatLitres(gaugeLitres(b, of, bike.tankLitres))} of ${trimNumber(bike.tankLitres)} L",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.textDim,
                modifier = Modifier.testTag("garage_level_litres"),
            )
        }
        NumberField(odoText, { odoText = it }, "Odometer (km)", odoText.isNotBlank() && odo == null, "garage_level_odo", Modifier.fillMaxWidth(), whole = true)
    }
}

/** 12b: the bike's own average for the ride just ended (its dash), optional. */
@Composable
fun DashKmPerLDialog(bikeName: String, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    val kmpl = parseNumber(value)?.takeIf { it in KMPL_PLAUSIBLE }
    GarageDialog(
        tag = "kmpl_dialog",
        title = "$bikeName's km/L",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag("kmpl_skip")) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(kmpl!!) },
                enabled = kmpl != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("kmpl_save"),
            ) { Text("Save") }
        },
    ) {
        Text(
            "What average did the dash show for this ride? Optional: it helps the range estimate.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        NumberField(value, { value = it }, "km/L", value.isNotBlank() && kmpl == null, "kmpl_value", Modifier.fillMaxWidth(), onDone = { kmpl?.let(onSave) })
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
    val amounts = refuelAmounts(mode, amountValue, priceValue)?.takeIf { (litres, _) -> refuelLitresFit(litres, bike.tankLitres) }
    val odoKm = parseNumber(odometer, thousands = true)
    val valid = amounts != null && odoKm != null

    GarageDialog(
        tag = "garage_refuel_dialog",
        title = "Refuel · ${bike.name}",
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
        DialogTwoColumns {
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 13a: one switch instead of Full tank / Partial. On by default: most fill-ups go to the top.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = full, role = Role.Switch) { haptics.toggle(it); full = it }
                        .testTag("garage_refuel_full"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Full refill", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = full, onCheckedChange = null, colors = appSwitchColors())
                }
                Segments(FuelType.entries, fuel, { it.label }, { "garage_refuel_fuel_${it.name}" }, label = { FuelLabel(it) }) {
                    haptics.tick()
                    fuel = it
                    price = wholeNumber(priceOf(it, prices))
                }
                Segments(AmountMode.entries, mode, { if (it == AmountMode.RUPIAH) "Paid (Rp)" else "Litres" }, { "garage_refuel_mode_${it.name}" }) {
                    haptics.tick(); mode = it; amount = ""
                }
            }
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        amount, { amount = it }, if (mode == AmountMode.RUPIAH) "Paid (Rp)" else "Litres",
                        amount.isNotBlank() && amounts == null, "garage_refuel_amount", Modifier.weight(1f),
                        whole = mode == AmountMode.RUPIAH,
                    )
                    NumberField(price, { price = it }, "Rp per litre", priceValue == null, "garage_refuel_price", Modifier.weight(1f), whole = true)
                }
                Text(
                    amounts?.let { (litres, rp) -> if (mode == AmountMode.RUPIAH) "= ${formatLitres(litres)}" else "= ${formatRupiah(rp)}" }
                        ?: "Tank holds ${formatLitres(bike.tankLitres)}",
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

/**
 * 12c: the price per litre of each fuel (shared by every bike), editable when the rate changes.
 * A fill-up at a new price updates it too; past fill-ups keep the price they were logged at.
 */
@Composable
private fun FuelPrices(prices: List<FuelPrice>, onEdit: (FuelType) -> Unit) {
    val colors = AppTheme.colors
    Spacer(Modifier.height(12.dp))
    Text("Fuel prices", style = MaterialTheme.typography.titleMedium)
    Text("Tap one when the rate changes.", style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
    Spacer(Modifier.height(4.dp))
    FuelType.entries.forEach { type ->
        val own = prices.firstOrNull { it.fuelType == type.name }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onEdit(type) }.testTag("garage_price_${type.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(type.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${formatRupiah(priceOf(type, prices))} / L",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.testTag("garage_price_value_${type.name}"),
                )
                Text(
                    own?.let { "updated ${formatDay(it.updatedAtMs)}" } ?: "default",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                )
            }
        }
    }
}

/** 12c: one fuel's price per litre. */
@Composable
internal fun FuelPriceDialog(fuel: FuelType, current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(wholeNumber(current)) }
    val price = parseNumber(value, thousands = true)?.takeIf { it in 1_000.0..100_000.0 }
    GarageDialog(
        tag = "garage_price_dialog",
        title = "${fuel.label} price",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(price!!) },
                enabled = price != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_price_save"),
            ) { Text("Save") }
        },
    ) {
        Text(
            "Rupiah per litre. Used for the next fill-ups and the refill estimate; past fill-ups keep their price.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        NumberField(value, { value = it }, "Rp / L", value.isNotBlank() && price == null, "garage_price_input", Modifier.fillMaxWidth(), whole = true, onDone = { price?.let(onSave) })
    }
}
