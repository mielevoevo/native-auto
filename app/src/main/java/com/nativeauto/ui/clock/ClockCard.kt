package com.nativeauto.ui.clock

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.core.WallClock
import com.nativeauto.settings.ClockFace
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.cardModeTransition
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.Geist
import com.nativeauto.ui.theme.appSegmentedColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.min

/**
 * Wall-clock time for the clock pane and the status-corner clock. [nowMs] ticks on each minute
 * boundary; [secondsMs] once a second, collected only while the analog face's second hand is on
 * screen. Ticks here rather than in composition, where the v2 Compose test dispatcher would
 * fast-forward the delay.
 */
@HiltViewModel
class ClockViewModel @Inject constructor(
    private val wall: WallClock,
    private val settings: SettingsRepository,
) : ViewModel() {
    val nowMs: StateFlow<Long> =
        ticker(MINUTE_MS).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), wall.nowMs())

    /** Stops as soon as nothing shows a second hand. */
    val secondsMs: StateFlow<Long> =
        ticker(SECOND_MS).stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), wall.nowMs())

    val face: StateFlow<ClockFace> =
        settings.clockFace.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockFace.DIGITAL)
    val secondHand: StateFlow<Boolean> =
        settings.clockSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setFace(face: ClockFace) {
        viewModelScope.launch { settings.setClockFace(face) }
    }

    fun setSecondHand(enabled: Boolean) {
        viewModelScope.launch { settings.setClockSeconds(enabled) }
    }

    /** Emits now, then just past each boundary of [periodMs]. */
    private fun ticker(periodMs: Long): Flow<Long> = flow {
        while (true) {
            val now = wall.nowMs()
            emit(now)
            delay(periodMs - Math.floorMod(now, periodMs) + 20)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SECOND_MS = 1_000L
    }
}

/**
 * The clock pane. Digital: big numerals, the date below in dim text. Analog (8c): an edge-tick
 * face (§9): no dial; the minute ticks run around the pane's own rounded edge, hour ticks longer
 * and brighter, thin off-white hands, and an optional cyan second hand (off by default: it
 * redraws every second). Both follow the phone's 12/24-hour setting for the text. The face is
 * chosen in the pane itself once it's expanded or fullscreen.
 */
@Composable
fun ClockCard(mode: CardMode, layout: DashboardLayoutState, viewModel: ClockViewModel = hiltViewModel()) {
    val now by viewModel.nowMs.collectAsStateWithLifecycle()
    val face by viewModel.face.collectAsStateWithLifecycle()
    val secondHand by viewModel.secondHand.collectAsStateWithLifecycle()
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val time = formatClockTime(now, zone, is24)
    val date = formatClockDate(now, zone, LocalLocale.current.platformLocale)
    // Every pane stays composed off screen: only tick each second while the hand is seen.
    val seconds: State<Long>? =
        if (face == ClockFace.ANALOG && secondHand && layout.isOnScreen(Pane.CLOCK)) {
            viewModel.secondsMs.collectAsStateWithLifecycle()
        } else {
            null
        }

    DashboardCard(title = "Clock", pane = Pane.CLOCK, mode = mode, layout = layout) {
        AnimatedContent(
            targetState = mode to face,
            transitionSpec = { cardModeTransition() },
            contentAlignment = Alignment.Center,
            label = "clockMode",
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
        ) { (m, f) ->
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (f == ClockFace.ANALOG) {
                        AnalogFace(
                            minuteNow = now,
                            seconds = seconds,
                            zone = zone,
                            description = time.spoken(),
                            date = date.takeIf { m == CardMode.Expanded || m == CardMode.Fullscreen },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DigitalFace(time, date, m)
                    }
                }
                if (m == CardMode.Expanded || m == CardMode.Fullscreen) {
                    FaceControls(face = f, secondHand = secondHand, onFace = viewModel::setFace, onSecondHand = viewModel::setSecondHand)
                }
            }
        }
    }
}

@Composable
private fun DigitalFace(time: ClockTime, date: String, mode: CardMode) {
    when (mode) {
        CardMode.Compact -> Stacked(time, date, 72.sp, 18.sp)
        CardMode.Wide -> Row(
            modifier = Modifier.wrapContentSize(unbounded = true),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Time(time, 88.sp)
            Spacer(Modifier.width(28.dp))
            Date(date, 24.sp)
        }
        CardMode.Expanded -> Stacked(time, date, 120.sp, 24.sp)
        CardMode.Fullscreen -> Stacked(time, date, 180.sp, 32.sp)
    }
}

@Composable
private fun Stacked(time: ClockTime, date: String, timeSize: TextUnit, dateSize: TextUnit) {
    Column(Modifier.wrapContentSize(unbounded = true), horizontalAlignment = Alignment.CenterHorizontally) {
        Time(time, timeSize)
        Spacer(Modifier.height(4.dp))
        Date(date, dateSize)
    }
}

@Composable
private fun Time(time: ClockTime, size: TextUnit) {
    Row(Modifier.testTag("clock_time"), verticalAlignment = Alignment.Bottom) {
        Text(
            time.digits,
            style = TextStyle(
                fontFamily = Geist,
                fontSize = size,
                lineHeight = size,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            color = AppTheme.colors.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alignByBaseline(),
        )
        time.suffix?.let {
            Spacer(Modifier.width(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                color = AppTheme.colors.textDim,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun Date(text: String, size: TextUnit) {
    Text(
        text,
        fontSize = size,
        color = AppTheme.colors.textDim,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.testTag("clock_date"),
    )
}

/**
 * The edge-tick face. Drawn from [minuteNow] (or the second tick, when the second hand shows)
 * inside the draw phase, so a second's tick redraws the canvas without recomposing the card.
 */
@Composable
private fun AnalogFace(
    minuteNow: Long,
    seconds: State<Long>?,
    zone: ZoneId,
    description: String,
    date: String?,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("clock_analog")
                .semantics { contentDescription = description },
        ) {
            drawEdgeTicks(colors.text, colors.textDim)
            val now = seconds?.value ?: minuteNow
            drawHands(now, zone, colors.text, colors.cyan.takeIf { seconds != null }, colors.card)
        }
        if (date != null) {
            Text(
                date,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textDim,
                maxLines = 1,
                modifier = Modifier.align(BiasAlignment(0f, 0.42f)).testTag("clock_date"),
            )
        }
    }
}

private class FaceFrame(val center: Offset, val halfW: Float, val halfH: Float, val radius: Float, val longTick: Float, val shortTick: Float)

private fun DrawScope.frame(): FaceFrame {
    val pad = 10.dp.toPx()
    val halfW = size.width / 2 - pad
    val halfH = size.height / 2 - pad
    val long = (min(halfW, halfH) * 0.12f).coerceIn(10.dp.toPx(), 22.dp.toPx())
    // The card's corner (20 dp) less the inset, so the ticks follow its curve.
    return FaceFrame(Offset(size.width / 2, size.height / 2), halfW, halfH, 12.dp.toPx(), long, long * 0.45f)
}

private fun DrawScope.drawEdgeTicks(hourColor: androidx.compose.ui.graphics.Color, minuteColor: androidx.compose.ui.graphics.Color) {
    val f = frame()
    if (f.halfW <= 0f || f.halfH <= 0f) return
    for (i in 0 until 60) {
        val hit = edgeAt(i / 60f, f.halfW, f.halfH, f.radius)
        val hour = i % 5 == 0
        val outer = f.center + hit.point
        val inner = outer - hit.normal * (if (hour) f.longTick else f.shortTick)
        drawLine(
            color = if (hour) hourColor else minuteColor.copy(alpha = 0.65f),
            start = outer,
            end = inner,
            strokeWidth = (if (hour) 3.dp else 1.5.dp).toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawHands(
    nowMs: Long,
    zone: ZoneId,
    handColor: androidx.compose.ui.graphics.Color,
    secondColor: androidx.compose.ui.graphics.Color?,
    hub: androidx.compose.ui.graphics.Color,
) {
    val f = frame()
    if (f.halfW <= 0f || f.halfH <= 0f) return
    val t = Instant.ofEpochMilli(nowMs).atZone(zone)
    val second = t.second.toFloat()
    val minute = t.minute + if (secondColor != null) second / 60f else 0f
    val hour = t.hour % 12 + minute / 60f
    val hand = handTurns(hour, minute, second)

    // Each hand points at its own spot on the edge (where its tick is), so the minute hand
    // reaches nearly to its tick on any shape of pane; the hour hand is short and fixed, so the
    // two never read alike on a wide pane.
    fun edge(turn: Float) = edgeAt(turn, f.halfW, f.halfH, f.radius).point
    fun direction(turn: Float) = edge(turn).let { it / it.getDistance() }
    fun tip(turn: Float, length: Float) = f.center + direction(turn) * length
    fun reach(turn: Float, inset: Float) = edge(turn).getDistance() - inset

    val hourLength = min(f.halfW, f.halfH) * 0.5f
    drawLine(handColor, f.center, tip(hand.hour, hourLength), 6.dp.toPx(), StrokeCap.Round)
    drawLine(handColor, f.center, tip(hand.minute, reach(hand.minute, f.longTick + 8.dp.toPx())), 3.5.dp.toPx(), StrokeCap.Round)
    if (secondColor != null) {
        val tail = f.center - direction(hand.second) * 14.dp.toPx()
        drawLine(secondColor, tail, tip(hand.second, reach(hand.second, f.shortTick + 4.dp.toPx())), 1.5.dp.toPx(), StrokeCap.Round)
    }
    drawCircle(secondColor ?: handColor, 6.dp.toPx(), f.center)
    drawCircle(hub, 2.dp.toPx(), f.center)
}

/**
 * Where each hand points, as a fraction of a turn clockwise from 12 (0 until 1), for hour 0–12
 * and minute/second 0–60 (fractions allowed). [edgeAt] turns a fraction into its edge point.
 */
internal data class HandTurns(val hour: Float, val minute: Float, val second: Float)

internal fun handTurns(hour: Float, minute: Float, second: Float) =
    HandTurns(hour = (hour % 12f) / 12f, minute = (minute % 60f) / 60f, second = (second % 60f) / 60f)

/** Digital / Analog, and the analog face's second hand. Big targets, shown when the pane is large. */
@Composable
private fun FaceControls(face: ClockFace, secondHand: Boolean, onFace: (ClockFace) -> Unit, onSecondHand: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow {
            ClockFace.entries.forEachIndexed { i, option ->
                SegmentedButton(
                    selected = option == face,
                    onClick = { if (option != face) { haptics.tick(); onFace(option) } },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = ClockFace.entries.size),
                    colors = appSegmentedColors(),
                    modifier = Modifier.heightIn(min = 56.dp).testTag("clock_face_${option.name}"),
                ) { Text(option.label, maxLines = 1) }
            }
        }
        if (face == ClockFace.ANALOG) {
            Surface(
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .toggleable(value = secondHand, role = Role.Switch) { haptics.toggle(it); onSecondHand(it) }
                    .testTag("clock_seconds"),
                shape = RoundedCornerShape(28.dp),
                color = if (secondHand) colors.navy else colors.surface,
                contentColor = if (secondHand) colors.onNavy else colors.text,
                border = BorderStroke(1.dp, colors.outline),
            ) {
                Box(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Second hand", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/** The numerals, and "AM"/"PM" on a 12-hour phone. */
internal data class ClockTime(val digits: String, val suffix: String?) {
    fun spoken() = if (suffix == null) digits else "$digits $suffix"
}

internal fun formatClockTime(nowMs: Long, zone: ZoneId, is24: Boolean): ClockTime {
    val t = Instant.ofEpochMilli(nowMs).atZone(zone)
    return if (is24) {
        ClockTime(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(t), null)
    } else {
        ClockTime(DateTimeFormatter.ofPattern("h:mm", Locale.ROOT).format(t), if (t.hour < 12) "AM" else "PM")
    }
}

/** "Sat 8 Nov": short enough for a quarter pane. */
internal fun formatClockDate(nowMs: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofPattern("EEE d MMM", locale).format(Instant.ofEpochMilli(nowMs).atZone(zone))
