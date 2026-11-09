package com.nativeauto.ui.speed

import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.TripState
import kotlin.time.Duration

/** Display-ready speedometer values, already converted to [unit]. */
data class SpeedometerUiState(
    val speed: Int?,          // null → "--"
    val maxSpeed: Int,
    val avgSpeed: Int,
    val movingAvgSpeed: Int,
    val elapsed: Duration,
    val distance: Double,     // km or mi per unit
    val unit: SpeedUnit,
    val gps: GpsStatus,
    val tripState: TripState,
    /** Paused by auto-pause (no movement for a while), not by hand (9a). */
    val autoPaused: Boolean = false,
    /** Time above walking pace, and the rest of the trip time (9c). */
    val movingTime: Duration = Duration.ZERO,
    val stoppedTime: Duration = Duration.ZERO,
) {
    /** The handoff's `isTracking`, derived so it can never disagree with [tripState]. */
    val isTracking: Boolean get() = tripState == TripState.Running

    companion object { val Empty = SpeedometerUiState(null, 0, 0, 0, Duration.ZERO, 0.0, SpeedUnit.KMH, GpsStatus.Searching, TripState.Idle) }
}
