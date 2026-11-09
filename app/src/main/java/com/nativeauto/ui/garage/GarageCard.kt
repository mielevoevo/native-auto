package com.nativeauto.ui.garage

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.DueLevel
import com.nativeauto.garage.FuelPrice
import com.nativeauto.garage.GarageCardState
import com.nativeauto.garage.GarageRepository
import com.nativeauto.garage.RefuelDraft
import com.nativeauto.garage.ServiceDue
import com.nativeauto.garage.dueText
import com.nativeauto.garage.formatOdometer
import com.nativeauto.garage.reminderText
import com.nativeauto.settings.GarageValue
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.cardModeTransition
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.Geist
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The active bike's card, or null with no bike; [loaded] false until the database answers. */
data class GarageCardUi(val loaded: Boolean = false, val state: GarageCardState? = null) {
    /** The tank's last segment (§6): the speed card's small red icon, and red on the Garage card. */
    val lowFuel: Boolean get() = state?.fuel?.level?.fraction?.let { it <= LOW_FUEL_FRACTION } == true

    /** The service reminder for the log face and the trip summary (§6 "When reminders appear"). */
    val reminder: ServiceDue? get() = state?.reminder
}

/** One per activity: the Garage card, the speed card's low-fuel icon and the service reminder share it. */
@HiltViewModel
class GarageCardViewModel @Inject constructor(
    private val garage: GarageRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val ui: StateFlow<GarageCardUi> = garage.activeCard.map { GarageCardUi(true, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GarageCardUi())

    val value: StateFlow<GarageValue> = settings.garageValue.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GarageValue.RANGE)

    val bikes: StateFlow<List<BikeRecord>> = garage.bikes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val prices: StateFlow<List<FuelPrice>> = garage.prices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setValue(value: GarageValue) = viewModelScope.launch { settings.setGarageValue(value) }

    fun ride(id: String) = viewModelScope.launch { garage.activate(id) }

    fun logRefuel(bikeId: String, draft: RefuelDraft) = viewModelScope.launch { garage.logRefuel(bikeId, draft) }
}

// ---- The tank: 5 chunky segments (Rider-Feedback §6) ----

const val TANK_SEGMENTS = 5

/** So a segment doesn't flicker on and off around a boundary. */
const val TANK_HYSTERESIS = 0.02

/**
 * Segments lit for [fraction] of a tank: n while the fraction is in ((n−1)/5, n/5]. Next to
 * [previous] a boundary has to be crossed by [TANK_HYSTERESIS] before the count changes.
 */
fun tankSegments(fraction: Double, previous: Int?): Int {
    val f = fraction.coerceIn(0.0, 1.0)
    val raw = ceil(f * TANK_SEGMENTS - 1e-9).toInt().coerceIn(0, TANK_SEGMENTS)
    if (previous == null || previous == raw || abs(previous - raw) > 1) return raw
    return if (raw < previous) {
        if (f <= (previous - 1).toDouble() / TANK_SEGMENTS - TANK_HYSTERESIS) raw else previous
    } else {
        if (f > previous.toDouble() / TANK_SEGMENTS + TANK_HYSTERESIS) raw else previous
    }
}

/**
 * A fuel-tank icon of stacked bars, like a retro battery meter: lit segments white; at the last
 * one it turns red (no amber step, no blinking); empty slots stay as faint outlines, so the
 * tank's shape is always there. Unknown fuel: all outlines.
 */
@Composable
fun TankGauge(fraction: Double?, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val held = remember { intArrayOf(-1) }
    val lit = fraction?.let { tankSegments(it, held[0].takeIf { h -> h >= 0 }) } ?: 0
    held[0] = if (fraction == null) -1 else lit
    val fill = if (fraction != null && lit <= 1) colors.red else colors.text
    val outline = colors.outline
    val description = if (fraction == null) "Fuel unknown" else "Fuel $lit of $TANK_SEGMENTS"
    Canvas(modifier.semantics { contentDescription = description }.testTag("garage_tank")) {
        val stroke = size.width * 0.06f
        val capH = size.height * 0.08f
        val capW = size.width * 0.4f
        // Filler cap, then the body.
        drawRoundRect(outline, Offset((size.width - capW) / 2, 0f), Size(capW, capH), CornerRadius(stroke))
        val top = capH + stroke
        val bodyH = size.height - top
        drawRoundRect(outline, Offset(stroke / 2, top), Size(size.width - stroke, bodyH - stroke / 2), CornerRadius(stroke * 2), style = Stroke(stroke))
        val pad = stroke * 2
        val gap = stroke
        val inner = bodyH - pad * 2
        val segH = (inner - gap * (TANK_SEGMENTS - 1)) / TANK_SEGMENTS
        for (i in 0 until TANK_SEGMENTS) {
            // Bottom up: segment 0 is the last to go.
            val y = top + pad + (TANK_SEGMENTS - 1 - i) * (segH + gap)
            val on = i < lit
            drawRoundRect(
                color = if (on) fill else outline,
                topLeft = Offset(pad, y),
                size = Size(size.width - pad * 2, segH),
                cornerRadius = CornerRadius(stroke),
                style = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke / 2),
            )
        }
    }
}

// ---- The card ----

/**
 * 11c: the Garage as a pane. Normal size: one tracked value, big (range by default, with the
 * tank). Maximized or fullscreen: the full set, the choice of that one value, the bike picker
 * and the Refuelled button (§6, decided 2026-11-08: Refuelled only on the expanded card). No
 * cost figures on the card.
 */
@Composable
fun GarageCard(
    mode: CardMode,
    layout: DashboardLayoutState,
    onOpenGarage: () -> Unit,
    viewModel: GarageCardViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val value by viewModel.value.collectAsStateWithLifecycle()
    val state = ui.state
    var refuelling by rememberSaveable { mutableStateOf(false) }
    DashboardCard(title = state?.bike?.name?.let { "Garage · $it" } ?: "Garage", pane = Pane.GARAGE, mode = mode, layout = layout) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { cardModeTransition() },
            contentAlignment = Alignment.Center,
            label = "garageMode",
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
        ) { m ->
            when {
                !ui.loaded -> Box(Modifier.fillMaxSize())
                state == null -> NoBike(onOpenGarage)
                m == CardMode.Compact || m == CardMode.Wide -> SmallGarage(state, value, m)
                else -> ExpandedGarage(state, value, viewModel, onRefuel = { refuelling = true }, onOpenGarage)
            }
        }
    }
    val s = state
    if (refuelling && s != null) {
        val prices by viewModel.prices.collectAsStateWithLifecycle()
        val haptics = rememberHaptics()
        RefuelDialog(
            bike = s.bike,
            estimateKm = s.odometer.estimateKm,
            prices = prices,
            onSave = { draft -> haptics.confirm(); viewModel.logRefuel(s.bike.id, draft); refuelling = false },
            onDismiss = { refuelling = false },
        )
    }
}

@Composable
private fun NoBike(onOpenGarage: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No bike yet", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.textDim)
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = onOpenGarage, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_card_open")) { Text("Open Garage") }
    }
}

/** The one value: big text, a small dim label, and its colour (red for low fuel, amber/red for a service). */
private data class BigValue(val text: String, val label: String, val color: Color?)

@Composable
private fun bigValue(state: GarageCardState, value: GarageValue): BigValue {
    val colors = AppTheme.colors
    return when (value) {
        GarageValue.RANGE -> {
            val range = state.fuel.level.rangeKm
            val low = (state.fuel.level.fraction ?: 1.0) <= LOW_FUEL_FRACTION
            BigValue(range?.let { "${it.roundToLong()} km" } ?: "—", "range left", if (low && range != null) colors.red else null)
        }
        GarageValue.SINCE_REFUEL -> BigValue(state.fuel.level.sinceRefuelKm?.let { "${it.roundToLong()} km" } ?: "—", "since fill-up", null)
        GarageValue.ODOMETER -> BigValue(state.odometer.estimateKm?.let(::formatOdometer) ?: "—", "odometer", null)
        GarageValue.KM_PER_L -> BigValue(
            String.format(Locale.US, "%.1f", state.fuel.consumption.kmPerL),
            if (state.fuel.consumption.learned) "km/L" else "km/L (starting value)",
            null,
        )
        GarageValue.NEXT_SERVICE -> {
            val next = state.nextService
            if (next == null) BigValue("—", "no service set", null) else BigValue(shortDue(next), next.item.name, levelColor(next.level))
        }
    }
}

@Composable
private fun levelColor(level: DueLevel): Color? = when (level) {
    DueLevel.OVERDUE -> AppTheme.colors.red
    DueLevel.SOON -> AppTheme.colors.amber
    else -> null
}

/** "120 km", "−50 km", "12 days", "today". */
private fun shortDue(d: ServiceDue): String = dueText(d)
    .removePrefix("due in ")
    .replace("overdue by ", "−")
    .replace("due today", "today")

@Composable
private fun SmallGarage(state: GarageCardState, value: GarageValue, mode: CardMode) {
    val big = bigValue(state, value)
    val wide = mode == CardMode.Wide
    Row(
        Modifier.wrapContentSize(unbounded = true).testTag("garage_card_small"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (value == GarageValue.RANGE) {
            TankGauge(state.fuel.level.fraction, Modifier.size(width = if (wide) 44.dp else 40.dp, height = if (wide) 80.dp else 72.dp))
            Spacer(Modifier.width(16.dp))
        }
        ValueText(big, if (wide) 56.sp else 48.sp)
    }
}

@Composable
private fun ValueText(big: BigValue, size: TextUnit) {
    Column {
        Text(
            big.text,
            style = TextStyle(fontFamily = Geist, fontSize = size, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = big.color ?: AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.testTag("garage_card_value"),
        )
        Text(big.label, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textDim, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ExpandedGarage(
    state: GarageCardState,
    value: GarageValue,
    viewModel: GarageCardViewModel,
    onRefuel: () -> Unit,
    onOpenGarage: () -> Unit,
) {
    val haptics = rememberHaptics()
    val bikes by viewModel.bikes.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).verticalScroll(rememberScrollState()).testTag("garage_card_full"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TankGauge(state.fuel.level.fraction, Modifier.size(width = 48.dp, height = 88.dp))
            Spacer(Modifier.width(16.dp))
            ValueText(bigValue(state, GarageValue.RANGE), 48.sp)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { haptics.tap(); onRefuel() }, modifier = Modifier.heightIn(min = 64.dp).testTag("garage_card_refuel")) {
                Icon(AppIcons.Fuel, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refuelled")
            }
        }
        FactRow(
            "Since fill-up" to (state.fuel.level.sinceRefuelKm?.let { "${it.roundToLong()} km" } ?: "—"),
            "Odometer" to (state.odometer.estimateKm?.let(::formatOdometer) ?: "Not set"),
            "km/L" to String.format(Locale.US, "%.1f", state.fuel.consumption.kmPerL),
        )
        state.nextService?.let { next ->
            Text(
                reminderText(next),
                style = MaterialTheme.typography.titleMedium,
                color = levelColor(next.level) ?: colors.text,
                modifier = Modifier.testTag("garage_card_service"),
            )
        }
        if (bikes.size >= 2) {
            Text("Riding", style = MaterialTheme.typography.labelLarge, color = colors.textDim)
            Segments(bikes, bikes.firstOrNull { it.id == state.bike.id } ?: bikes.first(), { it.name }, { "garage_card_bike_${it.name}" }) {
                haptics.confirm(); viewModel.ride(it.id)
            }
        }
        Text("The small card shows", style = MaterialTheme.typography.labelLarge, color = colors.textDim)
        ValueChoice(value) { haptics.tick(); viewModel.setValue(it) }
        FilledTonalButton(onClick = { haptics.tap(); onOpenGarage() }, modifier = Modifier.heightIn(min = 56.dp).testTag("garage_card_open")) {
            Text("Open Garage")
        }
    }
}

/** Five choices don't fit one segmented row on a half-width pane: two rows. */
@Composable
private fun ValueChoice(value: GarageValue, onPick: (GarageValue) -> Unit) {
    val all = GarageValue.entries
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(all.take(3), all.drop(3)).forEach { row ->
            // The row without the chosen value selects nothing.
            Segments<GarageValue?>(row, value.takeIf { it in row }, { it?.label ?: "" }, { "garage_value_${it?.name}" }) { it?.let(onPick) }
        }
    }
}

@Composable
private fun FactRow(vararg facts: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        facts.forEach { (label, text) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.textDim, maxLines = 1)
                Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** §6 "Low-fuel warning": a small red tank on the speed card when no Garage card is on screen. No text, no pop-up. */
@Composable
fun LowFuelIcon(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Icon(
        AppIcons.Fuel,
        contentDescription = "Low fuel",
        tint = AppTheme.colors.red,
        modifier = modifier.size(size).testTag("low_fuel"),
    )
}

/** The service reminder as a small pill: amber when due soon, red when overdue. */
@Composable
fun ServiceBadge(due: ServiceDue, modifier: Modifier = Modifier) {
    val color = if (due.level == DueLevel.OVERDUE) AppTheme.colors.red else AppTheme.colors.amber
    Text(
        reminderText(due),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.testTag("service_reminder"),
    )
}
