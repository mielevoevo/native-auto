package com.nativeauto.history

import com.nativeauto.garage.BikeModel
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.DashKmPerL
import com.nativeauto.garage.FakeGarageDao
import com.nativeauto.garage.FuelMark
import com.nativeauto.garage.FuelType
import com.nativeauto.garage.GarageBackup
import com.nativeauto.garage.OdometerReading
import com.nativeauto.garage.ReadingKind
import com.nativeauto.garage.RefuelRecord
import com.nativeauto.garage.ServiceItem
import com.nativeauto.garage.ServiceRecord
import com.nativeauto.garage.TripLoad
import com.nativeauto.garage.learnConsumption
import com.nativeauto.garage.spanLoads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 4.7.2: one bike recorded by two phones. The other phone records it on weekdays, the main phone (which also has a bike of
 * its own) on weekends; the other phone's backups are imported on the main phone week after week and stack there.
 */
class TwoPhoneMergeTest {

    private class Trips : TripDao {
        val trips = MutableStateFlow<List<TripRecord>>(emptyList())
        val track = mutableListOf<TrackPointRecord>()
        override fun observeAll() = trips
        override fun observe(id: Long) = trips.map { l -> l.find { it.id == id } }
        override suspend fun insert(trip: TripRecord): Long = (trips.value.size + 1L).also { trips.value += trip.copy(id = it) }
        override suspend fun insertPoints(points: List<TrackPointRecord>) { track += points }
        override suspend fun points(tripId: Long) = track.filter { it.tripId == tripId }
        override suspend fun setAddresses(id: Long, start: String?, end: String?) = Unit
        override suspend fun missingAddresses() = emptyList<TripRecord>()
        override suspend fun deleteTrip(id: Long) { trips.value = trips.value.filterNot { it.id == id } }
        override suspend fun deletePoints(tripId: Long) { track.removeAll { it.tripId == tripId } }
    }

    private class Phone {
        val trips = Trips()
        val garage = FakeGarageDao()

        suspend fun backup(): ByteArray {
            val out = ByteArrayOutputStream()
            Backup.write(trips.observeAll().first().map { Backup.TripWithTrack(it, trips.points(it.id)) }, out, GarageBackup.collect(garage))
            return out.toByteArray()
        }

        /** As `TripFiles.import` does it. */
        suspend fun import(zip: ByteArray): Pair<Int, Int> {
            val c = Backup.readAll(ByteArrayInputStream(zip))
            val added = c.garage?.let { GarageBackup.restore(garage, it) } ?: 0
            return Backup.restore(trips, c.trips) to added
        }
    }

    private val day = 86_400_000L
    private val t0 = 1_762_650_000_000L

    private fun bike(id: String, name: String, active: Boolean) = BikeRecord(
        id = id, name = name, model = BikeModel.NMAX_GEN2.name, tankLitres = 7.1, reserveLitres = 1.4,
        fuelType = FuelType.PERTAMAX.name, startKmPerL = 36.0, active = active, lastUsedAtMs = t0, createdAtMs = t0,
    )

    private suspend fun Phone.ride(bikeId: String, atMs: Long, km: Double, altitude: Float) {
        trips.insertWithTrack(
            TripRecord(
                startedAtMs = atMs, endedAtMs = atMs + 3_600_000, durationMs = 3_600_000, movingMs = 3_000_000, stoppedMs = 600_000,
                distanceM = km * 1000, maxSpeedMps = 20f, startLat = -6.2, startLon = 106.8, endLat = -6.1, endLon = 106.8, track = "",
                bikeId = bikeId, loadIndex = 600.0, loadDistanceM = km * 1000, loadAero = km * 100_000, loadAccel = km * 200, loadClimbM = 10.0, loadStops = 5,
            ),
            List(3) { i -> TrackPointRecord(tripId = 0, timeMs = atMs + i * 1_000L, latitude = -6.2, longitude = 106.8, speedMps = 10f, bearingDeg = null, accuracyM = 5f, altitudeM = altitude + i, altitudeAccuracyM = 3f) },
        )
    }

    private suspend fun Phone.fill(bikeId: String, atMs: Long, odo: Double, litres: Double) {
        garage.insertRefuel(
            RefuelRecord(bikeId = bikeId, timeMs = atMs, odometerKm = odo, litres = litres, pricePerL = 15_950.0, totalRp = litres * 15_950, full = true, fuelType = "PERTAMAX"),
        )
        garage.insertReading(OdometerReading(bikeId = bikeId, timeMs = atMs, km = odo, kind = ReadingKind.CORRECTION.name))
    }

    /** The other phone starts from zero with the shared bike; the main phone has its own bike and takes the shared one by import. */
    private suspend fun setUp(): Pair<Phone, Phone> {
        val main = Phone()
        main.garage.insertBike(bike("tcv", "TCV", true))
        main.ride("tcv", t0 - day, 12.0, 100f)
        val other = Phone()
        other.garage.insertBike(bike("krt", "KRT", true))
        other.garage.insertReading(OdometerReading(bikeId = "krt", timeMs = t0, km = 5_000.0, kind = ReadingKind.SET.name))
        other.ride("krt", t0 + 1_000, 5.0, 20f)
        assertEquals(1 to 2, main.import(other.backup())) // one trip; the bike and its reading
        return main to other
    }

    @Test fun `the other phone's bike arrives inactive, with its trips, and nothing goes the other way`() = runTest {
        val (main, other) = setUp()
        assertEquals(listOf("tcv"), main.garage.allBikes().filter { it.active }.map { it.id })
        assertEquals(listOf("tcv", "krt"), main.trips.trips.value.map { it.bikeId })
        assertEquals(listOf("krt"), other.trips.trips.value.map { it.bikeId })
    }

    @Test fun `weekly imports stack, whatever the bike is called, and the same file twice adds nothing`() = runTest {
        val (main, other) = setUp()
        // Renamed on both sides: bikes match by id.
        main.garage.updateBike(main.garage.bike("krt")!!.copy(name = "Work bike"))
        other.garage.updateBike(other.garage.bike("krt")!!.copy(name = "Motor"))
        repeat(3) { week ->
            val monday = t0 + (1 + 7 * week) * day
            repeat(5) { other.ride("krt", monday + it * day, 18.0, 20f) }
            main.ride("krt", monday + 5 * day, 30.0, 50f)
            val zip = other.backup()
            assertEquals("week $week", 5 to 0, main.import(zip))
            assertEquals(0 to 0, main.import(zip))
        }
        val krt = main.trips.trips.value.filter { it.bikeId == "krt" }
        assertEquals(1 + 3 * 6, krt.size)
        assertEquals(5.0 + 3 * (5 * 18.0 + 30.0), krt.sumOf { it.distanceM } / 1000, 1e-6)
        assertEquals("Work bike", main.garage.bike("krt")!!.name)
        // Altitude came with every ride, from either phone.
        assertTrue(krt.all { t -> main.trips.points(t.id).let { p -> p.size == 3 && p.all { it.altitudeM != null && it.altitudeAccuracyM == 3f } } })
    }

    @Test fun `a tank ridden half on each phone is one measured tank with both phones' riding`() = runTest {
        val (main, other) = setUp()
        other.fill("krt", t0 + day, 5_005.0, 5.0)
        repeat(5) { other.ride("krt", t0 + 2 * day + it * day, 20.0, 20f) } // 100 km on the other phone
        main.ride("krt", t0 + 8 * day, 98.0, 50f) // 98 km on the main phone
        main.garage.insertReading(OdometerReading(bikeId = "krt", timeMs = t0 + 8 * day - 1, km = 5_105.0, kind = ReadingKind.UNTRACKED.name, awayKm = 100.0))
        main.fill("krt", t0 + 9 * day, 5_205.0, 5.0) // 200 km on 5 L

        main.import(other.backup())
        val away = main.garage.allReadings().filter { it.kind == ReadingKind.AWAY.name }.map { it.timeMs }
        val c = learnConsumption(36.0, main.garage.allRefuels().filter { it.bikeId == "krt" }, away)
        val tank = c.measures.single()
        assertTrue(tank.used)
        assertEquals(40.0, tank.kmPerL, 1e-9)
        val loads = main.trips.trips.value.filter { it.bikeId == "krt" }
            .map { TripLoad(it.endedAtMs, it.loadDistanceM!!, it.loadAero!!, it.loadAccel!!, it.loadClimbM!!, it.loadStops!!) }
        assertEquals("both phones' trips cover the tank", 1, spanLoads(c.measures, loads).size)
        // The odometer is the last reading by time, whichever phone it was entered on.
        assertEquals(5_205.0, main.garage.lastReading("krt")!!.km, 0.0)
    }

    @Test fun `gauge readings, dash figures and a service done on the other phone come along`() = runTest {
        val (main, other) = setUp()
        other.garage.insertService(ServiceItem(bikeId = "krt", name = "Engine oil", kind = "DISTANCE", intervalKm = 2_000.0, lastDoneMs = t0, lastDoneKm = 5_000.0, createdAtMs = t0))
        main.import(other.backup())
        val oil = main.garage.allServices().single()
        // Then: a gauge reading, a dash figure, and the oil changed, all on the other phone.
        other.garage.insertFuelMark(FuelMark(bikeId = "krt", timeMs = t0 + day, odometerKm = 5_020.0, bars = 4, ofBars = 8, litres = 3.1))
        other.garage.insertDashKmPerL(DashKmPerL(bikeId = "krt", timeMs = t0 + day, kmPerL = 41.5, tripKm = 18.0))
        val there = other.garage.allServices().single()
        other.garage.markDone(
            there.copy(lastDoneMs = t0 + 2 * day, lastDoneKm = 5_100.0),
            ServiceRecord(itemId = there.id, bikeId = "krt", itemName = "Engine oil", timeMs = t0 + 2 * day, odometerKm = 5_100.0),
        )
        val zip = other.backup()
        assertEquals(0 to 3, main.import(zip)) // the mark, the dash figure, the service record
        assertEquals(3.1, main.garage.allFuelMarks().single().litres, 0.0)
        assertEquals(41.5, main.garage.allDashKmPerL().single().kmPerL, 0.0)
        assertEquals(oil.copy(lastDoneMs = t0 + 2 * day, lastDoneKm = 5_100.0), main.garage.allServices().single())
        assertEquals(oil.id, main.garage.allServiceRecords().single().itemId)
        assertEquals(0 to 0, main.import(zip))

        // Done again here, later: an older backup doesn't take that back.
        main.garage.updateService(main.garage.allServices().single().copy(lastDoneMs = t0 + 5 * day, lastDoneKm = 5_400.0))
        main.import(zip)
        assertEquals(5_400.0, main.garage.allServices().single().lastDoneKm!!, 0.0)
    }

    @Test fun `a backup from before 4_7_2 still imports, without altitude`() = runTest {
        val main = Phone()
        val old = """{"format":2,"app":"Native Auto","garage":{"bikes":[{"id":"krt","name":"KRT"}]},"trips":[{"startedAtMs":$t0,"endedAtMs":${t0 + 1000},
            "durationMs":1000,"movingMs":1000,"distanceM":500.0,"maxSpeedMps":9.0,"startLat":1.0,"startLon":2.0,"endLat":1.0,"endLon":2.0,"track":"","bikeId":"krt",
            "points":[[$t0,1.0,2.0,9.0,null,4.0]]}]}"""
        val c = Backup.decodeAll(old)
        GarageBackup.restore(main.garage, c.garage!!)
        assertEquals(1, Backup.restore(main.trips, c.trips))
        val p = main.trips.points(1).single()
        assertNull(p.altitudeM)
        assertNull(p.altitudeAccuracyM)
        assertTrue(main.garage.allFuelMarks().isEmpty())
    }
}
