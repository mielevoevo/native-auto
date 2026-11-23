package com.nativeauto.ui.greeting

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
 * 4.3c: the daily greeting, a bottom sheet on the first open of the day. With a bike: the tank and the cautious range
 * (the biggest thing on it), then the odometer, this week's riding and a service that is due. Without one: the greeting
 * and a way into the Garage. A tap anywhere, a swipe down or Done closes it. [GreetingViewModel] decides when it shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreetingSheet(onOpenGarage: () -> Unit, viewModel: GreetingViewModel = hiltViewModel()) {
    // Each time the app comes to the foreground: the first one of the day greets.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.onOpened() } }

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
                TankGauge(level.fraction, Modifier.size(width = 40.dp, height = 72.dp))
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        range?.let { "$it km" } ?: "—",
                        style = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, fontFeatureSettings = "tnum"),
                        color = if (range != null && (level.fraction ?: 1.0) <= com.nativeauto.ui.garage.LOW_FUEL_FRACTION) colors.red else colors.text,
                        modifier = Modifier.testTag("greeting_range"),
                    )
                    Text(
                        when {
                            range == null && level.unknownBecause == UnknownFuel.AWAY -> "Fill up or set the fuel level"
                            range == null -> "Set the fuel level in the Garage"
                            else -> "range left"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textDim,
                    )
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

@Composable
private fun Fact(label: String, value: String, tag: String) {
    Column(Modifier.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.textDim)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Color.Unspecified)
    }
}
