package com.nativeauto.trip

/** One GPS fix, in SI units (rule #7). Pure Kotlin: no android.* imports (rule #9). */
data class SpeedSample(
    val timestampNanos: Long,        // Location.elapsedRealtimeNanos
    val speedMps: Float,
    val speedAccuracyMps: Float?,
    val horizontalAccuracyM: Float,
    val latitude: Double,
    val longitude: Double,
    /** Course over ground, degrees clockwise from north; null when the fix has none. Map only. */
    val bearingDeg: Float? = null,
    /**
     * False for a position-only fix (Wi-Fi / cell, or GPS before it has a course): [speedMps]
     * is then meaningless. The map and navigation still use the position; the trip ignores it.
     */
    val hasSpeed: Boolean = true,
)

enum class GpsStatus { Searching, Fix, Stale, Disabled, PermissionDenied }
