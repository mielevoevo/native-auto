package com.nativeauto.location

import com.nativeauto.trip.SpeedSample
import kotlinx.coroutines.flow.Flow

/**
 * A cold stream of GPS fixes. Collecting starts updates; cancelling stops them.
 *
 * Instead of crashing, a source fails with [LocationPermissionException] (no precise-location
 * permission; terminal until the user grants it) or [LocationDisabledException] (location is
 * switched off in system settings; worth retrying).
 */
interface SpeedSampleSource {
    val samples: Flow<SpeedSample>

    /**
     * Whether a retry after [LocationDisabledException] can succeed now (location switched
     * back on). Lets the tracker leave the "off" state without a flicker on every retry.
     */
    fun isLocationEnabled(): Boolean = true

    /**
     * 13b: the same fixes for something that only watches for movement (auto-start), at a rate that
     * suits it: slow while stationary, full rate once moving. Sources with nothing to save just
     * reuse [samples].
     */
    val watchSamples: Flow<SpeedSample> get() = samples
}

class LocationPermissionException(cause: Throwable? = null) :
    Exception("Precise location permission is not granted", cause)

class LocationDisabledException : Exception("Location is turned off in system settings")
