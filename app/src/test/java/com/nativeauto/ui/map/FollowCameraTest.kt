package com.nativeauto.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowCameraTest {

    private fun target(speed: Float, course: Float?, zoom: Float = 16f, bearing: Float = 30f) =
        FollowCamera.target(51.0, -2.5, speedMps = speed, courseDeg = course, currentZoom = zoom, currentBearing = bearing)

    @Test fun `centres on the fix, tilted`() {
        val t = target(speed = 20f, course = 90f)
        assertEquals(51.0, t.latitude, 0.0)
        assertEquals(-2.5, t.longitude, 0.0)
        assertEquals(FollowCamera.TILT, t.tilt, 0f)
    }

    @Test fun `heading-up while moving`() {
        assertEquals(90f, target(speed = 20f, course = 90f).bearing, 0f)
        assertEquals(350f, target(speed = 20f, course = -10f).bearing, 1e-4f)
        assertEquals(10f, target(speed = 20f, course = 370f).bearing, 1e-4f)
    }

    @Test fun `parked or without a course, the bearing holds still`() {
        // Below ~11 km/h GPS course is noise; spinning the map with it would be worse.
        assertEquals(30f, target(speed = 1f, course = 200f).bearing, 0f)
        assertEquals(30f, target(speed = 20f, course = null).bearing, 0f)
        assertEquals(90f, target(speed = FollowCamera.MIN_HEADING_SPEED_MPS, course = 90f).bearing, 0f)
    }

    @Test fun `keeps the user's zoom unless it's zoomed out too far`() {
        assertEquals(15f, target(speed = 20f, course = 0f, zoom = 15f).zoom, 0f)
        assertEquals(FollowCamera.ZOOM, target(speed = 20f, course = 0f, zoom = 4f).zoom, 0f)
    }
}
