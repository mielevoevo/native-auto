package com.nativeauto.history

import com.nativeauto.nav.GeoPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxBackupTest {

    private val trip = TripRecord(
        id = 7, startedAtMs = 1_762_650_000_000L, endedAtMs = 1_762_650_600_000L, durationMs = 600_000, movingMs = 500_000,
        stoppedMs = 100_000, distanceM = 5_000.0, maxSpeedMps = 22f, startLat = -7.8, startLon = 110.37, endLat = -7.75, endLon = 110.4,
        startAddress = "Jalan Malioboro & <Co>", endAddress = null,
        track = Polyline.encode(listOf(GeoPoint(-7.8, 110.37), GeoPoint(-7.75, 110.4))), bikeId = "Gen 1",
    )
    private val points = listOf(
        TrackPointRecord(tripId = 7, timeMs = 1_762_650_000_000L, latitude = -7.8, longitude = 110.37, speedMps = 10.5f, bearingDeg = 45f, accuracyM = 4f),
        TrackPointRecord(tripId = 7, timeMs = 1_762_650_001_000L, latitude = -7.79991, longitude = 110.37008, speedMps = 11f, bearingDeg = null, accuracyM = 5f),
    )

    @Test fun `gpx carries time, speed and course per point, and escapes names`() {
        val gpx = buildGpx(trip, points)
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("""<trkpt lat="-7.8000000" lon="110.3700000"><time>2025-11-09T01:00:00Z</time>"""))
        assertTrue(gpx.contains("<speed>10.50</speed><course>45.00</course>"))
        // No bearing: no course element.
        assertTrue(gpx.contains("<speed>11.00</speed></extensions>"))
        assertTrue(gpx.contains("Jalan Malioboro &amp; &lt;Co&gt;"))
        assertFalse(gpx.contains("<Co>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test fun `an old trip without a full track exports its path without times`() {
        val gpx = buildGpx(trip, emptyList())
        assertEquals(2, Regex("<trkpt [^>]*/>").findAll(gpx).count())
        assertFalse(gpx.contains("<trkpt lat=\"-7.8000000\" lon=\"110.3700000\"><time>"))
    }

    @Test fun `a backup zip holds the json and one gpx per trip, and reads back the same`() {
        val out = ByteArrayOutputStream()
        Backup.write(listOf(Backup.TripWithTrack(trip, points)), out)

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertEquals(listOf(Backup.JSON_ENTRY, "gpx/" + gpxFileName(trip)), names)

        val back = Backup.read(ByteArrayInputStream(out.toByteArray())).single()
        assertEquals(trip.copy(id = 0), back.trip)
        assertEquals(points.map { it.copy(id = 0, tripId = 0) }, back.points)
        assertNull(back.points[1].bearingDeg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a newer backup format is refused`() {
        Backup.decode("""{"format": 99, "trips": []}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zip without backup json is refused`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("other.txt")); it.write(1) }
        Backup.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test fun `restore adds only trips that aren't there yet`() = runTest {
        val dao = object : TripDao {
            val trips = MutableStateFlow<List<TripRecord>>(emptyList())
            val track = mutableListOf<TrackPointRecord>()
            override fun observeAll() = trips
            override fun observe(id: Long) = trips.map { l -> l.find { it.id == id } }
            override suspend fun insert(trip: TripRecord): Long = (trips.value.size + 1L).also { trips.value += trip.copy(id = it) }
            override suspend fun insertPoints(points: List<TrackPointRecord>) { track += points }
            override suspend fun points(tripId: Long) = track.filter { it.tripId == tripId }
            override suspend fun setAddresses(id: Long, start: String?, end: String?) = Unit
            override suspend fun missingAddresses() = emptyList<TripRecord>()
            override suspend fun deleteTrip(id: Long) = Unit
            override suspend fun deletePoints(tripId: Long) = Unit
        }
        val backup = listOf(
            Backup.TripWithTrack(trip, points),
            Backup.TripWithTrack(trip.copy(startedAtMs = trip.startedAtMs + 86_400_000L), emptyList()),
        )
        assertEquals(2, Backup.restore(dao, backup))
        assertEquals(2, dao.points(1).size)
        assertEquals(0, Backup.restore(dao, backup))
        assertEquals(2, dao.trips.value.size)
    }
}
