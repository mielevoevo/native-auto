package com.nativeauto.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.material3.ButtonDefaults
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.Place
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.cardModeTransition
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NextTurnViewModel @Inject constructor(
    private val navigator: Navigator,
    settings: SettingsRepository,
) : ViewModel() {
    val nav: StateFlow<NavState> = navigator.state
    val unit: StateFlow<SpeedUnit> =
        settings.speedUnit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUnit.KMH)

    fun routeTo(place: Place) = navigator.routeTo(place)
    fun start() = navigator.start()
    fun retry() = navigator.retry()
    fun stop() = navigator.stop()
}

/**
 * The Navigation pane (Phase 8b; "Next turn" until 13a): navigation readable without the map. While guiding: a big
 * arrow, the distance to the turn, the road, and the arrival time. Idle, it's a big
 * "Where to?" button (the same search as the map's). Planning, the route preview and
 * failures show the same cards as on the map, so it works in a layout without one too.
 */
@Composable
fun NextTurnCard(mode: CardMode, layout: DashboardLayoutState, viewModel: NextTurnViewModel = hiltViewModel()) {
    val nav by viewModel.nav.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }

    DashboardCard(title = "Navigation", pane = Pane.NAV, mode = mode, layout = layout) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = nav) {
                NavState.Idle -> WhereTo(onClick = { searching = true })
                is NavState.Routing, is NavState.Failed, is NavState.Arrived ->
                    NavStatusCard(s, onRetry = viewModel::retry, onDismiss = viewModel::stop, modifier = Modifier.widthIn(max = 560.dp))
                is NavState.Preview ->
                    RoutePreviewCard(s, unit, onStart = viewModel::start, onCancel = viewModel::stop, modifier = Modifier.widthIn(max = 560.dp))
                is NavState.Navigating -> AnimatedContent(
                    targetState = mode,
                    transitionSpec = { cardModeTransition() },
                    contentAlignment = Alignment.Center,
                    label = "nextTurnMode",
                    modifier = Modifier.fillMaxSize(),
                ) { m -> TurnFace(s, unit, m, onEnd = viewModel::stop) }
            }
        }
    }

    if (searching) {
        DestinationSearchSheet(
            onPick = { place ->
                searching = false
                viewModel.routeTo(place)
            },
            onDismiss = { searching = false },
        )
    }
}

/** One big target: with gloves on, at a stop. */
@Composable
private fun WhereTo(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FilledTonalButton(
        onClick = { haptics.tap(); onClick() },
        modifier = Modifier.heightIn(min = 64.dp).widthIn(min = 200.dp).testTag("nav_where_to"),
    ) {
        Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text("Where to?", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TurnFace(state: NavState.Navigating, unit: SpeedUnit, mode: CardMode, onEnd: () -> Unit) {
    if (state.rerouting || (state.offRoute && state.instruction == null)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 4.dp)
            Spacer(Modifier.width(16.dp))
            Text("Rerouting…", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    val distance = formatNavDistance(state.distanceToManeuverM, unit)
    val road = state.instruction?.bannerText() ?: "Follow the route"
    val eta = "${formatEta(System.currentTimeMillis(), state.durationRemainingS)} · " +
        "${formatNavDuration(state.durationRemainingS)} · ${formatNavDistance(state.distanceRemainingM, unit)}"
    val color = MaterialTheme.colorScheme.onSurface

    when (mode) {
        CardMode.Compact, CardMode.Wide -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            // 13a: the arrow is centred on the distance + road block, and the arrival line starts
            // at that block's left edge (not the card's), so all the text shares one edge.
            val glyphSize = if (mode == CardMode.Wide) 84.dp else 72.dp
            val glyphGap = 14.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.instruction?.let { ManeuverGlyph(it, color, Modifier.size(glyphSize)) }
                Spacer(Modifier.width(glyphGap))
                Column(Modifier.weight(1f)) {
                    DistanceText(distance, if (mode == CardMode.Wide) 48.sp else 40.sp)
                    RoadText(road, MaterialTheme.typography.titleMedium.fontSize, maxLines = if (mode == CardMode.Wide) 1 else 2)
                }
                if (mode == CardMode.Wide) {
                    Spacer(Modifier.width(16.dp))
                    EtaText(eta, MaterialTheme.typography.titleLarge.fontSize, TextAlign.End)
                }
            }
            if (mode == CardMode.Compact) {
                Spacer(Modifier.height(6.dp))
                val indent = if (state.instruction != null) glyphSize + glyphGap else 0.dp
                EtaText(eta, MaterialTheme.typography.titleMedium.fontSize, TextAlign.Start, Modifier.padding(start = indent))
            }
        }
        CardMode.Expanded, CardMode.Fullscreen -> {
            val big = mode == CardMode.Fullscreen
            Column(
                modifier = Modifier.fillMaxSize().wrapContentSize(unbounded = true),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                state.instruction?.let { ManeuverGlyph(it, color, Modifier.size(if (big) 180.dp else 132.dp)) }
                DistanceText(distance, if (big) 88.sp else 64.sp)
                RoadText(road, if (big) 32.sp else 24.sp, maxLines = 2, align = TextAlign.Center)
                state.nextInstruction?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Then ${it.bannerText()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                EtaText(eta, if (big) 28.sp else 22.sp, TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                EndButton(onEnd, height = 56.dp)
            }
        }
    }
}

@Composable
private fun DistanceText(text: String, size: TextUnit) = Text(
    text,
    fontSize = size,
    lineHeight = size,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    softWrap = false,
    modifier = Modifier.testTag("next_turn_distance"),
)

@Composable
private fun RoadText(text: String, size: TextUnit, maxLines: Int, align: TextAlign = TextAlign.Start) = Text(
    text,
    fontSize = size,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    textAlign = align,
    modifier = Modifier.testTag("next_turn_road"),
)

@Composable
private fun EtaText(text: String, size: TextUnit, align: TextAlign, modifier: Modifier = Modifier) = Text(
    text,
    fontSize = size,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    textAlign = align,
    modifier = modifier.testTag("next_turn_eta"),
)

@Composable
private fun EndButton(onEnd: () -> Unit, height: Dp) {
    val haptics = rememberHaptics()
    FilledTonalButton(
        onClick = { haptics.confirm(); onEnd() },
        modifier = Modifier.heightIn(min = height).widthIn(min = 160.dp).testTag("next_turn_end"),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Icon(AppIcons.Close, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("End navigation", style = MaterialTheme.typography.titleMedium)
    }
}
