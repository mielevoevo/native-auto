package com.nativeauto.trip

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure fold of GPS fixes into [TripStats]. No clocks, no Android, fully unit-testable.
 *
 * **Deviation from the handoff's §6.3 reference:** a physically implausible fix (speed change
 * over [MAX_ACCEL_MPS2]) is ignored *completely*, like a poor-accuracy fix, instead of only
 * being kept out of max speed. The reference let a spike reach the current speed and the
 * distance, and compared each fix with the previous *raw* one, so two bad fixes in a row
 * passed the filter and set a false max for the rest of the trip.
 *
 * Because a rejected fix doesn't move [TripStats.lastSample], the next fix is judged against
 * the last *good* one over the longer interval. The allowed change therefore grows by
 * 12 m/s every second, so the filter can't lock onto a bad value: a real change is accepted
 * within ~1–3 s, and the distance it skipped is caught up then.
 */
object TripReducer {
    const val STATIONARY_MPS = 0.8f              // ~3 km/h; below = jitter → 0
    const val MAX_ACCURACY_M = 25f
    const val MAX_ACCEL_MPS2 = 12f               // ~1.2 g; no road car changes speed faster
    const val MAX_GAP_NANOS = 5_000_000_000L     // tunnel → straight-line distance
    const val MAX_SPEED_ACCURACY_MPS = 1.5f

    fun reduce(s: TripStats, fix: SpeedSample): TripStats {
        // Position-only fixes (no speed) feed the map and navigation, never the trip.
        if (!fix.hasSpeed || fix.horizontalAccuracyM > MAX_ACCURACY_M) return s.copy(gpsStatus = GpsStatus.Searching)

        val speed = if (fix.speedMps < STATIONARY_MPS) 0f else fix.speedMps
        val prev = s.lastSample ?: return s.copy(
            startedAtNanos = s.startedAtNanos ?: fix.timestampNanos,
            lastSample = fix, currentSpeedMps = speed, gpsStatus = GpsStatus.Fix,
        )

        val dtNanos = fix.timestampNanos - prev.timestampNanos
        if (dtNanos <= 0L) return s                // duplicate or out-of-order fix
        val dtSec = dtNanos / 1e9

        // Spike: ignore the fix entirely. `s` still holds the last good fix to compare against.
        val plausible = abs(speed - s.currentSpeedMps) / dtSec <= MAX_ACCEL_MPS2
        if (!plausible) return s

        // Short gaps: trapezoid of the two speeds. Long gaps (tunnels): straight-line distance.
        val dDist = if (dtNanos > MAX_GAP_NANOS) haversineMeters(prev, fix)
                    else (s.currentSpeedMps + speed) / 2.0 * dtSec

        val accurate = fix.speedAccuracyMps?.let { it < MAX_SPEED_ACCURACY_MPS } ?: true

        return s.copy(
            lastSample = fix,
            currentSpeedMps = speed,
            maxSpeedMps = if (accurate) max(s.maxSpeedMps, speed) else s.maxSpeedMps,
            distanceM = s.distanceM + dDist,
            movingNanos = s.movingNanos + if (speed > 0f) dtNanos else 0L,
            gpsStatus = GpsStatus.Fix,
        )
    }
}
