package com.nativeauto.ui.speed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripOutcome
import com.nativeauto.history.periodDistanceM
import com.nativeauto.settings.TotalPeriod
import java.time.ZoneId
import com.nativeauto.history.TripRecorder
import com.nativeauto.trip.TripState
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.TelemetryStat
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
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
    settings: SettingsRepository,
) : ViewModel() {

    /** The figures to show (9c): everything not hidden in settings. */
    val visible: StateFlow<Set<TelemetryStat>> = settings.hiddenStats
        .map { TelemetryStat.entries.toSet() - it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelemetryStat.entries.toSet() - TelemetryStat.DEFAULT_HIDDEN)

    val period: StateFlow<TotalPeriod> = settings.totalPeriod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TotalPeriod.DAY)

    /** Re-reads the clock each minute, so "Today" rolls over at midnight. */
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    private val savedM = combine(dao.observeAll(), settings.totalPeriod, minutes) { trips, p, _ ->
        periodDistanceM(trips, p, System.currentTimeMillis(), ZoneId.systemDefault())
    }.distinctUntilChanged()

    /**
     * 12b (R2): metres in the chosen period: the saved trips plus the one running or paused (an
     * ended trip is already saved, or was too short to keep).
     */
    val periodM: StateFlow<Double> = combine(
        savedM,
        tracker.state,
        tracker.stats.map { it.distanceM }.distinctUntilChanged(),
    ) { saved, state, live ->
        saved + if (state == TripState.Running || state == TripState.Paused) live else 0.0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

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
    }
}
