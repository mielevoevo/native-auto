package com.nativeauto.trip

import com.nativeauto.core.MonotonicClock
import com.nativeauto.di.ApplicationScope
import com.nativeauto.location.LocationDisabledException
import com.nativeauto.location.LocationPermissionException
import com.nativeauto.location.SpeedSampleSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide trip state (rule #10). Collects [SpeedSampleSource] in the application scope,
 * so tracking outlives any screen. TripService drives the life cycle ([start], [pause],
 * [resume], [end], [discard]); ViewModels only observe [stats] and [state].
 *
 * Phase 9a: a trip starts only when the rider presses Start (no auto-start on app open), and
 * Reset means [discard]: the trip is dropped without saving and the tracker goes back to Idle.
 *
 * Source failures never escape (the scope has no exception handler, so one would crash
 * the app). Instead they become a [GpsStatus]:
 * - [LocationDisabledException] → [GpsStatus.Disabled]; retried every [RETRY_DISABLED_MS]
 *   until location is switched back on.
 * - [LocationPermissionException] → [GpsStatus.PermissionDenied]; the trip ends, since it
 *   cannot recover until the user grants access (which starts a new one).
 * - anything else (e.g. Play services hiccups) → retried after [RETRY_ERROR_MS].
 */
@Singleton
class TripTracker @Inject constructor(
    private val source: SpeedSampleSource,
    private val clock: MonotonicClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _stats = MutableStateFlow(TripStats())
    val stats: StateFlow<TripStats> = _stats.asStateFlow()

    private val _state = MutableStateFlow(TripState.Idle)
    val state: StateFlow<TripState> = _state.asStateFlow()

    /**
     * The newest fix as received, before the reducer's filtering (the map shows where the
     * car is even when a reading is too inaccurate for the trip figures). Only updates while
     * running.
     */
    private val _lastFix = MutableStateFlow<SpeedSample?>(null)
    val lastFix: StateFlow<SpeedSample?> = _lastFix.asStateFlow()

    private var job: Job? = null

    /**
     * Idempotent: called whenever the service starts, i.e. each time the app comes to the
     * foreground. Running → no-op. Paused → stays paused (only [resume] continues).
     * Ended → a fresh trip. Idle → the trip clock starts now.
     */
    fun start() {
        when (_state.value) {
            TripState.Running, TripState.Paused -> return
            TripState.Ended -> _stats.value = TripStats()
            TripState.Idle -> Unit
        }
        _stats.update {
            it.copy(
                startedAtNanos = it.startedAtNanos ?: clock.nowNanos(),
                // A previous denial is stale once tracking is (re)started with access.
                gpsStatus = if (it.gpsStatus == GpsStatus.PermissionDenied) GpsStatus.Searching else it.gpsStatus,
            )
        }
        collect()
    }

    /**
     * Stops the GPS and freezes the trip clock. No-op unless running. [auto] marks a pause by
     * auto-pause (no movement for a while), so the card can say why.
     */
    fun pause(auto: Boolean = false) {
        if (_state.value != TripState.Running) return
        cancelCollection()
        _stats.update { it.copy(pausedAtNanos = clock.nowNanos(), autoPaused = auto) }
        _state.value = TripState.Paused
    }

    /**
     * Continues a paused trip. The paused time is excluded from elapsed, and the last fix is
     * forgotten, so the first fix after resuming starts a new segment: wherever the car went
     * while paused adds no distance or moving time.
     */
    fun resume() {
        if (_state.value != TripState.Paused) return
        val now = clock.nowNanos()
        _stats.update {
            it.copy(
                pausedNanos = it.pausedNanos + (now - (it.pausedAtNanos ?: now)),
                // Auto-pause counts from the resume, not from the last movement before the pause.
                lastMovingAtNanos = now,
                pausedAtNanos = null,
                autoPaused = false,
                lastSample = null,
                currentSpeedMps = 0f,
                gpsStatus = GpsStatus.Searching,
            )
        }
        collect()
    }

    /** Ends the trip: the GPS stops and every figure freezes (at the pause, if paused). */
    fun end() {
        cancelCollection()
        if (_state.value != TripState.Running && _state.value != TripState.Paused) return
        _stats.update { it.copy(endedAtNanos = it.pausedAtNanos ?: clock.nowNanos()) }
        _state.value = TripState.Ended
    }

    /**
     * Reset = discard (9a): the trip is dropped without saving, the GPS read stops and the
     * tracker is Idle; nothing runs until the next Start. Also clears an ended trip's figures.
     */
    fun discard() {
        cancelCollection()
        _stats.value = TripStats()
        _lastFix.value = null
        _state.value = TripState.Idle
    }

    private fun collect() {
        _state.value = TripState.Running
        job = scope.launch {
            source.samples
                .retryWhen { cause, _ ->
                    when (cause) {
                        is LocationPermissionException -> false
                        is LocationDisabledException -> {
                            setStatus(GpsStatus.Disabled)
                            delay(RETRY_DISABLED_MS)
                            // Only leave "off" when the retry can work, so the tile doesn't
                            // flicker Off → No fix → Off on every attempt.
                            if (source.isLocationEnabled()) setStatus(GpsStatus.Searching)
                            true
                        }
                        else -> {
                            delay(RETRY_ERROR_MS)
                            true
                        }
                    }
                }
                .catch { cause ->
                    if (cause !is LocationPermissionException) throw cause
                    setStatus(GpsStatus.PermissionDenied)
                    _stats.update { it.copy(endedAtNanos = clock.nowNanos()) }
                    _state.value = TripState.Ended
                }
                .collect { fix ->
                    _lastFix.value = fix
                    _stats.update { TripReducer.reduce(it, fix) }
                }
        }
    }

    private fun cancelCollection() {
        job?.cancel()
        job = null
    }

    private fun setStatus(status: GpsStatus) = _stats.update { it.copy(gpsStatus = status) }

    companion object {
        const val RETRY_DISABLED_MS = 3_000L
        const val RETRY_ERROR_MS = 5_000L
    }
}
