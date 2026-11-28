package com.nativeauto.garage

import com.nativeauto.history.Backup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 11c: the Garage in the ZIP backup: a round trip, merging, and format 1 files. */
class GarageBackupTest {

    private fun bike(id: String, name: String, active: Boolean) = BikeRecord(
        id = id, name = name, model = BikeModel.NMAX_GEN1.name, tankLitres = 6.6, reserveLitres = 1.3,
        fuelType = FuelType.PERTAMAX.name, startKmPerL = 36.0, odoFactor = 1.02, active = active, lastUsedAtMs = 5, createdAtMs = 1, lowFuelKm = 45, gaugeBars = 10, odoFactorManual = 0.985, cautionPct = 20, type = BikeType.UNDERBONE.name,
    )

    private val data = GarageData(
        bikes = listOf(bike("b1", "Gen 1", true), bike("b2", "Gen 2", false)),
        readings = listOf(
            OdometerReading(id = 7, bikeId = "b1", timeMs = 10, km = 12_000.0, kind = ReadingKind.SET.name),
            OdometerReading(id = 8, bikeId = "b1", timeMs = 20, km = 12_100.0, kind = ReadingKind.UNTRACKED.name, awayKm = 42.0, tripOffsetM = 150.0),
        ),
        refuels = listOf(
            RefuelRecord(id = 3, bikeId = "b1", timeMs = 15, odometerKm = 12_050.0, litres = 4.0, pricePerL = 15_900.0, totalRp = 63_600.0, full = true, fuelType = "PERTAMAX", learn = false),
        ),
        prices = listOf(FuelPrice("PERTAMAX", 15_900.0, 15)),
        services = listOf(
            ServiceItem(id = 40, bikeId = "b1", name = "Engine oil", kind = "DISTANCE", intervalKm = 2_000.0, intervalMonths = 3, lastDoneMs = 10, lastDoneKm = 12_000.0, createdAtMs = 9),
            ServiceItem(id = 41, bikeId = "b2", name = "Vehicle tax", kind = "DATE", intervalMonths = 12, dueDateMs = 99, lastDoneMs = 0, createdAtMs = 9),
        ),
        serviceRecords = listOf(
            ServiceRecord(id = 1, itemId = 40, bikeId = "b1", itemName = "Engine oil", timeMs = 10, odometerKm = 12_000.0, costRp = 55_000.0, note = "Yamalube"),
        ),
        fuelMarks = listOf(FuelMark(id = 4, bikeId = "b1", timeMs = 16, odometerKm = 12_060.0, bars = 3, ofBars = 6, litres = 2.75)),
        dashKmPerL = listOf(DashKmPerL(id = 5, bikeId = "b2", timeMs = 17, kmPerL = 38.4, tripKm = 12.5)),
        customFuels = listOf(CustomFuel(customFuelKey("Shell V-Power"), "Shell V-Power", 3, retired = true)),
    )

    @Test fun `the garage survives a round trip through the ZIP`() {
        val out = ByteArrayOutputStream()
        Backup.write(emptyList(), out, data)
        val back = Backup.readAll(ByteArrayInputStream(out.toByteArray())).garage!!
        assertEquals(data.bikes, back.bikes)
        assertEquals(data.readings.map { it.copy(id = 0) }, back.readings)
        assertEquals(data.refuels.map { it.copy(id = 0) }, back.refuels)
        assertEquals(data.prices, back.prices)
        assertEquals(data.services, back.services)
        assertEquals(data.serviceRecords.map { it.copy(id = 0) }, back.serviceRecords)
        assertEquals(data.fuelMarks.map { it.copy(id = 0) }, back.fuelMarks)
        assertEquals(data.dashKmPerL.map { it.copy(id = 0) }, back.dashKmPerL)
        assertEquals(data.customFuels, back.customFuels)
    }

    @Test fun `a format 1 backup has no garage and still reads`() {
        val contents = Backup.decodeAll("""{"format": 1, "trips": []}""")
        assertTrue(contents.trips.isEmpty())
        assertNull(contents.garage)
    }

    @Test fun `restoring merges, keeps one active bike, and twice changes nothing`() = runTest {
        val dao = FakeGarageDao()
        // Here, Gen 2 is already being ridden.
        dao.insertBike(bike("b2", "Gen 2", true))
        dao.upsertPrice(FuelPrice("PERTAMAX", 16_000.0, 99))

        val added = GarageBackup.restore(dao, data)
        // Gen 1, 2 readings, 1 fill-up, 2 items, 1 record, 1 gauge reading, 1 dash figure, 1 fuel.
        assertEquals(10, added)
        assertEquals(listOf("b2"), dao.bikes.value.filter { it.active }.map { it.id })
        assertEquals(1.02, dao.bike("b1")!!.odoFactor, 0.0)
        // The newer local price stays.
        assertEquals(16_000.0, dao.allPrices().single().pricePerL, 0.0)
        // The record follows its item to its new id.
        val oil = dao.allServices().single { it.name == "Engine oil" }
        assertEquals(oil.id, dao.allServiceRecords().single().itemId)

        assertEquals(0, GarageBackup.restore(dao, data))
        assertEquals(2, dao.allBikes().size)
    }

    @Test fun `restoring into an empty garage keeps the backup's active bike`() = runTest {
        val dao = FakeGarageDao()
        GarageBackup.restore(dao, data)
        assertEquals(listOf("b1"), dao.bikes.value.filter { it.active }.map { it.id })
        assertEquals(15_900.0, dao.allPrices().single().pricePerL, 0.0)
    }
}
