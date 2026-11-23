package com.nativeauto.greeting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.core.WallClock
import com.nativeauto.garage.DueLevel
import com.nativeauto.garage.GarageCardState
import com.nativeauto.garage.GarageRepository
import com.nativeauto.history.TripDao
import com.nativeauto.history.periodDistanceM
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.TotalPeriod
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the greeting sheet shows. [card] is null without a bike. */
data class GreetingUi(
    val title: String,
    val card: GarageCardState?,
    /** Saved trips this week plus a trip in progress, metres. */
    val weekM: Double,
) {
    /** The service line, only when one is due soon or overdue. */
    val serviceDue get() = card?.reminder?.takeIf { it.level == DueLevel.SOON || it.level == DueLevel.OVERDUE }
}

/**
 * 4.3c: decides on each app open ([onOpened], from the activity's ON_START) whether the day's greeting shows, and
 * marks the day as soon as it does (a process death afterwards doesn't greet twice). It goes away by itself 8 s after
 * a trip starts running. The changelog waits while it is up ([showing]).
 */
@HiltViewModel
class GreetingViewModel @Inject constructor(
    private val store: GreetingStore,
    private val settings: SettingsRepository,
    private val tracker: TripTracker,
    private val navigator: Navigator,
    private val wall: WallClock,
    garage: GarageRepository,
    trips: TripDao,
) : ViewModel() {

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private val zone: ZoneId get() = ZoneId.systemDefault()

    val ui: StateFlow<GreetingUi?> = combine(_showing, garage.activeCard, trips.observeAll(), tracker.stats) { show, card, saved, stats ->
        if (!show) return@combine null
        val now = wall.nowMs()
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        val live = if (tracker.state.value == TripState.Running || tracker.state.value == TripState.Paused) stats.distanceM else 0.0
        GreetingUi(greetingFor(hour), card, periodDistanceM(saved, TotalPeriod.WEEK, now, zone) + live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Riding off with the greeting still up: it steps aside 8 s after the trip runs.
        viewModelScope.launch {
            combine(_showing, tracker.state) { show, trip -> show && trip == TripState.Running }.collectLatest { riding ->
                if (riding) {
                    delay(AUTO_CLOSE_MS)
                    _showing.value = false
                }
            }
        }
    }

    /** The app came to the foreground (not a rotation: the activity handles those itself). */
    fun onOpened() {
        if (_showing.value) return
        viewModelScope.launch {
            val today = Instant.ofEpochMilli(wall.nowMs()).atZone(zone).toLocalDate()
            val busy = tracker.state.value == TripState.Running || tracker.state.value == TripState.Paused ||
                navigator.state.value !is NavState.Idle
            if (shouldGreet(today, store.lastDay(), settings.dailyGreeting.first(), busy)) {
                store.markGreeted(today)
                _showing.value = true
            }
        }
    }

    fun dismiss() {
        _showing.value = false
    }

    companion object {
        const val AUTO_CLOSE_MS = 8_000L
    }
}
