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
 * 9c: a version-1 database (as Phase 8a shipped it, written here by hand) opens under the current
 * version with its trips intact, stopped time backfilled, and the full-track table working.
 * 11a: through v2 → v3, the Garage's tables are created and match Room's schema (Room checks).
 * 11b: through v3 → v4, the refuel log, fuel prices and the trip-recovery checkpoint.
 * 11c: through v4 → v5, service items and their history.
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

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, com.nativeauto.history.MIGRATION_6_7, com.nativeauto.history.MIGRATION_7_8, MIGRATION_8_9).build()
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

            // v3: bikes and readings; the first bike can adopt the old trip.
            val garage = room.garage()
            garage.insertBike(
                com.nativeauto.garage.BikeRecord(
                    id = "b1", name = "Gen 1", model = "NMAX_GEN1", tankLitres = 6.6, reserveLitres = 1.3,
                    fuelType = "PERTAMAX", startKmPerL = 36.0, active = true, lastUsedAtMs = 0, createdAtMs = 0,
                ),
            )
            garage.tagUntaggedTrips("b1")
            assertEquals("b1", dao.observe(old.id).first()?.bikeId)
            garage.insertReading(com.nativeauto.garage.OdometerReading(bikeId = "b1", timeMs = 0, km = 12_000.0, kind = "SET"))
            assertEquals(5_000.0, garage.metersSince("b1", sinceMs = 0, offsetM = 0.0), 0.0)
            assertEquals(12_000.0, garage.lastReading("b1")!!.km, 0.0)

            // v4: a fill-up, the pump price, and the trip checkpoint.
            garage.insertRefuel(
                com.nativeauto.garage.RefuelRecord(
                    bikeId = "b1", timeMs = 1, odometerKm = 12_000.0, litres = 5.0, pricePerL = 12_500.0,
                    totalRp = 62_500.0, full = true, fuelType = "PERTAMAX",
                ),
            )
            assertEquals(5.0, garage.observeRefuels("b1").first().single().litres, 0.0)
            garage.upsertPrice(com.nativeauto.garage.FuelPrice("PERTAMAX", 12_900.0, 1))
            garage.upsertPrice(com.nativeauto.garage.FuelPrice("PERTAMAX", 13_000.0, 2))
            assertEquals(13_000.0, garage.observePrices().first().single().pricePerL, 0.0)
            // v8 (4.6.1): the low-fuel warning.
            assertEquals(30, garage.bike("b1")!!.lowFuelKm)
            garage.updateBike(garage.bike("b1")!!.copy(lowFuelKm = 50))
            assertEquals(50, garage.bike("b1")!!.lowFuelKm)
            // v9 (4.7): the gauge's bars and the own odometer correction.
            assertEquals(8, garage.bike("b1")!!.gaugeBars)
            assertNull(garage.bike("b1")!!.odoFactorManual)
            garage.updateBike(garage.bike("b1")!!.copy(gaugeBars = 10, odoFactorManual = 1.02))
            assertEquals(1.02, garage.bike("b1")!!.factor, 0.0)
            garage.deleteBike("b1")
            assertTrue(garage.observeRefuels("b1").first().isEmpty())
            // v5: a service item, marked done; deleting its bike takes it and its history.
            val itemId = garage.insertService(
                com.nativeauto.garage.ServiceItem(
                    bikeId = "b2", name = "Engine oil", kind = "DISTANCE", intervalKm = 2_000.0, lastDoneMs = 0,
                    lastDoneKm = 12_000.0, createdAtMs = 0,
                ),
            )
            val item = garage.service(itemId)!!
            garage.markDone(
                item.copy(lastDoneKm = 14_000.0),
                com.nativeauto.garage.ServiceRecord(itemId = itemId, bikeId = "b2", itemName = "Engine oil", timeMs = 5, odometerKm = 14_000.0),
            )
            assertEquals(14_000.0, garage.service(itemId)!!.lastDoneKm!!, 0.0)
            assertEquals(1, garage.observeServiceRecords("b2").first().size)
            garage.deleteBike("b2")
            assertTrue(garage.allServices().isEmpty())
            assertTrue(garage.allServiceRecords().isEmpty())
            val live = room.liveTrip()
            val row = LiveTripRecord(
                savedAtMs = 1, savedAtNanos = 2, startedAtNanos = 0, distanceM = 10.0, maxSpeedMps = 5f, movingNanos = 1,
                pausedNanos = 0, pausedAtNanos = null, lastMovingAtNanos = null, autoPaused = false, paused = false,
            )
            live.checkpoint(row, listOf(LivePointRecord(timestampNanos = 1, latitude = 1.0, longitude = 2.0, speedMps = 3f, speedAccuracyMps = null, accuracyM = 4f, bearingDeg = null)), replace = true)
            live.checkpoint(row.copy(distanceM = 20.0), emptyList(), replace = false)
            assertEquals(20.0, live.trip()!!.distanceM, 0.0)
            assertEquals(1, live.points().size)
            live.clear()
            assertNull(live.trip())
            // v7 (4.3d): the old trip has no riding load; a new one carries it, and the bike's load query reads it.
            assertNull(dao.observe(old.id).first()?.loadIndex)
            val loaded = dao.insertWithTrack(
                old.copy(id = 0, bikeId = "b3", endedAtMs = 99, loadIndex = 400.0, loadDistanceM = 5_000.0, loadAero = 500_000.0, loadAccel = 30.0),
                emptyList(),
            )
            assertEquals(400.0, dao.observe(loaded).first()!!.loadIndex!!, 0.0)
            val loads = garage.observeTripLoads("b3").first()
            assertEquals(1, loads.size)
            assertEquals(0.0, loads.single().climbM, 0.0) // null climb reads as 0
            assertEquals(5_000.0, loads.single().distanceM, 0.0)
            assertNull(dao.observe(old.id).first()!!.loadCrawlS)
        } finally {
            room.close()
        }
    }

    /** 4.7: a v8 garage as 4.6.x leaves it: bikes keep their gauge, readings, fill-ups and learned figures. */
    @Test fun v8BikesKeepTheirGaugeAndData() = runBlocking {
        val v8 = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        v8.openHelper.writableDatabase.let { db ->
            // Back to v8's shape: the columns v9 adds are taken away again.
            db.execSQL("ALTER TABLE bikes DROP COLUMN gaugeBars")
            db.execSQL("ALTER TABLE bikes DROP COLUMN odoFactorManual")
            listOf("loadCrawlS", "loadCrawlM", "loadCrawlClimbM").forEach { db.execSQL("ALTER TABLE trips DROP COLUMN $it") }
            listOf("g1" to "NMAX_GEN1", "g2" to "NMAX_GEN2", "o" to "OTHER").forEach { (id, model) ->
                db.execSQL(
                    "INSERT INTO bikes (id, name, type, model, tankLitres, reserveLitres, fuelType, startKmPerL, odoFactor, active, lastUsedAtMs, createdAtMs, lowFuelKm) " +
                        "VALUES ('$id', '$id', 'SCOOTER_CVT', '$model', 6.6, 1.3, 'PERTAMAX', 36.0, 1.015, 0, 5, 1, 40)",
                )
            }
            db.execSQL("INSERT INTO odometer_readings (bikeId, timeMs, km, kind, awayKm, tripOffsetM) VALUES ('g1', 1, 12000.0, 'SET', 0, 0)")
            db.execSQL(
                "INSERT INTO refuels (bikeId, timeMs, odometerKm, litres, pricePerL, totalRp, full, fuelType) VALUES ('g1', 2, 12000.0, 5.0, 10000.0, 50000.0, 1, 'PERTAMAX')",
            )
            db.execSQL("PRAGMA user_version = 8")
        }
        v8.close()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_8_9).build()
        try {
            val garage = room.garage()
            assertEquals(listOf(6, 8, 8), listOf("g1", "g2", "o").map { garage.bike(it)!!.gaugeBars })
            val g1 = garage.bike("g1")!!
            assertEquals(1.015, g1.factor, 0.0)
            assertNull(g1.odoFactorManual)
            assertEquals(40, g1.lowFuelKm)
            assertEquals(12_000.0, garage.lastReading("g1")!!.km, 0.0)
            assertEquals(5.0, garage.observeRefuels("g1").first().single().litres, 0.0)
        } finally {
            room.close()
        }
    }
}
