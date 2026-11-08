package com.nativeauto.ui.map

/**
 * Where the follow camera should be for a fix: centred on it, tilted, and turned so the
 * direction of travel points up. Pure, so the rules are unit-tested without a map.
 *
 * - The heading only follows the fix's course while actually moving: when parked, GPS course
 *   is noise, and the map would spin. Below that speed the current bearing is kept.
 * - The user's zoom is kept (pinching breaks follow, and recentering shouldn't undo the zoom
 *   they chose), unless it's zoomed out too far to be useful for driving.
 */
internal data class CameraTarget(
    val latitude: Double,
    val longitude: Double,
    val zoom: Float,
    val tilt: Float,
    val bearing: Float,
)

internal object FollowCamera {
    const val ZOOM = 17f
    const val MIN_FOLLOW_ZOOM = 13f
    const val TILT = 45f
    /** ~11 km/h: below this, course readings aren't trustworthy enough to rotate the map. */
    const val MIN_HEADING_SPEED_MPS = 3f

    fun target(
        latitude: Double,
        longitude: Double,
        speedMps: Float,
        courseDeg: Float?,
        currentZoom: Float,
        currentBearing: Float,
    ) = CameraTarget(
        latitude = latitude,
        longitude = longitude,
        zoom = if (currentZoom < MIN_FOLLOW_ZOOM) ZOOM else currentZoom,
        tilt = TILT,
        bearing = if (courseDeg != null && speedMps >= MIN_HEADING_SPEED_MPS) normalize(courseDeg) else currentBearing,
    )

    private fun normalize(deg: Float) = ((deg % 360f) + 360f) % 360f
}
