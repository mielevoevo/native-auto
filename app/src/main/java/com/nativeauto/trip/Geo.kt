package com.nativeauto.trip

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (IUGG), metres. */
const val EARTH_RADIUS_M = 6_371_008.8

/** Metres per degree of latitude on the mean sphere. */
const val METERS_PER_DEGREE_LAT = EARTH_RADIUS_M * Math.PI / 180.0

/** Great-circle distance in metres between two coordinates (haversine formula). */
fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
    // min() guards against rounding pushing `a` fractionally above 1 for antipodal points.
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

fun haversineMeters(a: SpeedSample, b: SpeedSample): Double =
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
