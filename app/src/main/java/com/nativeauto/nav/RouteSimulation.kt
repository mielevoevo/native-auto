package com.nativeauto.nav

import com.nativeauto.core.MonotonicClock
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.haversineMeters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min

/**
 * Debug builds only ("Simulate navigation"): while set, the location source drives along this
 * route instead of reading the GPS, so turn-by-turn can be tried at a desk. The navigator sets
 * it when navigation starts and clears it when it ends.
 */
@Singleton
class RouteSimulation @Inject constructor() {
    val route = MutableStateFlow<List<GeoPoint>?>(null)
}

/**
 * Fixes at 1 Hz along [route] at [speedMps], slowing for sharp bends like a rider would; at the
 * end the vehicle stands still on the last point.
 */
fun simulatedDrive(route: List<GeoPoint>, clock: MonotonicClock, speedMps: Float = 11f): Flow<SpeedSample> = flow {
    if (route.isEmpty()) return@flow
    var i = 0                    // segment start index
    var along = 0.0              // metres into segment i
    var bearing = if (route.size > 1) bearingDeg(route[0], route[1]) else 0f
    while (true) {
        delay(1_000)
        var speed = 0f
        if (i < route.size - 1) {
            val nextBearing = if (i + 2 < route.size) bearingDeg(route[i + 1], route[i + 2]) else bearing
            val bend = angleBetween(bearing, nextBearing)
            speed = if (bend > 60f) speedMps * 0.45f else speedMps
            var left = speed.toDouble()
            while (left > 0 && i < route.size - 1) {
                val segment = haversineMeters(route[i].latitude, route[i].longitude, route[i + 1].latitude, route[i + 1].longitude)
                val step = min(left, segment - along)
                along += step
                left -= step
                if (along >= segment - 1e-6) {
                    i++
                    along = 0.0
                }
            }
            if (i < route.size - 1) bearing = bearingDeg(route[i], route[i + 1])
        }
        val p = if (i >= route.size - 1) route.last() else interpolate(route[i], route[i + 1], along)
        emit(
            SpeedSample(
                timestampNanos = clock.nowNanos(),
                speedMps = speed,
                speedAccuracyMps = 0.4f,
                horizontalAccuracyM = 4f,
                latitude = p.latitude,
                longitude = p.longitude,
                bearingDeg = bearing,
            ),
        )
    }
}

private fun interpolate(a: GeoPoint, b: GeoPoint, metres: Double): GeoPoint {
    val total = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    if (total < 1e-6) return a
    val t = metres / total
    return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
}

internal fun bearingDeg(a: GeoPoint, b: GeoPoint): Float {
    val dLon = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    val dLat = Math.toRadians(b.latitude - a.latitude)
    return ((Math.toDegrees(atan2(dLon, dLat)) + 360) % 360).toFloat()
}

private fun angleBetween(a: Float, b: Float): Float {
    val d = ((b - a) % 360 + 360) % 360
    return if (d > 180) 360 - d else d
}
