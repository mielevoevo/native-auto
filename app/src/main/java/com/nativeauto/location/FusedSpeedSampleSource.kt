package com.nativeauto.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.nativeauto.trip.SpeedSample
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real location via the fused location provider: high accuracy, 1 s interval. Every fix with a
 * known accuracy is passed on, best available first: a rough Wi-Fi / cell position indoors,
 * then satellite GPS once it locks (the fused provider switches on its own). Fixes without a
 * speed are marked [SpeedSample.hasSpeed] = false, so only the map and navigation use them.
 */
class FusedSpeedSampleSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeedSampleSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    override val samples: Flow<SpeedSample> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) throw LocationPermissionException()
        if (!isLocationEnabled()) throw LocationDisabledException()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    if (location.hasAccuracy()) trySend(location.toSpeedSample())
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Unavailable also happens in tunnels; only "switched off" ends the stream.
                if (!availability.isLocationAvailable && !isLocationEnabled()) {
                    close(LocationDisabledException())
                }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { close(it) }
        } catch (e: SecurityException) {
            throw LocationPermissionException(e)
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    override fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}

/** Maps a framework [Location] to the pure-Kotlin trip model, SI units throughout. */
internal fun Location.toSpeedSample() = SpeedSample(
    timestampNanos = elapsedRealtimeNanos,
    speedMps = speed,
    speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    // No accuracy reported means unknown, so the reducer treats it as unusable.
    horizontalAccuracyM = if (hasAccuracy()) accuracy else Float.POSITIVE_INFINITY,
    latitude = latitude,
    longitude = longitude,
    bearingDeg = if (hasBearing()) bearing else null,
    hasSpeed = hasSpeed(),
)
