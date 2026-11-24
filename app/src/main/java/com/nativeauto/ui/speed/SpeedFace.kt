package com.nativeauto.ui.speed

import androidx.lifecycle.ViewModel
import com.nativeauto.core.MonotonicClock
import com.nativeauto.location.LiveLocation
import com.nativeauto.trip.AUTO_START_MPS
import com.nativeauto.trip.AutoStartState
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripReducer
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import com.nativeauto.trip.stepAutoStart
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The speed card's two faces, in pager order. */
enum class SpeedFace { SPEED, LOG }

/**
 * 4.6.1 (rider): stationary shows the log face (Start), riding shows the speed. Pure.
 * - Opened with a trip running: speed. Opened without one, or paused: log, until the bike moves.
 * - Riding ([moving], rising edge) slides to speed; slowing down never slides back (traffic, lights).
 * - Pausing and ending show the log face; a trip started by hand leaves it up until the bike moves.
 * - [byHand]: the rider swiped; that face stays until the app is next opened or the trip changes.
 * - [blocked]: no location access. The speed face holds the button that fixes it.
 */
data class SpeedFaceState(
    val face: SpeedFace = SpeedFace.LOG,
    val byHand: Boolean = false,
    val trip: TripState = TripState.Idle,
    val moving: Boolean = false,
    val blocked: Boolean = false,
) {
    fun opened(trip: TripState): SpeedFaceState = copy(
        face = if (blocked || trip == TripState.Running) SpeedFace.SPEED else SpeedFace.LOG,
        byHand = false,
        trip = trip,
        moving = false,
    )

    fun onTrip(next: TripState): SpeedFaceState = if (next == trip) this else copy(
        trip = next,
        byHand = false,
        face = when (next) {
            TripState.Running -> if (moving) SpeedFace.SPEED else face
            TripState.Paused, TripState.Ended -> SpeedFace.LOG
            TripState.Idle -> if (moving || blocked) SpeedFace.SPEED else SpeedFace.LOG
        },
    )

    fun onMoving(now: Boolean): SpeedFaceState =
        copy(moving = now, face = if (now && !moving && !byHand && trip != TripState.Ended) SpeedFace.SPEED else face)

    fun onSwiped(to: SpeedFace): SpeedFaceState = copy(face = to, byHand = true)

    fun onBlocked(now: Boolean): SpeedFaceState =
        copy(blocked = now, face = if (now && !blocked && !byHand) SpeedFace.SPEED else face)
}

/**
 * Whether the bike is being ridden: [AUTO_START_MPS] held for [HOLD_NANOS], by the auto-start's own rules (inaccurate
 * fixes are skipped, a gap starts over). The first usable fix after the app opens needs no hold: opened mid-ride, the
 * speed shows at once.
 */
internal class RidingDetector {
    private var hold = AutoStartState()
    private var first = true

    fun onFix(fix: SpeedSample): Boolean {
        val usable = fix.hasSpeed && fix.horizontalAccuracyM <= TripReducer.MAX_ACCURACY_M
        val step = stepAutoStart(hold, fix, HOLD_NANOS)
        hold = step.state
        val opening = first && usable
        if (usable) first = false
        return step.triggered || (opening && hold.heldSinceNanos != null)
    }

    companion object {
        const val HOLD_NANOS = 3_000_000_000L
    }
}

/** Drives [SpeedFaceState] from the trip and the fixes the speed card already reads (no GPS read of its own). */
@HiltViewModel
class SpeedFaceViewModel @Inject constructor(
    private val tracker: TripTracker,
    private val live: LiveLocation,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val _state = MutableStateFlow(SpeedFaceState().opened(tracker.state.value))
    val state: StateFlow<SpeedFaceState> = _state.asStateFlow()

    /** Runs while the app is started; each start is an app open. */
    suspend fun watch() {
        _state.update { it.opened(tracker.state.value) }
        coroutineScope {
            launch { tracker.state.collect { t -> _state.update { it.onTrip(t) } } }
            val riding = RidingDetector()
            live.fixes.collect { fix ->
                // A replayed fix from before the app was in the background says nothing about now.
                if (clock.nowNanos() - fix.timestampNanos > SpeedometerViewModel.STALE_AFTER_NANOS) return@collect
                val moving = riding.onFix(fix)
                _state.update { it.onMoving(moving) }
            }
        }
    }

    fun onSwiped(face: SpeedFace) = _state.update { it.onSwiped(face) }

    fun onBlocked(blocked: Boolean) = _state.update { it.onBlocked(blocked) }
}
