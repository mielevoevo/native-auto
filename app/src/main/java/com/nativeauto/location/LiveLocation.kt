package com.nativeauto.location

import android.location.Location
import com.nativeauto.di.ApplicationScope
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the vehicle is, for everything that isn't trip maths (map, navigation). While a trip
 * runs it reuses the tracker's fixes, so there's one GPS subscription and every pane agrees,
 * fake drive included. Otherwise it reads the source itself; that subscription stops 5 s
 * after the last reader goes away (map off screen, navigation ended).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveLocation @Inject constructor(
    private val tracker: TripTracker,
    source: SpeedSampleSource,
    @ApplicationScope scope: CoroutineScope,
) {
    val fixes: SharedFlow<SpeedSample> = tracker.state
        .map { it == TripState.Running }
        .distinctUntilChanged()
        .flatMapLatest { running ->
            if (running) {
                tracker.lastFix.filterNotNull()
            } else {
                // Display only: a failure (no access, location off) just means no fix until it recovers.
                source.samples.retryWhen { _, _ -> delay(RETRY_MS); true }
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** The newest fix anyone has seen, without subscribing. */
    val latest: SpeedSample? get() = fixes.replayCache.lastOrNull() ?: tracker.lastFix.value

    private companion object {
        const val RETRY_MS = 3_000L
    }
}

/** For the map's location dot and for Ferrostar. Trip maths never reads `time` (rule #8). */
fun SpeedSample.toLocation(): Location = Location("native-auto").apply {
    latitude = this@toLocation.latitude
    longitude = this@toLocation.longitude
    if (horizontalAccuracyM.isFinite()) accuracy = horizontalAccuracyM
    if (hasSpeed) speed = speedMps
    if (hasSpeed) speedAccuracyMps?.let { speedAccuracyMetersPerSecond = it }
    bearingDeg?.let { bearing = it }
    elapsedRealtimeNanos = timestampNanos
    time = System.currentTimeMillis()
}
