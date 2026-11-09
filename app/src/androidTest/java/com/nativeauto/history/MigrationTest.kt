package com.nativeauto.history

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 9c: a version-1 database (as Phase 8a shipped it, written here by hand) opens under v2 with
 * its trips intact, stopped time backfilled, and the full-track table working.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @Before @After fun clean() {
        context.deleteDatabase(name)
    }

    @Test fun v1TripsSurviveAndGainTheNewColumns() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAtMs INTEGER NOT NULL, " +
                    "endedAtMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, movingMs INTEGER NOT NULL, " +
                    "distanceM REAL NOT NULL, maxSpeedMps REAL NOT NULL, startLat REAL NOT NULL, startLon REAL NOT NULL, " +
                    "endLat REAL NOT NULL, endLon REAL NOT NULL, startAddress TEXT, endAddress TEXT, track TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE recent_places (`key` TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, detail TEXT, " +
                    "latitude REAL NOT NULL, longitude REAL NOT NULL, usedAtMs INTEGER NOT NULL)",
            )
            db.insert(
                "trips", null,
                ContentValues().apply {
                    put("startedAtMs", 1_000L); put("endedAtMs", 601_000L)
                    put("durationMs", 600_000L); put("movingMs", 450_000L)
                    put("distanceM", 5_000.0); put("maxSpeedMps", 20f)
                    put("startLat", -7.8); put("startLon", 110.4); put("endLat", -7.7); put("endLon", 110.4)
                    put("startAddress", "Jalan A"); put("track", "abc")
                },
            )
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            val dao = room.trips()
            val old = dao.observeAll().first().single()
            assertEquals(5_000.0, old.distanceM, 0.0)
            assertEquals("Jalan A", old.startAddress)
            assertEquals(150_000L, old.stoppedMs)
            assertNull(old.bikeId)
            assertTrue("old trips have no full track", dao.points(old.id).isEmpty())

            val id = dao.insertWithTrack(
                old.copy(id = 0, bikeId = "Gen 1"),
                List(3) { i -> TrackPointRecord(tripId = 0, timeMs = i * 1_000L, latitude = -7.8, longitude = 110.4, speedMps = 10f, bearingDeg = null, accuracyM = 5f) },
            )
            assertEquals(3, dao.points(id).size)
            assertEquals("Gen 1", dao.observe(id).first()?.bikeId)
            dao.delete(id)
            assertTrue(dao.points(id).isEmpty())
            assertNull(dao.observe(id).first())
        } finally {
            room.close()
        }
    }
}
