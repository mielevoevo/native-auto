package com.nativeauto.nav

import com.nativeauto.TestClock
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import com.nativeauto.trip.haversineMeters
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedDriveTest {

    /** A point [north] and [east] metres from (0, 0); near the equator a degree is the same both ways. */
    private fun at(north: Double, east: Double = 0.0) = GeoPoint(north / METERS_PER_DEGREE_LAT, east / METERS_PER_DEGREE_LAT)

    private fun dist(a: GeoPoint, lat: Double, lon: Double) = haversineMeters(a.latitude, a.longitude, lat, lon)

    @Test fun `an empty route emits nothing`() = runTest {
        assertEquals(0, simulatedDrive(emptyList(), TestClock(testScheduler)).toList().size)
    }

    @Test fun `drives a straight route at the set speed, one fix a second`() = runTest {
        val clock = TestClock(testScheduler)
        val fixes = simulatedDrive(listOf(at(0.0), at(1_000.0)), clock, speedMps = 10f).take(5).toList()
        fixes.forEachIndexed { k, f ->
            assertEquals((k + 1) * 1_000_000_000L, f.timestampNanos)
            assertEquals(10f, f.speedMps, 0f)
            assertEquals(0f, f.bearingDeg!!, 0.01f)
            assertTrue(f.hasSpeed)
            assertEquals((k + 1) * 10.0, dist(at(0.0), f.latitude, f.longitude), 0.01)
        }
    }

    @Test fun `carries over vertices and stops on the last point`() = runTest {
        // Three 15 m segments heading east, driven at 10 m/s.
        val route = listOf(at(0.0), at(0.0, 15.0), at(0.0, 30.0), at(0.0, 45.0))
        val fixes = simulatedDrive(route, TestClock(testScheduler), speedMps = 10f).take(7).toList()
        val along = fixes.map { dist(route[0], it.latitude, it.longitude) }
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), along.take(4).map { Math.round(it * 100) / 100.0 })
        assertEquals(90f, fixes[0].bearingDeg!!, 0.01f)
        // Fifth second: 5 m left, then parked. Afterwards it stands still at the end.
        for (f in fixes.drop(4)) {
            assertEquals(45.0, dist(route[0], f.latitude, f.longitude), 0.01)
        }
        assertEquals(0f, fixes[5].speedMps, 0f)
        assertEquals(0f, fixes[6].speedMps, 0f)
    }

    @Test fun `slows for a sharp bend ahead`() = runTest {
        // North, then a right angle east: the first leg is ridden at 45 % speed.
        val bend = listOf(at(0.0), at(100.0), at(100.0, 100.0))
        assertEquals(4.5f, simulatedDrive(bend, TestClock(testScheduler), 10f).take(1).toList()[0].speedMps, 1e-4f)
        // A gentle 30° bend doesn't slow it down.
        val gentle = listOf(at(0.0), at(100.0), at(100.0 + 86.6, 50.0))
        assertEquals(10f, simulatedDrive(gentle, TestClock(testScheduler), 10f).take(1).toList()[0].speedMps, 0f)
    }

    @Test fun `a one-point route stands still there`() = runTest {
        val p = at(10.0, 10.0)
        val f = simulatedDrive(listOf(p), TestClock(testScheduler)).take(2).toList()
        f.forEach {
            assertEquals(0f, it.speedMps, 0f)
            assertEquals(p, GeoPoint(it.latitude, it.longitude))
        }
    }

    @Test fun `bearings point the compass way`() {
        assertEquals(0f, bearingDeg(at(0.0), at(10.0)), 0.01f)
        assertEquals(90f, bearingDeg(at(0.0), at(0.0, 10.0)), 0.01f)
        assertEquals(180f, bearingDeg(at(10.0), at(0.0)), 0.01f)
        assertEquals(270f, bearingDeg(at(0.0, 10.0), at(0.0)), 0.01f)
        assertEquals(45f, bearingDeg(at(0.0), at(10.0, 10.0)), 0.01f)
    }
}
