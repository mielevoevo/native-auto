package com.nativeauto.trip

import com.nativeauto.core.MonotonicClock
import com.nativeauto.di.ApplicationScope
import com.nativeauto.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** What auto-pause / auto-end want done now. */
enum class AutoAction { Pause, End }

private const val NANOS_PER_MINUTE = 60_000_000_000L

/**
 * Rider-Feedback "Decided" (for "forgot to close the app"):
 * - running, below walking pace ([TripReducer.STATIONARY_MPS]) for [pauseMin] → pause;
 * - paused (by hand or auto) for [endMin] → end, which saves the trip.
 * A limit of 0 is off. "Below walking pace" counts from the last moving fix, or from the start /
 * resume if there was none since; no fix at all (indoors) counts as not moving.
 */
internal fun autoAction(state: TripState, stats: TripStats, nowNanos: Long, pauseMin: Int, endMin: Int): AutoAction? =
    when (state) {
        TripState.Running -> {
            val since = stats.lastMovingAtNanos ?: stats.startedAtNanos
            if (pauseMin > 0 && since != null && nowNanos - since >= pauseMin * NANOS_PER_MINUTE) AutoAction.Pause else null
        }
        TripState.Paused -> {
            val since = stats.pausedAtNanos
            if (endMin > 0 && since != null && nowNanos - since >= endMin * NANOS_PER_MINUTE) AutoAction.End else null
        }
        TripState.Idle, TripState.Ended -> null
    }

/**
 * Applies [autoAction] to the [TripTracker]: checks every [CHECK_MS] while a trip is running or
 * paused, nothing otherwise. Auto-pause stops the GPS like a manual pause; there is no
 * auto-resume (that would need the GPS on). Started once from the Application.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AutoTrip @Inject constructor(
    private val tracker: TripTracker,
    private val settings: SettingsRepository,
    private val clock: MonotonicClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(tracker.state, settings.autoPauseMinutes, settings.autoEndMinutes, ::Triple)
                .flatMapLatest { (state, pauseMin, endMin) ->
                    if (state != TripState.Running && state != TripState.Paused) emptyFlow()
                    else flow {
                        while (true) {
                            delay(CHECK_MS)
                            emit(autoAction(tracker.state.value, tracker.stats.value, clock.nowNanos(), pauseMin, endMin))
                        }
                    }
                }
                .collect { action ->
                    when (action) {
                        AutoAction.Pause -> tracker.pause(auto = true)
                        AutoAction.End -> tracker.end()
                        null -> Unit
                    }
                }
        }
    }

    internal companion object {
        const val CHECK_MS = 10_000L
    }
}
