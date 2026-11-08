package com.nativeauto.location

import com.nativeauto.core.MonotonicClock
import com.nativeauto.nav.RouteSimulation
import com.nativeauto.nav.simulatedDrive
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.trip.SpeedSample
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Debug builds only: follows the developer toggles without restarting tracking.
 * - A simulated navigation route (settings → "Simulate navigation") wins: the vehicle drives it.
 * - Else "Fake GPS" plays the scripted [FakeSpeedSampleSource].
 * - Else the real [FusedSpeedSampleSource].
 * Release builds bind the real source directly (see LocationModule).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableSpeedSampleSource @Inject constructor(
    private val real: FusedSpeedSampleSource,
    private val fake: FakeSpeedSampleSource,
    private val simulation: RouteSimulation,
    private val clock: MonotonicClock,
    settings: SettingsRepository,
) : SpeedSampleSource {

    @Volatile private var usingFake = false

    override val samples: Flow<SpeedSample> = combine(settings.useFakeGps.distinctUntilChanged(), simulation.route) { useFake, route -> useFake to route }
        .distinctUntilChanged()
        .flatMapLatest { (useFake, route) ->
            usingFake = useFake || route != null
            when {
                route != null -> simulatedDrive(route, clock)
                useFake -> fake.samples
                else -> real.samples
            }
        }

    override fun isLocationEnabled(): Boolean = usingFake || real.isLocationEnabled()
}
