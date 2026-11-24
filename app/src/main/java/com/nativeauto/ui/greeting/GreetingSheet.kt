package com.nativeauto.ui.greeting

import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextOverflow
import com.nativeauto.garage.FuelLevel
import com.nativeauto.ui.components.LocalPortrait
import com.nativeauto.ui.theme.PortraitSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nativeauto.garage.DueLevel
import com.nativeauto.garage.UnknownFuel
import com.nativeauto.garage.formatOdometer
import com.nativeauto.garage.reminderText
import com.nativeauto.greeting.GreetingUi
import com.nativeauto.greeting.GreetingViewModel
import com.nativeauto.ui.components.sheetTag
import com.nativeauto.ui.garage.TankGauge
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.Geist
import java.util.Locale

/**
 * 4.3c: the greeting, a bottom sheet on an app open. With a bike: the tank and the cautious range
 * (the biggest thing on it), then the odometer, this week's riding and a service that is due. Without one: the greeting
 * and a way into the Garage. A tap anywhere, a swipe down or Done closes it. [GreetingViewModel] decides when it shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreetingSheet(onOpenGarage: () -> Unit, viewModel: GreetingViewModel = hiltViewModel()) {
    // Each time the app comes to the foreground; the setting says which of them greet.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.onOpened()
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                viewModel.onLeft()
            }
        }
    }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val state = ui ?: return
    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 900.dp,
        dragHandle = null,
        modifier = Modifier.sheetTag("greeting_sheet"),
    ) {
        GreetingContent(
            state,
            onDone = viewModel::dismiss,
            onOpenGarage = { viewModel.dismiss(); onOpenGarage() },
        )
    }
}

@Composable
internal fun GreetingContent(state: GreetingUi, onDone: () -> Unit, onOpenGarage: () -> Unit) {
    if (LocalPortrait.current) {
        PortraitGreeting(state, onDone, onOpenGarage)
        return
    }
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Close", onClick = onDone)
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("greeting_title"))
                state.card?.let { Text(it.bike.name, style = MaterialTheme.typography.bodyLarge, color = colors.textDim, modifier = Modifier.testTag("greeting_bike")) }
            }
            FilledTonalButton(onClick = onDone, modifier = Modifier.heightIn(min = 56.dp).testTag("greeting_done")) { Text("Done") }
        }
        val card = state.card
        if (card == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Add your bike to see its range here",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textDim,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                FilledTonalButton(onClick = onOpenGarage, modifier = Modifier.heightIn(min = 56.dp).testTag("greeting_add_bike")) { Text("Open Garage") }
            }
            return@Column
        }
        val level = card.fuel.level
        val range = level.cautiousRangeKm
        val rangeBlock = @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TankGauge(level.fraction, Modifier.size(width = 40.dp, height = 72.dp), low = level.low)
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        range?.let { "$it km" } ?: "—",
                        style = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, fontFeatureSettings = "tnum"),
                        color = if (level.low) colors.red else colors.text,
                        modifier = Modifier.testTag("greeting_range"),
                    )
                    Text(rangeLine(level), style = MaterialTheme.typography.bodyLarge, color = colors.textDim)
                }
            }
        }
        val facts = @Composable {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Fact("Odometer", card.odometer.estimateKm?.let(::formatOdometer) ?: "Not set", "greeting_odometer")
                Fact("This week", String.format(Locale.US, "%.1f km", state.weekM / 1000.0), "greeting_week")
            }
        }
        // Wide (landscape): the figures beside the range, so the sheet stays short; narrow: under it.
        BoxWithConstraints {
            if (maxWidth >= 600.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { rangeBlock() }
                    facts()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { rangeBlock(); facts() }
            }
        }
        state.serviceDue?.let { due ->
            Text(
                reminderText(due),
                style = MaterialTheme.typography.titleMedium,
                color = if (due.level == DueLevel.OVERDUE) colors.red else colors.amber,
                modifier = Modifier.testTag("greeting_service"),
            )
        }
    }
}

private fun rangeLine(level: FuelLevel): String = when {
    level.cautiousRangeKm != null -> "range left"
    level.unknownBecause == UnknownFuel.AWAY -> "Fill up or set the fuel level"
    else -> "Set the fuel level in the Garage"
}

/** 4.6.4: portrait: the range large, the two figures in equal halves, the buttons across the bottom, where the thumb is. */
@Composable
private fun PortraitGreeting(state: GreetingUi, onDone: () -> Unit, onOpenGarage: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Close", onClick = onDone)
            .padding(horizontal = 24.dp)
            .padding(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val card = state.card
        Column {
            Text(state.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("greeting_title"))
            card?.let {
                Text(
                    it.bike.name,
                    fontSize = PortraitSheet.label,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("greeting_bike"),
                )
            }
        }
        if (card == null) {
            Text("Add your bike to see its range here", fontSize = PortraitSheet.label, lineHeight = PortraitSheet.label * 1.3f, color = colors.textDim)
        } else {
            val level = card.fuel.level
            Row(verticalAlignment = Alignment.CenterVertically) {
                TankGauge(level.fraction, Modifier.size(PortraitSheet.gaugeWidth, PortraitSheet.gaugeHeight), low = level.low)
                Spacer(Modifier.width(24.dp))
                Column {
                    // No tabular digits: the value doesn't tick, and a tabular 1 would stand off the line under it.
                    Text(
                        level.cautiousRangeKm?.let { "$it km" } ?: "—",
                        style = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = PortraitSheet.value, lineHeight = PortraitSheet.value),
                        color = if (level.low) colors.red else colors.text,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag("greeting_range"),
                    )
                    Text(rangeLine(level), fontSize = PortraitSheet.label, color = colors.textDim)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PortraitFact("Odometer", card.odometer.estimateKm?.let(::formatOdometer) ?: "Not set", "greeting_odometer", Modifier.weight(1f))
                PortraitFact("This week", String.format(Locale.US, "%.1f km", state.weekM / 1000.0), "greeting_week", Modifier.weight(1f))
            }
            state.serviceDue?.let { due ->
                Text(
                    reminderText(due),
                    fontSize = PortraitSheet.label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (due.level == DueLevel.OVERDUE) colors.red else colors.amber,
                    modifier = Modifier.testTag("greeting_service"),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card == null) {
                Button(onClick = onOpenGarage, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("greeting_add_bike")) { ButtonText("Open Garage") }
            }
            FilledTonalButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("greeting_done")) { ButtonText("Done") }
        }
    }
}

@Composable
private fun ButtonText(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun PortraitFact(label: String, value: String, tag: String, modifier: Modifier) {
    Column(modifier.testTag(tag)) {
        Text(label, fontSize = PortraitSheet.label, color = AppTheme.colors.textDim)
        Text(value, fontSize = PortraitSheet.figure, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Fact(label: String, value: String, tag: String) {
    Column(Modifier.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.textDim)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Color.Unspecified)
    }
}
