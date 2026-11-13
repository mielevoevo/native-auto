package com.nativeauto.location

import android.content.Context
import com.nativeauto.garage.ActiveBike
import com.nativeauto.garage.GarageRepository
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.trip.AUTO_START_MAX_GAP_NANOS
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.trip.AutoStartState
import com.nativeauto.trip.ODOMETER_CHUNK_M
import com.nativeauto.trip.ODOMETER_IDLE_NANOS
import com.nativeauto.trip.TripReducer
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripStats
import com.nativeauto.trip.TripTracker
import com.nativeauto.trip.autoStartAllowed
import com.nativeauto.trip.stepAutoStart
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext

/** What auto-start does to the rest of the app; an interface so the monitor is testable on the JVM. */
interface AutoStartActions {
    /** Starts a trip now (the same path as the Start button). */
    fun startTrip()

    /** Adds [km] ridden to the active bike's odometer, fuel and services, without saving a trip. */
    suspend fun addOdometerKm(km: Double)
}

class AndroidAutoStartActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val garage: GarageRepository,
    private val bike: ActiveBike,
) : AutoStartActions {
    override fun startTrip() = TripService.start(context)

    override suspend fun addOdometerKm(km: Double) {
        val id = bike.id() ?: return
        garage.addUntrackedKm(id, km)
    }
}

/**
 * 13b: starts the rider's logging for them, **only while the app is open** (the activity's started
 * lifecycle calls [watch]; it is cancelled when the app goes to the background). No service, no
 * background location.
 *
 * While the setting is on and the state is clean ([autoStartAllowed]) it watches the fixes
 * ([SpeedSampleSource.watchSamples]: low rate while stationary, full rate once moving) for 15 km/h
 * held for the hold time. Then, by the setting:
 * - **Start trip**: starts a trip right then (the first seconds are not recorded: an auto-start is
 *   consent proven by "app open and already moving") and announces it ([announcements]).
 * - **Odometer only**: the km ridden (from the start of the hold) go to the odometer in chunks,
 *   and the rest when riding stops or the app leaves, without a saved trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AutoStartMonitor @Inject constructor(
    private val source: SpeedSampleSource,
    private val tracker: TripTracker,
    private val settings: SettingsRepository,
    private val actions: AutoStartActions,
) {
    private val _announcements = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One event each time a trip was started by auto-start. */
    val announcements: SharedFlow<Unit> = _announcements.asSharedFlow()

    @Volatile private var cleanAtOpen = true
    @Volatile private var endedThisSession = false

    /** The app was opened afresh (not a rotation): the session starts over. Clean only with no trip in sight. */
    fun onAppOpened() {
        cleanAtOpen = tracker.state.value == TripState.Idle
        endedThisSession = false
    }

    /** Runs until cancelled. Call it from a lifecycle that is active only while the app is on screen. */
    suspend fun watch() {
        combine(
            settings.autoStartMode,
            settings.autoStartHoldS,
            tracker.state.onEach { if (it == TripState.Ended) endedThisSession = true },
        ) { mode, holdS, trip -> Plan(mode, holdS, autoStartAllowed(mode, trip, endedThisSession, cleanAtOpen)) }
            .distinctUntilChanged()
            .flatMapLatest { plan -> if (plan.allowed) kotlinx.coroutines.flow.flow<Unit> { run(plan) } else emptyFlow() }
            .collect {}
    }

    private data class Plan(val mode: AutoStartMode, val holdS: Int, val allowed: Boolean)

    private suspend fun run(plan: Plan) {
        val holdNanos = plan.holdS * 1_000_000_000L
        var hold = AutoStartState()
        // Odometer-only: after the hold, the run's distance as the trip maths see it, and how much is committed.
        var odo: TripStats? = null
        var committedM = 0.0
        suspend fun commit(all: Boolean) {
            val stats = odo ?: return
            val pending = stats.distanceM - committedM
            if (pending >= ODOMETER_CHUNK_M || (all && pending > 0.0)) {
                committedM += pending
                actions.addOdometerKm(pending / 1000.0)
            }
        }
        try {
            sampleFlow().collect { fix ->
                val current = odo
                if (current == null) {
                    val step = stepAutoStart(hold, fix, holdNanos)
                    hold = step.state
                    if (step.triggered) {
                        when (plan.mode) {
                            AutoStartMode.TRIP -> {
                                actions.startTrip()
                                _announcements.tryEmit(Unit)
                                // Running ends this watch; if the trip could not start, try again after a pause.
                                delay(RETRY_START_MS)
                                hold = AutoStartState()
                            }
                            AutoStartMode.ODOMETER_ONLY -> {
                                odo = step.state.stats
                                committedM = 0.0
                                commit(all = false)
                            }
                            AutoStartMode.OFF -> Unit
                        }
                    }
                } else {
                    val next = TripReducer.reduce(current, fix)
                    odo = next
                    commit(all = false)
                    val idleFor = fix.timestampNanos - (next.lastMovingAtNanos ?: fix.timestampNanos)
                    val gap = fix.timestampNanos - (current.lastSample?.timestampNanos ?: fix.timestampNanos)
                    if (idleFor > ODOMETER_IDLE_NANOS || gap > AUTO_START_MAX_GAP_NANOS * 6) {
                        // Riding stopped: settle up, and a new ride has to earn its hold again.
                        commit(all = true)
                        odo = null
                        hold = AutoStartState()
                    }
                }
            }
        } catch (e: CancellationException) {
            // The app left the screen or the state changed: what has been ridden still counts.
            withContext(NonCancellable) { commit(all = true) }
            throw e
        }
    }

    /** The watch stream, retried while location is off or not granted yet (the permission gate asks). */
    private fun sampleFlow(): Flow<com.nativeauto.trip.SpeedSample> =
        source.watchSamples.retryWhen { _, _ -> delay(RETRY_MS); true }

    private companion object {
        const val RETRY_MS = 10_000L
        const val RETRY_START_MS = 5_000L
    }
}
