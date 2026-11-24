package com.nativeauto.ui.speed

import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4.6.1: stationary shows the log face, riding the speed. */
class SpeedFaceTest {
    private fun open(trip: TripState) = SpeedFaceState().opened(trip)

    @Test fun `opened without a trip or paused shows the log face, with one running the speed`() {
        assertEquals(SpeedFace.LOG, open(TripState.Idle).face)
        assertEquals(SpeedFace.LOG, open(TripState.Paused).face)
        assertEquals(SpeedFace.SPEED, open(TripState.Running).face)
    }

    @Test fun `riding slides to speed and slowing down stays there`() {
        val riding = open(TripState.Idle).onMoving(true)
        assertEquals(SpeedFace.SPEED, riding.face)
        assertEquals(SpeedFace.SPEED, riding.onMoving(false).face)
    }

    @Test fun `start by hand leaves the log face up until the bike moves`() {
        val started = open(TripState.Idle).onTrip(TripState.Running)
        assertEquals(SpeedFace.LOG, started.face)
        assertEquals(SpeedFace.SPEED, started.onMoving(true).face)
        // An auto-start happens mid-motion.
        assertEquals(SpeedFace.SPEED, open(TripState.Idle).onMoving(true).onTrip(TripState.Running).face)
    }

    @Test fun `pausing and ending show the log face, and a dismissed summary too while stationary`() {
        val running = open(TripState.Running)
        assertEquals(SpeedFace.LOG, running.onTrip(TripState.Paused).face)
        val ended = running.onTrip(TripState.Ended)
        assertEquals(SpeedFace.LOG, ended.face)
        assertEquals(SpeedFace.LOG, ended.onMoving(true).face) // the summary is up
        assertEquals(SpeedFace.LOG, ended.onTrip(TripState.Idle).face)
        assertEquals(SpeedFace.SPEED, ended.onMoving(true).onTrip(TripState.Idle).face)
    }

    @Test fun `a swipe by hand wins until the trip changes or the app is opened again`() {
        val swiped = open(TripState.Running).onSwiped(SpeedFace.LOG)
        assertEquals(SpeedFace.LOG, swiped.onMoving(true).face)
        assertEquals(SpeedFace.LOG, swiped.onTrip(TripState.Running).face)
        assertEquals(SpeedFace.SPEED, swiped.opened(TripState.Running).face)
        assertFalse(swiped.onTrip(TripState.Paused).byHand)
        assertEquals(SpeedFace.SPEED, swiped.onTrip(TripState.Paused).onMoving(true).face)
    }

    @Test fun `no location access shows the speed face, which holds the fix`() {
        val blocked = open(TripState.Idle).onBlocked(true)
        assertEquals(SpeedFace.SPEED, blocked.face)
        assertEquals(SpeedFace.SPEED, blocked.opened(TripState.Idle).face)
        assertEquals(SpeedFace.LOG, blocked.onBlocked(false).opened(TripState.Idle).face)
    }

    private fun fix(s: Int, kmh: Float, accuracy: Float = 5f) = SpeedSample(
        speedMps = kmh / 3.6f, hasSpeed = true, speedAccuracyMps = 0.5f, horizontalAccuracyM = accuracy,
        latitude = -7.0, longitude = 110.0, timestampNanos = s * 1_000_000_000L,
    )

    @Test fun `opened mid-ride counts as riding at the first good fix`() {
        val d = RidingDetector()
        assertFalse(d.onFix(fix(0, 40f, accuracy = 80f)))
        assertTrue(d.onFix(fix(1, 40f)))
    }

    @Test fun `from standing, 10 km per h has to hold three seconds`() {
        val d = RidingDetector()
        assertFalse(d.onFix(fix(0, 0f)))
        assertFalse(d.onFix(fix(1, 15f)))
        assertFalse(d.onFix(fix(2, 15f)))
        assertFalse(d.onFix(fix(3, 4f))) // a wobble starts over
        assertFalse(d.onFix(fix(4, 15f)))
        assertFalse(d.onFix(fix(6, 15f)))
        assertTrue(d.onFix(fix(7, 15f)))
    }
}
