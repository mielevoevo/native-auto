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

    @Test fun `altitude travels in the backup, only on the points that have one, and stays out of the gpx`() {
        val high = listOf(points[0].copy(altitudeM = 123.4f, altitudeAccuracyM = 2.5f), points[1].copy(altitudeM = -3.1f), points[1])
        val json = Backup.encode(listOf(Backup.TripWithTrack(trip, high)))
        assertEquals(high.map { it.copy(id = 0, tripId = 0) }, Backup.decode(json).single().points)
        // A point without altitude is written as before: six values.
        assertTrue(json.contains("[1762650001000,-7.79991,110.37008,11.0,null,5.0]"))
        // The height is above the ellipsoid, which GPX's <ele> isn't.
        val gpx = buildGpx(trip, high)
        assertFalse(gpx.contains("<ele>"))
        // 4.8: it goes along labeled as raw, with its accuracy where there is one.
        assertTrue(gpx.contains("<rawaltitude>123.40</rawaltitude><rawaltitudeaccuracy>2.50</rawaltitudeaccuracy></extensions>"))
        assertTrue(gpx.contains("<rawaltitude>-3.10</rawaltitude></extensions>"))
        assertEquals(2, Regex("<rawaltitude>").findAll(gpx).count())
    }

    @Test fun `the riding load travels in the backup, and a trip without it reads back without it`() {
        val loaded = trip.copy(loadIndex = 412.5, loadDistanceM = 600.0, loadAero = 60_000.0, loadAccel = 12.5, loadClimbM = 3.0, loadStops = 2, loadCrawlS = 90.5, loadCrawlM = 210.0, loadCrawlClimbM = 4.0)
        val back = Backup.decode(Backup.encode(listOf(Backup.TripWithTrack(loaded, points), Backup.TripWithTrack(trip, points))))
        assertEquals(loaded.copy(id = 0), back[0].trip)
        assertNull(back[1].trip.loadIndex)
        assertNull(back[1].trip.loadDistanceM)
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
            override suspend fun tripsWithoutEffort() = trips.value.filter { t -> t.effortM == null && track.any { it.tripId == t.id } }.map { it.id }
            override suspend fun setEffort(id: Long, distanceM: Double, flatJ: Double, slopeJ: Double, slopeM: Double, slowS: Double) {
                trips.value = trips.value.map {
                    if (it.id == id) it.copy(effortM = distanceM, effortFlatJ = flatJ, effortSlopeJ = slopeJ, effortSlopeM = slopeM, effortSlowS = slowS) else it
                }
            }
        }
        val backup = listOf(
            Backup.TripWithTrack(trip, points),
            Backup.TripWithTrack(trip.copy(startedAtMs = trip.startedAtMs + 86_400_000L), emptyList()),
        )
        assertEquals(2, Backup.restore(dao, backup))
        assertEquals(2, dao.points(1).size)
        assertEquals(0, Backup.restore(dao, backup))
        assertEquals(2, dao.trips.value.size)
        // 4.8: the effort of what came in is worked out from its track; a trip without one stays without.
        dao.fillMissingEffort()
        assertEquals(13.3, dao.trips.value[0].effortM!!, 0.5)
        assertNull(dao.trips.value[1].effortM)
    }

    // ---- 4.8: the password ----

    private fun protectedFile(scope: BackupScope = BackupScope.EVERYTHING, password: String = "gas pol 2026"): ByteArray {
        val out = ByteArrayOutputStream()
        val trips = if (scope == BackupScope.EVERYTHING) listOf(Backup.TripWithTrack(trip, points)) else emptyList()
        Backup.write(trips, out, null, password.toCharArray(), scope)
        return out.toByteArray()
    }

    @Test fun `a protected backup reads back with its password and names no trip outside it`() {
        val file = protectedFile()
        assertTrue(Backup.isProtected(file))
        val back = Backup.readAll(file, "gas pol 2026".toCharArray()).trips.single()
        assertEquals(trip.copy(id = 0), back.trip)
        assertEquals(points.map { it.copy(id = 0, tripId = 0) }, back.points)
        val text = file.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains(gpxFileName(trip)))
        assertFalse(text.contains("Malioboro"))
        assertTrue(text.contains("gpx.zip"))
    }

    @Test fun `without the password, or with another one, nothing is read`() {
        val file = protectedFile()
        val none = runCatching { Backup.readAll(file, null) }.exceptionOrNull()
        assertTrue(none is Backup.PasswordException && !none.wrong)
        listOf("gas pol 2025", "x", "GAS POL 2026").forEach {
            val wrong = runCatching { Backup.readAll(file, it.toCharArray()) }.exceptionOrNull()
            assertTrue("$it: $wrong", wrong is Backup.PasswordException && wrong.wrong)
        }
    }

    @Test fun `a changed protected file isn't imported`() {
        val file = protectedFile()
        file[file.size / 3] = (file[file.size / 3] + 1).toByte()
        assertTrue(runCatching { Backup.readAll(file, "gas pol 2026".toCharArray()) }.isFailure)
    }

    @Test fun `an unprotected backup needs no password and ignores one`() {
        val out = ByteArrayOutputStream()
        Backup.write(listOf(Backup.TripWithTrack(trip, points)), out)
        assertFalse(Backup.isProtected(out.toByteArray()))
        assertEquals(1, Backup.readAll(out.toByteArray(), null).trips.size)
        assertEquals(1, Backup.readAll(out.toByteArray(), "any".toCharArray()).trips.size)
    }

    @Test fun `a garage only backup holds no trips and says so`() {
        val json = Backup.encode(emptyList(), null, BackupScope.GARAGE)
        assertTrue(json.contains("\"scope\":\"garage\""))
        assertTrue(Backup.decode(json).isEmpty())
        assertFalse(Backup.encode(emptyList()).contains("scope"))
        val file = protectedFile(BackupScope.GARAGE)
        assertFalse(file.toString(Charsets.ISO_8859_1).contains("gpx"))
        assertTrue(Backup.readAll(file, "gas pol 2026".toCharArray()).trips.isEmpty())
    }
}
