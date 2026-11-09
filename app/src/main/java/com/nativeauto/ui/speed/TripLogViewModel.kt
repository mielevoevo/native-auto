package com.nativeauto.ui.speed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripOutcome
import com.nativeauto.history.TripRecord
import com.nativeauto.history.TripRecorder
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The speed card's log face and end-of-trip summary (9b). Pause, End and the summary's dismiss
 * go straight to the [TripTracker], like Reset in settings; Start and Resume go through
 * `TripService` (it must be in the foreground to read the GPS), which the card calls itself.
 *
 * End needs two taps within [ARM_MS], like Reset: the first turns ⏹ red. The timer lives
 * here, not in composition (a delay loop in a composable stalls the v2 test dispatcher).
 */
@HiltViewModel
class TripLogViewModel @Inject constructor(
    private val tracker: TripTracker,
    recorder: TripRecorder,
    dao: TripDao,
) : ViewModel() {

    /** The last few saved rides, newest first. */
    val recent: StateFlow<List<TripRecord>> = dao.observeAll()
        .map { it.take(RECENT_COUNT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outcome: StateFlow<TripOutcome?> = recorder.lastOutcome

    private val _endArmed = MutableStateFlow(false)
    val endArmed: StateFlow<Boolean> = _endArmed.asStateFlow()
    private var disarm: Job? = null

    fun pause() = tracker.pause()

    /** First tap arms; the second (within [ARM_MS]) ends and saves. Returns true when it ended. */
    fun onEndTapped(): Boolean {
        disarm?.cancel()
        if (_endArmed.value) {
            _endArmed.value = false
            tracker.end()
            return true
        }
        val state = tracker.state.value
        if (state != TripState.Running && state != TripState.Paused) return false
        _endArmed.value = true
        disarm = viewModelScope.launch {
            delay(ARM_MS)
            _endArmed.value = false
        }
        return false
    }

    /** Closes the end-of-trip summary: the figures go back to zero until the next Start. */
    fun dismissSummary() {
        if (tracker.state.value == TripState.Ended) tracker.discard()
    }

    companion object {
        const val ARM_MS = 3_000L
        const val RECENT_COUNT = 3
    }
}
