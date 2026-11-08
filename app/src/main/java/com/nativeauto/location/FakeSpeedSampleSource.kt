package com.nativeauto.location

import com.nativeauto.core.MonotonicClock
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.SpeedSample
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Plays back [FakeDrive.script] at 1 Hz, forever, for the emulator and development.
 * Timestamps come from [clock], so they share the time base of the real GPS and the UI ticker.
 * The car drives due north; positions follow the *true* speed, so a tunnel gap's
 * straight-line distance matches what was actually travelled.
 */
class FakeSpeedSampleSource @Inject constructor(
    private val clock: MonotonicClock,
) : SpeedSampleSource {

    override val samples: Flow<SpeedSample> = flow {
        var latitude = FakeDrive.START_LAT
        var prevTrueSpeed = 0f
        while (true) {
            for (step in FakeDrive.script) {
                when (step) {
                    is FakeDrive.Step.Fix -> {
                        delay(FakeDrive.FIX_INTERVAL_MS)
                        latitude += (prevTrueSpeed + step.trueSpeedMps) / 2.0 * FakeDrive.FIX_INTERVAL_S / METERS_PER_DEGREE_LAT
                        prevTrueSpeed = step.trueSpeedMps
                        emit(
                            SpeedSample(
                                timestampNanos = clock.nowNanos(),
                                speedMps = step.reportedSpeedMps,
                                speedAccuracyMps = step.speedAccuracyMps,
                                horizontalAccuracyM = step.horizontalAccuracyM,
                                latitude = latitude,
                                longitude = FakeDrive.START_LON,
                                bearingDeg = 0f, // due north
                            ),
                        )
                    }
                    is FakeDrive.Step.Gap -> {
                        delay(step.seconds * 1_000L)
                        latitude += prevTrueSpeed.toDouble() * step.seconds / METERS_PER_DEGREE_LAT
                    }
                }
            }
        }
    }
}

/**
 * The scripted drive (~90 s per loop): stationary jitter → accelerate to 100 km/h → cruise
 * with two bad speed readings in a row → 8 s tunnel with no fixes (stale "--") → two poor-accuracy
 * fixes ("Searching") → decelerate → 10 s stop.
 */
object FakeDrive {
    const val FIX_INTERVAL_MS = 1_000L
    const val FIX_INTERVAL_S = FIX_INTERVAL_MS / 1_000.0
    const val START_LAT = 51.4545
    const val START_LON = -2.5879

    const val CRUISE_MPS = 27.78f      // 100.0 km/h
    const val SPIKE_MPS = 55f          // 198 km/h — must never show, nor become the max
    const val SPIKE_2_MPS = 56.1f      // 202 km/h — plausible vs the first spike, not vs the truth
    const val TUNNEL_SECONDS = 8

    sealed interface Step {
        data class Fix(
            val reportedSpeedMps: Float,
            val trueSpeedMps: Float = reportedSpeedMps,
            val horizontalAccuracyM: Float = 4f,
            val speedAccuracyMps: Float? = 0.4f,
        ) : Step

        data class Gap(val seconds: Int) : Step
    }

    val script: List<Step> = buildList {
        // Parked: GPS jitter below the stationary threshold.
        listOf(0.3f, 0.6f, 0.2f, 0.5f, 0.4f).forEach { add(Step.Fix(it, trueSpeedMps = 0f)) }
        // Accelerate 0 → 100 km/h over 15 s (1.85 m/s², well inside the spike filter).
        (1..15).forEach { add(Step.Fix(CRUISE_MPS * it / 15)) }
        repeat(15) { add(Step.Fix(CRUISE_MPS)) }
        // Two bad readings in a row while really cruising. The second is only 1 m/s from the
        // first, which is what slipped past the handoff's reference filter.
        add(Step.Fix(SPIKE_MPS, trueSpeedMps = CRUISE_MPS))
        add(Step.Fix(SPIKE_2_MPS, trueSpeedMps = CRUISE_MPS))
        repeat(10) { add(Step.Fix(CRUISE_MPS)) }
        // Tunnel: no fixes at all, still moving.
        add(Step.Gap(TUNNEL_SECONDS))
        repeat(8) { add(Step.Fix(CRUISE_MPS)) }
        // Urban canyon: fixes too inaccurate to use.
        repeat(2) { add(Step.Fix(CRUISE_MPS, horizontalAccuracyM = 40f)) }
        repeat(5) { add(Step.Fix(CRUISE_MPS)) }
        // Brake to a stop over 10 s.
        (9 downTo 0).forEach { add(Step.Fix(CRUISE_MPS * it / 10)) }
        // Waiting at the lights.
        listOf(0.1f, 0.4f, 0.0f, 0.3f, 0.5f, 0.2f, 0.0f, 0.6f, 0.1f, 0.3f).forEach { add(Step.Fix(it, trueSpeedMps = 0f)) }
    }

    /** Seconds per loop, gaps included. */
    val loopSeconds: Int = script.sumOf { if (it is Step.Gap) it.seconds else 1 }
}
