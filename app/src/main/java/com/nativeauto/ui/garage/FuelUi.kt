package com.nativeauto.ui.garage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nativeauto.garage.Fuel
import com.nativeauto.garage.CustomFuel
import com.nativeauto.garage.DashKmPerL
import com.nativeauto.garage.DEFAULT_KM_PER_L
import com.nativeauto.garage.MIN_MODEL_STEPS
import com.nativeauto.garage.MIN_MODEL_TANKS
import com.nativeauto.garage.PRICE_RANGE
import com.nativeauto.garage.RefuelIssue
import com.nativeauto.garage.fuelChoices
import com.nativeauto.garage.fuelOf
import com.nativeauto.garage.reviewRefuel
import com.nativeauto.garage.FuelType
import com.nativeauto.garage.RefuelDraft
import com.nativeauto.garage.RefuelRecord
import com.nativeauto.garage.UnknownFuel
import com.nativeauto.garage.Consumption
import com.nativeauto.garage.KMPL_PLAUSIBLE
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
import androidx.compose.ui.semantics.Role
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
    fuels: List<CustomFuel> = emptyList(),
    onEditPrice: (Fuel) -> Unit = {},
    onAddFuel: () -> Unit = {},
    onEditRefuel: (RefuelRecord) -> Unit = {},
    dashArmed: Long? = null,
    onEditDash: (DashKmPerL) -> Unit = {},
    onDeleteDash: (Long) -> Unit = {},
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
        // 4.8: only when the riding lately moves the range by 5 % or more.
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
        rangeModeLine(f)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.testTag("garage_range_mode"))
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
    FuelPrices(prices, fuels, onEditPrice, onAddFuel)
    if (fuel != null && fuel.refuels.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Refuel log", style = MaterialTheme.typography.titleMedium)
        Text("Tap one to correct it.", style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
        Spacer(Modifier.height(4.dp))
        val measures = fuel.consumption.measures.associateBy { it.endRefuelId }
        LogRows(fuel.refuels, "garage_refuel_all", keep = { it.id == refuelArmed }) { r ->
            val measure = measures[r.id]?.let { m -> formatKmPerL(m.kmPerL) + if (m.used) "" else " (not used)" }
            LogRow(
                title = formatOdometer(r.odometerKm),
                detail = listOfNotNull(readingDate(r.timeMs), "${if (r.full) "Full" else "Partial"} ${formatLitres(r.litres)}", fuelOf(r.fuelType, fuels).label, measure)
                    .joinToString(" · "),
                tag = "garage_refuel_row",
                deleteTag = "garage_refuel_delete",
                deleteLabel = "Delete fill-up",
                armed = refuelArmed == r.id,
                onClick = { haptics.tap(); onEditRefuel(r) },
                onDelete = { onDeleteRefuel(r.id) },
            )
        }
    }
    if (fuel != null && fuel.dash.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Dash km/L", style = MaterialTheme.typography.titleMedium)
        Text(dashLine(fuel.consumption), style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.testTag("garage_dash_line"))
        Spacer(Modifier.height(4.dp))
        LogRows(fuel.dash, "garage_dash_all", keep = { it.id == dashArmed }) { d ->
            LogRow(
                title = formatKmPerL(d.kmPerL),
                detail = "${readingDate(d.timeMs)} · after ${trimNumber(d.tripKm)} km",
                tag = "garage_dash_row",
                deleteTag = "garage_dash_delete",
                deleteLabel = "Delete km/L entry",
                armed = dashArmed == d.id,
                onClick = { haptics.tap(); onEditDash(d) },
                onDelete = { onDeleteDash(d.id) },
            )
        }
    }
}

/** 4.8: where the dash figures stand against the bike's own tanks. */
private fun dashLine(c: Consumption): String {
    val off = (c.dashRatio - 1) * 100
    return if (kotlin.math.abs(off) < 0.5) {
        "What you entered after rides. Full tanks will show how exact the dash is."
    } else {
        "By your tanks the dash reads ${String.format(Locale.US, "%.0f", kotlin.math.abs(off))}% ${if (off > 0) "low" else "high"}; the estimate allows for it."
    }
}

/** 4.8: whether the range follows the riding, or how far that is. Nothing before the first dash figure. */
private fun rangeModeLine(f: FuelState): String? {
    val l = f.learning
    return when {
        f.load.on -> "Range follows your riding"
        f.dash.isEmpty() -> null
        l.steps < MIN_MODEL_STEPS || l.tanks < MIN_MODEL_TANKS ->
            "Learning your riding: ${minOf(l.steps, MIN_MODEL_STEPS)} of $MIN_MODEL_STEPS rides, ${minOf(l.tanks, MIN_MODEL_TANKS)} of $MIN_MODEL_TANKS full tanks"
        else -> "Learning your riding: not more exact than the usual range yet"
    }
}

/** The first [LOG_ROWS] entries, the rest behind "Show all". [keep]: always shown (a delete that waits for its second tap). */
@Composable
private fun <T> LogRows(entries: List<T>, tag: String, keep: (T) -> Boolean, row: @Composable (T) -> Unit) {
    var all by rememberSaveable(tag) { mutableStateOf(false) }
    val shown = if (all) entries else entries.filterIndexed { i, e -> i < LOG_ROWS || keep(e) }
    shown.forEach { row(it) }
    if (entries.size > LOG_ROWS) {
        TextButton(onClick = { all = !all }, modifier = Modifier.heightIn(min = 56.dp).testTag(tag)) {
            Text(if (all) "Show less" else "Show all ${entries.size}")
        }
    }
}

private const val LOG_ROWS = 5

/** One entry of a log: a figure and its details; a tap edits it, the bin deletes it (two taps). */
@Composable
private fun LogRow(
    title: String,
    detail: String,
    tag: String,
    deleteTag: String,
    deleteLabel: String,
    armed: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    HorizontalDivider(color = colors.outline)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(56.dp).testTag(deleteTag),
            colors = if (armed) IconButtonDefaults.iconButtonColors(contentColor = colors.red) else IconButtonDefaults.iconButtonColors(),
        ) { Icon(AppIcons.Delete, contentDescription = if (armed) "Tap again to delete" else deleteLabel) }
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

/** 4.3d: "Riding hard: −12%" under the range. textDim like the fuel line: it explains the number, it's no warning. */
@Composable
internal fun LoadNote(note: String) {
    Text(note, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim, modifier = Modifier.testTag("garage_load_note"))
}

/** 12b: " · range at 34.0 km/L" when recent tanks are below the long run (the range uses those). */
internal fun cautiousNote(f: FuelState): String =
    if (f.consumption.cautiousKmPerL < f.consumption.kmPerL - 0.05) " · range at ${formatKmPerL(f.consumption.cautiousKmPerL)}" else ""

/**
 * 12b "Set fuel level": the bars lit on the bike's gauge (4.7: as many as the bike has) and
 * the odometer. The level counts the last bar as half used, so the range leans low.
 */
@Composable
internal fun FuelLevelDialog(bike: BikeRecord, estimateKm: Double?, onSave: (bars: Int, ofBars: Int, odometerKm: Double) -> Unit, onDismiss: () -> Unit) {
    val of = bike.gaugeBars
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
        BarPicker(of, bars) { bars = it }
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

/** 4.7: 0 to [of] bars as big cells; a portrait screen takes more than one row of them. */
@Composable
private fun BarPicker(of: Int, bars: Int?, onPick: (Int) -> Unit) {
    val colors = AppTheme.colors
    val haptics = rememberHaptics()
    val options = (0..of).toList()
    val perRow = if (com.nativeauto.ui.components.LocalPortrait.current) BARS_PER_ROW_PORTRAIT else options.size
    val rows = (options.size + perRow - 1) / perRow
    val width = (options.size + rows - 1) / rows
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(width).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { n ->
                    val on = n == bars
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .selectable(selected = on, role = Role.RadioButton) { haptics.tick(); onPick(n) }
                            .testTag("garage_level_bar_$n"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (on) colors.navy else colors.surface,
                        contentColor = if (on) colors.onNavy else colors.text,
                        border = BorderStroke(1.dp, colors.outline),
                    ) {
                        Box(Modifier.heightIn(min = 56.dp), contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.titleMedium) }
                    }
                }
                // The last row's cells stay as wide as the others.
                repeat(width - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val BARS_PER_ROW_PORTRAIT = 5

/**
 * 12b: the bike's own average on its dash, after a ride or (4.8) corrected later ([title]). Opens with [initial], the last
 * one given: the dash carries its average over rides, so it is often the same or a step away.
 */
@Composable
fun DashKmPerLDialog(
    title: String,
    initial: Double?,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
    skipLabel: String = "Skip",
    startAt: Double = DEFAULT_KM_PER_L,
) {
    val haptics = rememberHaptics()
    var value by rememberSaveable { mutableStateOf(initial?.let(::tenths) ?: "") }
    val kmpl = parseNumber(value)?.takeIf { it in KMPL_PLAUSIBLE }
    val step = { by: Double ->
        haptics.tick()
        value = tenths(((kmpl ?: startAt) + by).coerceIn(KMPL_PLAUSIBLE.start, KMPL_PLAUSIBLE.endInclusive))
    }
    GarageDialog(
        tag = "kmpl_dialog",
        title = title,
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp).testTag("kmpl_skip")) { Text(skipLabel) }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(kmpl!!) },
                enabled = kmpl != null,
                modifier = Modifier.heightIn(min = 56.dp).testTag("kmpl_save"),
            ) { Text("Save") }
        },
    ) {
        Text(
            "The average on the bike's dash. Optional: it helps the range.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StepButton("−", "Lower: km/L", "kmpl_minus", enabled = kmpl == null || kmpl > KMPL_PLAUSIBLE.start) { step(-0.1) }
            NumberField(value, { value = it }, "km/L", value.isNotBlank() && kmpl == null, "kmpl_value", Modifier.weight(1f), onDone = { kmpl?.let(onSave) })
            StepButton("+", "Raise: km/L", "kmpl_plus", enabled = kmpl == null || kmpl < KMPL_PLAUSIBLE.endInclusive) { step(0.1) }
        }
    }
}

/** 35.64 → "35.6" (the dash's own steps). */
private fun tenths(v: Double): String = String.format(Locale.US, "%.1f", v)

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

/**
 * The pump, a few taps: full (the default) or partial; what was paid in Rp or litres, the other
 * worked out from the price per litre (prefilled with the fuel's last price, correctable); and
 * the odometer, prefilled with the estimate (the real reading is the truth).
 * 4.8: [existing] corrects one logged before; an entry that looks wrong is asked about before it is saved ([reviewRefuel]).
 */
@Composable
internal fun RefuelDialog(
    bike: BikeRecord,
    estimateKm: Double?,
    prices: List<FuelPrice>,
    onSave: (RefuelDraft) -> Unit,
    onDismiss: () -> Unit,
    fuels: List<CustomFuel> = emptyList(),
    state: FuelState? = null,
    existing: RefuelRecord? = null,
) {
    val haptics = rememberHaptics()
    var full by rememberSaveable { mutableStateOf(existing?.full ?: true) }
    var learn by rememberSaveable { mutableStateOf(existing?.learn ?: true) }
    var fuelKey by rememberSaveable { mutableStateOf(existing?.fuelType ?: bike.fuelType) }
    val fuel = fuelOf(fuelKey, fuels)
    var mode by rememberSaveable { mutableStateOf(if (existing == null) AmountMode.RUPIAH else AmountMode.LITRES) }
    val firstAmount = existing?.let { exact(it.litres) } ?: ""
    var amount by rememberSaveable { mutableStateOf(firstAmount) }
    val firstPrice = existing?.let { wholeNumber(it.pricePerL) } ?: priceText(priceOf(fuel, prices))
    var price by rememberSaveable { mutableStateOf(firstPrice) }
    val firstOdometer = (existing?.odometerKm ?: estimateKm)?.let(::wholeNumber) ?: ""
    var odometer by rememberSaveable { mutableStateOf(firstOdometer) }
    /** What looks wrong about the entry; null = the form. */
    var review by remember { mutableStateOf<List<RefuelIssue>?>(null) }

    val amountValue = if (mode == AmountMode.RUPIAH) parseNumber(amount, thousands = true) else parseNumber(amount)
    val priceValue = parseNumber(price, thousands = true)?.takeIf { it in PRICE_RANGE }
    // What wasn't touched stays exactly what it was (the fields show it rounded).
    val untouched = existing != null && mode == AmountMode.LITRES && amount == firstAmount && price == firstPrice
    val amounts = (if (untouched) existing!!.litres to existing.totalRp else refuelAmounts(mode, amountValue, priceValue))
        ?.takeIf { (litres, _) -> untouched || refuelLitresFit(litres, bike.tankLitres) }
    val odoKm = if (existing != null && odometer == firstOdometer) existing.odometerKm else parseNumber(odometer, thousands = true)
    val valid = amounts != null && odoKm != null && priceValue != null

    fun draft(learns: Boolean) = RefuelDraft(
        full, amounts!!.first, if (untouched) existing!!.pricePerL else priceValue!!, amounts.second, fuel, odoKm!!, learns,
    )
    fun save() {
        val d = draft(learn)
        val issues = if (state == null || !d.learn) {
            emptyList()
        } else {
            reviewRefuel(
                entry = RefuelRecord(
                    existing?.id ?: 0, bike.id, existing?.timeMs ?: System.currentTimeMillis(), d.odometerKm, d.litres, d.pricePerL, d.totalRp, d.full, fuel.key,
                ),
                refuels = state.refuels,
                startKmPerL = bike.startKmPerL,
                awayTimesMs = state.awayTimesMs,
                dash = state.dash,
                tankLitres = bike.tankLitres,
                roomL = state.level.toFillLitres.takeIf { existing == null },
                estimateKm = estimateKm.takeIf { existing == null },
                knownPrice = if (existing != null && price == firstPrice) 0.0 else priceOf(fuel, prices),
            )
        }
        if (issues.isEmpty()) onSave(d) else review = issues
    }

    GarageDialog(
        tag = "garage_refuel_dialog",
        title = when {
            review != null -> "Check this fill-up"
            existing != null -> "Fill-up · ${readingDate(existing.timeMs)}"
            else -> "Refuel · ${bike.name}"
        },
        onDismiss = onDismiss,
        action = {
            if (review == null) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { save() }, enabled = valid, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_refuel_save")) { Text("Save") }
            } else {
                FilledTonalButton(
                    onClick = { haptics.tap(); review = null },
                    modifier = Modifier.heightIn(min = 56.dp).testTag("garage_review_fix"),
                ) { Text("Fix") }
            }
        },
    ) {
        review?.let { issues ->
            issues.forEach { Text(it.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("garage_review_issue")) }
            OutlinedButton(
                onClick = { onSave(draft(learn)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("garage_review_save"),
            ) { Text("It's right: save it", color = AppTheme.colors.text) }
            if (issues.any { it.learning }) {
                OutlinedButton(
                    onClick = { onSave(draft(false)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("garage_review_no_learn"),
                ) { Text("Save, but don't learn km/L from it", color = AppTheme.colors.text) }
            }
            return@GarageDialog
        }
        DialogTwoColumns {
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 13a: one switch instead of Full tank / Partial. On by default: most fill-ups go to the top.
                FormSwitch("Full refill", full, "garage_refuel_full") { haptics.toggle(it); full = it }
                if (existing != null) FormSwitch("Learn km/L from it", learn, "garage_refuel_learn") { haptics.toggle(it); learn = it }
                FuelPicker(fuelChoices(fuels, fuel), fuel, "garage_refuel_fuel") {
                    haptics.tick()
                    fuelKey = it.key
                    // A fuel's own price; a correction keeps the one that was paid.
                    if (existing == null) price = priceText(priceOf(it, prices))
                }
                Segments(AmountMode.entries, mode, { if (it == AmountMode.RUPIAH) "Paid (Rp)" else "Liters" }, { "garage_refuel_mode_${it.name}" }) {
                    haptics.tick(); mode = it; amount = ""
                }
            }
            Column(column, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(
                        amount, { amount = it }, if (mode == AmountMode.RUPIAH) "Paid (Rp)" else "Liters",
                        amount.isNotBlank() && amounts == null, "garage_refuel_amount", Modifier.weight(1f),
                        whole = mode == AmountMode.RUPIAH,
                    )
                    NumberField(price, { price = it }, "Rp per liter", priceValue == null, "garage_refuel_price", Modifier.weight(1f), whole = true)
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

/** 4.665 → "4.665", 5.0 → "5". */
private fun exact(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

/** A price for its field; blank while there is none. */
private fun priceText(v: Double): String = if (v > 0) wholeNumber(v) else ""

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
 * A fill-up at a new price updates it too; past fill-ups keep the price they were logged at. 4.8: the rider's own too.
 */
@Composable
private fun FuelPrices(prices: List<FuelPrice>, fuels: List<CustomFuel>, onEdit: (Fuel) -> Unit, onAdd: () -> Unit) {
    val colors = AppTheme.colors
    Spacer(Modifier.height(12.dp))
    Text("Fuel prices", style = MaterialTheme.typography.titleMedium)
    Text("Tap one when the rate changes.", style = MaterialTheme.typography.bodyMedium, color = colors.textDim)
    Spacer(Modifier.height(4.dp))
    fuelChoices(fuels).forEach { fuel ->
        val own = prices.firstOrNull { it.fuelType == fuel.key }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onEdit(fuel) }.testTag("garage_price_${fuelTag(fuel)}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fuel.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${formatRupiah(priceOf(fuel, prices))} / L",
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.testTag("garage_price_value_${fuelTag(fuel)}"),
                )
                Text(
                    own?.let { "updated ${formatDay(it.updatedAtMs)}" } ?: "default",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                )
            }
        }
    }
    TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_fuel_add")) { Text("+ Add a fuel") }
}

/** A pump fuel's tag is its name, the rider's own by its label. */
internal fun fuelTag(fuel: Fuel): String = if (fuel is FuelType) fuel.name else fuel.label

/**
 * 4.8: adds a fuel of the rider's own ([fuel] null), or changes one's name and price. [taken]: names that are there already.
 */
@Composable
internal fun CustomFuelDialog(
    fuel: CustomFuel?,
    current: Double,
    taken: List<String>,
    onSave: (name: String, pricePerL: Double) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val haptics = rememberHaptics()
    var name by rememberSaveable { mutableStateOf(fuel?.name ?: "") }
    var value by rememberSaveable { mutableStateOf(priceText(current)) }
    var armed by rememberSaveable { mutableStateOf(false) }
    val price = parseNumber(value, thousands = true)?.takeIf { it in PRICE_RANGE }
    val known = taken.any { it.equals(name.trim(), ignoreCase = true) }
    val ok = name.isNotBlank() && !known && price != null
    GarageDialog(
        tag = "garage_fuel_dialog",
        title = fuel?.let { "Edit ${it.name}" } ?: "Add a fuel",
        onDismiss = onDismiss,
        action = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onSave(name.trim(), price!!) },
                enabled = ok,
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_fuel_save"),
            ) { Text("Save") }
        },
    ) {
        Text("For every bike. Past fill-ups keep the price they were made at.", style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = { Text(if (known) "Already there" else "Name") },
                isError = known,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag("garage_fuel_name"),
            )
            NumberField(value, { value = it }, "Rp / L", value.isNotBlank() && price == null, "garage_fuel_price", Modifier.weight(1f), whole = true)
        }
        if (onDelete != null) {
            FilledTonalButton(
                onClick = { if (armed) onDelete() else { haptics.tap(); armed = true } },
                modifier = Modifier.heightIn(min = 56.dp).testTag("garage_fuel_delete"),
                colors = if (armed) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) { Text(if (armed) "Tap again to delete" else "Delete fuel") }
        }
    }
}

/** 12c: one fuel's price per litre. */
@Composable
internal fun FuelPriceDialog(fuel: Fuel, current: Double, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable { mutableStateOf(wholeNumber(current)) }
    val price = parseNumber(value, thousands = true)?.takeIf { it in PRICE_RANGE }
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
            "Rupiah per liter. Used for the next fill-ups and the refill estimate; past fill-ups keep their price.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTheme.colors.textDim,
        )
        NumberField(value, { value = it }, "Rp / L", value.isNotBlank() && price == null, "garage_price_input", Modifier.fillMaxWidth(), whole = true, onDone = { price?.let(onSave) })
    }
}
