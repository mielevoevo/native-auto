package com.nativeauto.ui.history

import androidx.core.graphics.toColorInt
import com.nativeauto.ui.map.MapLook
import com.nativeauto.ui.theme.AppTheme
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.history.Polyline
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripMaps
import com.nativeauto.history.TripRecord
import com.nativeauto.history.TripRecorder
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
    private val dao: TripDao,
    val maps: TripMaps,
    recorder: TripRecorder,
    settings: SettingsRepository,
) : ViewModel() {

    /** Null while loading, so the empty state doesn't flash before the first read. */
    val trips: StateFlow<List<TripRecord>?> = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unit: StateFlow<SpeedUnit> = settings.speedUnit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUnit.KMH)

    private val _deleteArmed = MutableStateFlow<Long?>(null)
    val deleteArmed: StateFlow<Long?> = _deleteArmed.asStateFlow()
    private var disarm: Job? = null

    init {
        // Trips saved offline have no addresses yet.
        recorder.fillMissingAddresses()
    }

    /** First tap arms, a second within 3 s deletes. Returns true when it deleted. */
    fun onDeleteTapped(id: Long): Boolean {
        disarm?.cancel()
        if (_deleteArmed.value == id) {
            _deleteArmed.value = null
            viewModelScope.launch {
                dao.delete(id)
                maps.forget(id)
            }
            return true
        }
        _deleteArmed.value = id
        disarm = viewModelScope.launch {
            delay(3_000)
            _deleteArmed.value = null
        }
        return false
    }
}

/**
 * Every saved trip, newest first: overview map, start → end address, and the trip's figures.
 * Tapping one opens it large. Drawn over the dashboard, which stays composed underneath
 * (rule #3: the live map is never recreated).
 */
@Composable
fun TripHistoryScreen(onClose: () -> Unit, viewModel: TripHistoryViewModel = hiltViewModel()) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val armed by viewModel.deleteArmed.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = trips?.firstOrNull { it.id == selectedId }
    val haptics = rememberHaptics()

    BackHandler { if (selected != null) selectedId = null else onClose() }

    Surface(Modifier.fillMaxSize().testTag("history_screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { haptics.tap(); if (selected != null) selectedId = null else onClose() },
                    modifier = Modifier.size(56.dp).testTag("history_back"),
                ) {
                    Icon(AppIcons.ArrowBack, contentDescription = "Back")
                }
                Text(
                    if (selected != null) formatDay(selected.startedAtMs) else "Trip history",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (selected == null) {
                    trips?.let {
                        Text("${it.size} ${if (it.size == 1) "trip" else "trips"}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    selected != null -> TripDetail(
                        trip = selected,
                        unit = unit,
                        maps = viewModel.maps,
                        deleteArmed = armed == selected.id,
                        onDelete = {
                            if (viewModel.onDeleteTapped(selected.id)) {
                                haptics.confirm()
                                selectedId = null
                            } else {
                                haptics.tap()
                            }
                        },
                    )
                    trips == null -> Unit
                    trips!!.isEmpty() -> EmptyHistory()
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 420.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize().testTag("history_list"),
                    ) {
                        items(trips!!, key = { it.id }) { trip ->
                            TripCard(trip, unit, viewModel.maps) {
                                haptics.tap()
                                selectedId = trip.id
                            }
                        }
                    }
                }
            }
            // OpenFreeMap / OpenStreetMap terms: the overview maps need the credit.
            Text(
                "Maps © OpenStreetMap contributors · OpenFreeMap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("No trips yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "A trip is saved when you end it or reset it (trips under 100 m are skipped).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TripCard(trip: TripRecord, unit: SpeedUnit, maps: TripMaps, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).testTag("history_trip"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TripMapImage(trip, maps, width = 150.dp, height = 118.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatDay(trip.startedAtMs)} · ${formatTime(trip.startedAtMs)}–${formatTime(trip.endedAtMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Endpoints(trip, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MiniStat(formatDistance(trip.distanceM, unit), "distance")
                    MiniStat(formatDuration(trip.durationMs), "time")
                    MiniStat(formatSpeed(trip.averageSpeedMps, unit), "avg")
                    MiniStat(formatSpeed(trip.maxSpeedMps, unit), "max")
                }
            }
        }
    }
}

@Composable
private fun TripDetail(trip: TripRecord, unit: SpeedUnit, maps: TripMaps, deleteArmed: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TripMapImage(trip, maps, width = 520.dp, height = 300.dp, modifier = Modifier.weight(1.3f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text(
                "${formatTime(trip.startedAtMs)} – ${formatTime(trip.endedAtMs)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Endpoints(trip, maxLines = 2, large = true)
            Spacer(Modifier.height(16.dp))
            val stats = listOf(
                "Distance" to formatDistance(trip.distanceM, unit),
                "Trip time" to formatDuration(trip.durationMs),
                "Avg speed" to formatSpeed(trip.averageSpeedMps, unit),
                "Max speed" to formatSpeed(trip.maxSpeedMps, unit),
                "Moving avg" to formatSpeed(trip.movingAverageMps, unit),
                "Moving time" to formatDuration(trip.movingMs),
            )
            stats.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = 56.dp).testTag("history_delete"),
                colors = if (deleteArmed) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(AppIcons.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (deleteArmed) "Tap again to delete" else "Delete trip")
            }
        }
    }
}

/** Start and end, with the same dots as on the map. */
@Composable
private fun Endpoints(trip: TripRecord, maxLines: Int, large: Boolean = false) {
    val style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    val look = MapLook.of(AppTheme.colors.isDark)
    val startDot = Color(look.start.toColorInt())
    val endDot = Color(look.destination.toColorInt())
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            Triple(startDot, trip.startAddress, formatCoords(trip.startLat, trip.startLon)),
            Triple(endDot, trip.endAddress, formatCoords(trip.endLat, trip.endLon)),
        ).forEachIndexed { i, (color, address, coords) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(if (i == 0) "history_start" else "history_end")) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(address ?: coords, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The overview map. Until the snapshot is ready (or when it can't be made, e.g. offline the
 * first time) the path itself is drawn on a plain background, so there's always a shape.
 */
@Composable
private fun TripMapImage(trip: TripRecord, maps: TripMaps, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val dark = AppTheme.colors.isDark
    val bitmap by produceState<Bitmap?>(null, trip.id, width, height, dark) {
        value = maps.overview(trip, width.value.roundToInt(), height.value.roundToInt(), dark)
    }
    val shape = RoundedCornerShape(14.dp)
    val sized = if (modifier == Modifier) Modifier.size(width, height) else modifier
    Box(sized.clip(shape).background(AppTheme.colors.buttonQuiet).testTag("history_map")) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = "Route map", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            TrackSketch(trip, Modifier.fillMaxSize().padding(12.dp))
        }
    }
}

@Composable
private fun TrackSketch(trip: TripRecord, modifier: Modifier) {
    val points = Polyline.decode(trip.track)
    val look = MapLook.of(AppTheme.colors.isDark)
    val route = Color(look.route.toColorInt())
    val start = Color(look.start.toColorInt())
    val end = Color(look.destination.toColorInt())
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val lonScale = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-6)
        val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf(size.width / spanX, size.height / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun at(i: Int) = Offset(
            (offX + (points[i].longitude - minLon) * lonScale * scale).toFloat(),
            (offY + (maxLat - points[i].latitude) * scale).toFloat(),
        )
        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until points.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, route, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(start, 5.dp.toPx(), at(0))
        drawCircle(end, 5.dp.toPx(), at(points.lastIndex))
    }
}


internal fun formatDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(ms).atZone(zone))

internal fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

internal fun formatDistance(meters: Double, unit: SpeedUnit): String =
    String.format(Locale.ROOT, "%.1f %s", meters / unit.metersPerDistanceUnit, unit.distanceLabel)

internal fun formatSpeed(mps: Float, unit: SpeedUnit): String = "${(mps * unit.perMps).roundToInt()} ${unit.speedLabel}"

/** "42 min", "1 h 05 min"; under a minute, seconds. */
internal fun formatDuration(ms: Long): String {
    val d = ms.coerceAtLeast(0).milliseconds
    val minutes = d.inWholeMinutes
    return when {
        minutes < 1 -> "${d.inWholeSeconds} s"
        minutes < 60 -> "$minutes min"
        else -> String.format(Locale.ROOT, "%d h %02d min", minutes / 60, minutes % 60)
    }
}

private fun formatCoords(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.4f, %.4f", lat, lon)
