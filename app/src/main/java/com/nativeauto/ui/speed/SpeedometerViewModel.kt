package com.nativeauto.ui.speed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.core.MonotonicClock
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripStats
import com.nativeauto.trip.TripReducer
import com.nativeauto.trip.TripTracker
import com.nativeauto.trip.SpeedSample
import com.nativeauto.location.LiveLocation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import com.nativeauto.trip.averageSpeedMps
import com.nativeauto.trip.elapsedNanos
import com.nativeauto.trip.movingAverageMps
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Observes [TripTracker] (never drives it, rule #10) and turns SI stats into display units.
 * This is the only place that converts m/s and metres into km/h, mph, km or mi (rule #7).
 *
 * Phase 9a: with no trip running (idle, paused, ended) the hero still shows the live speed,
 * read from [LiveLocation] (the same GPS read the map uses), so the speedometer works without
 * a trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SpeedometerViewModel @Inject constructor(
    tracker: TripTracker,
    private val clock: MonotonicClock,
    settings: SettingsRepository,
    permission: LocationPermissionState,
    live: LiveLocation,
) : ViewModel() {

    private val liveWhenStopped: Flow<SpeedSample?> = tracker.state
        .map { it == TripState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (running) flowOf(null) else live.fixes.map<SpeedSample, SpeedSample?> { it }.onStart { emit(null) }
        }

    val ui: StateFlow<SpeedometerUiState> =
        combine(
            combine(tracker.stats, liveWhenStopped, ::Pair),
            tripSecondTicks(tracker, clock),
            settings.speedUnit,
            tracker.state,
            permission.granted,
        ) { (stats, liveFix), _, unit, trip, granted ->
            stats.toUiState(nowNanos = clock.nowNanos(), unit = unit, tripState = trip, locationGranted = granted, liveFix = liveFix)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedometerUiState.Empty)

    companion object {
        /** A fix older than this means the GPS has gone quiet: show "--" (§6.4 stale rule). */
        const val STALE_AFTER_NANOS = 3_000_000_000L
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Fires once a second, aligned to whole seconds of trip time so the elapsed counter never
 * skips or repeats a digit. It only *triggers* recomputation: the value is read from the
 * clock at mapping time, so updates from new fixes use an equally fresh "now". Shared by the
 * card and the trip notification.
 */
internal fun tripSecondTicks(tracker: TripTracker, clock: MonotonicClock): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        // Trip time, not wall time since the start: paused spans shift the second boundary.
        val intoSecond = Math.floorMod(tracker.stats.value.elapsedNanos(clock.nowNanos()), NANOS_PER_SECOND)
        delay((NANOS_PER_SECOND - intoSecond) / NANOS_PER_MILLI + 1)
    }
}

/**
 * The one SI → display conversion (rule #7). "Can't track" states outrank the stale rule:
 * with access denied or location off, the tile names the cause instead of just "Lost".
 */
internal fun TripStats.toUiState(
    nowNanos: Long,
    unit: SpeedUnit,
    tripState: TripState,
    locationGranted: Boolean = true,
    liveFix: SpeedSample? = null,
): SpeedometerUiState {
    val last = lastSample
    val stale = last != null && nowNanos - last.timestampNanos > SpeedometerViewModel.STALE_AFTER_NANOS
    val liveFresh = liveFix != null && nowNanos - liveFix.timestampNanos <= SpeedometerViewModel.STALE_AFTER_NANOS
    val gps = when {
        !locationGranted -> GpsStatus.PermissionDenied
        tripState != TripState.Running -> when {
            // No trip: the tile follows the map's GPS read.
            liveFix == null -> if (gpsStatus == GpsStatus.Disabled) gpsStatus else GpsStatus.Searching
            liveFresh -> GpsStatus.Fix
            else -> GpsStatus.Stale
        }
        gpsStatus == GpsStatus.Disabled || gpsStatus == GpsStatus.PermissionDenied -> gpsStatus
        stale -> GpsStatus.Stale
        else -> gpsStatus
    }
    val blind = gps == GpsStatus.Disabled || gps == GpsStatus.PermissionDenied
    // Running: the trip's own speed. Otherwise the figures are frozen (or zero) and the hero shows
    // the live fix the map reads, when there is a fresh one with a speed.
    val speedMps = if (tripState == TripState.Running) {
        if (last == null || stale) null else currentSpeedMps
    } else {
        liveFix?.takeIf { liveFresh && it.hasSpeed }
            ?.let { if (it.speedMps < TripReducer.STATIONARY_MPS) 0f else it.speedMps }
    }
    return SpeedometerUiState(
        speed = if (blind) null else speedMps?.toUnit(unit),
        maxSpeed = maxSpeedMps.toUnit(unit),
        avgSpeed = averageSpeedMps(nowNanos).toUnit(unit),
        movingAvgSpeed = movingAverageMps().toUnit(unit),
        // Whole seconds, as displayed: ticks within the same second yield an equal state,
        // which StateFlow drops, so the card doesn't recompose for nothing.
        elapsed = (elapsedNanos(nowNanos).coerceAtLeast(0L) / 1_000_000_000L).seconds,
        movingTime = (movingNanos / 1_000_000_000L).seconds,
        stoppedTime = ((elapsedNanos(nowNanos) - movingNanos).coerceAtLeast(0L) / 1_000_000_000L).seconds,
        distance = distanceM / unit.metersPerDistanceUnit,
        unit = unit,
        gps = gps,
        tripState = tripState,
        autoPaused = tripState == TripState.Paused && autoPaused,
    )
}

private fun Float.toUnit(unit: SpeedUnit): Int = (this * unit.perMps).roundToInt()

