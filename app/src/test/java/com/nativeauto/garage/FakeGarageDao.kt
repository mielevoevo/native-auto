package com.nativeauto.garage

import com.nativeauto.history.TripRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The Garage's DAO in memory, with the same semantics as the SQL (11a; services and backup reads 11c). */
internal class FakeGarageDao : GarageDao {
    val bikes = MutableStateFlow<List<BikeRecord>>(emptyList())
    val readings = MutableStateFlow<List<OdometerReading>>(emptyList())
    val trips = MutableStateFlow<List<TripRecord>>(emptyList())

    override fun observeBikes() = bikes.map { l -> l.sortedBy { it.createdAtMs } }
    override fun observeActive() = bikes.map { l -> l.firstOrNull { it.active } }
    override suspend fun activeBike() = bikes.value.firstOrNull { it.active }
    override suspend fun bike(id: String) = bikes.value.firstOrNull { it.id == id }
    override suspend fun bikesByUse() = bikes.value.sortedByDescending { it.lastUsedAtMs }
    override suspend fun insertBike(bike: BikeRecord) { bikes.value += bike }
    override suspend fun updateBike(bike: BikeRecord) { bikes.value = bikes.value.map { if (it.id == bike.id) bike else it } }
    override suspend fun setFactor(id: String, factor: Double) {
        bikes.value = bikes.value.map { if (it.id == id) it.copy(odoFactor = factor) else it }
    }
    override suspend fun deactivateAll(nowMs: Long) {
        bikes.value = bikes.value.map { if (it.active) it.copy(active = false, lastUsedAtMs = nowMs) else it }
    }
    override suspend fun markActive(id: String) { bikes.value = bikes.value.map { if (it.id == id) it.copy(active = true) else it } }
    override suspend fun deleteBikeRow(id: String) { bikes.value = bikes.value.filterNot { it.id == id } }
    override suspend fun deleteReadings(id: String) { readings.value = readings.value.filterNot { it.bikeId == id } }
    override suspend fun untagTrips(id: String) { trips.value = trips.value.map { if (it.bikeId == id) it.copy(bikeId = null) else it } }
    override suspend fun tagUntaggedTrips(id: String) { trips.value = trips.value.map { if (it.bikeId == null) it.copy(bikeId = id) else it } }
    override suspend fun insertReading(reading: OdometerReading): Long {
        val id = readings.value.size + 1L
        readings.value += reading.copy(id = id)
        return id
    }
    private fun sorted(bikeId: String) = readings.value.filter { it.bikeId == bikeId }.sortedWith(compareByDescending<OdometerReading> { it.timeMs }.thenByDescending { it.id })
    override fun observeReadings(bikeId: String): Flow<List<OdometerReading>> = readings.map { sorted(bikeId) }
    override fun observeLastReading(bikeId: String) = readings.map { sorted(bikeId).firstOrNull() }
    override suspend fun lastReading(bikeId: String) = sorted(bikeId).firstOrNull()
    private fun meters(list: List<TripRecord>, bikeId: String, sinceMs: Long, offsetM: Double) =
        list.filter { it.bikeId == bikeId && it.endedAtMs > sinceMs }
            .sumOf { if (it.startedAtMs < sinceMs) maxOf(it.distanceM - offsetM, 0.0) else it.distanceM }
    override fun observeMetersSince(bikeId: String, sinceMs: Long, offsetM: Double) = trips.map { meters(it, bikeId, sinceMs, offsetM) }
    override suspend fun metersSince(bikeId: String, sinceMs: Long, offsetM: Double) = meters(trips.value, bikeId, sinceMs, offsetM)
    override suspend fun lastTrip(bikeId: String) = trips.value.filter { it.bikeId == bikeId }.maxByOrNull { it.endedAtMs }
    override suspend fun deleteAllBikes() { bikes.value = emptyList() }
    val refuels = MutableStateFlow<List<RefuelRecord>>(emptyList())
    val prices = MutableStateFlow<List<FuelPrice>>(emptyList())
    override suspend fun deleteRefuels(id: String) { refuels.value = refuels.value.filterNot { it.bikeId == id } }
    override fun observeRefuels(bikeId: String) =
        refuels.map { l -> l.filter { it.bikeId == bikeId }.sortedWith(compareByDescending<RefuelRecord> { it.timeMs }.thenByDescending { it.id }) }
    override suspend fun insertRefuel(refuel: RefuelRecord): Long {
        val id = refuels.value.size + 1L
        refuels.value += refuel.copy(id = id)
        return id
    }
    override suspend fun deleteRefuel(id: Long) { refuels.value = refuels.value.filterNot { it.id == id } }
    override fun observePrices() = prices
    override suspend fun upsertPrice(price: FuelPrice) { prices.value = prices.value.filterNot { it.fuelType == price.fuelType } + price }
    override suspend fun deleteAllRefuels() { refuels.value = emptyList() }
    override suspend fun deleteAllPrices() { prices.value = emptyList() }
    override suspend fun deleteAllReadings() { readings.value = emptyList() }
    // ---- 11c ----
    val services = MutableStateFlow<List<ServiceItem>>(emptyList())
    val serviceRecords = MutableStateFlow<List<ServiceRecord>>(emptyList())
    override suspend fun deleteServices(id: String) { services.value = services.value.filterNot { it.bikeId == id } }
    override suspend fun deleteServiceRecords(id: String) { serviceRecords.value = serviceRecords.value.filterNot { it.bikeId == id } }
    override fun observeServices(bikeId: String) = services.map { l -> l.filter { it.bikeId == bikeId }.sortedWith(compareBy({ it.createdAtMs }, { it.id })) }
    override suspend fun service(id: Long) = services.value.firstOrNull { it.id == id }
    override suspend fun insertService(item: ServiceItem): Long {
        val id = (services.value.maxOfOrNull { it.id } ?: 0) + 1
        services.value += item.copy(id = id)
        return id
    }
    override suspend fun updateService(item: ServiceItem) { services.value = services.value.map { if (it.id == item.id) item else it } }
    override suspend fun deleteService(id: Long) { services.value = services.value.filterNot { it.id == id } }
    override fun observeServiceRecords(bikeId: String) =
        serviceRecords.map { l -> l.filter { it.bikeId == bikeId }.sortedWith(compareByDescending<ServiceRecord> { it.timeMs }.thenByDescending { it.id }) }
    override suspend fun insertServiceRecord(record: ServiceRecord): Long {
        val id = serviceRecords.value.size + 1L
        serviceRecords.value += record.copy(id = id)
        return id
    }
    override suspend fun allBikes() = bikes.value.sortedBy { it.createdAtMs }
    override suspend fun allReadings() = readings.value.sortedWith(compareBy({ it.timeMs }, { it.id }))
    override suspend fun allRefuels() = refuels.value.sortedWith(compareBy({ it.timeMs }, { it.id }))
    override suspend fun allPrices() = prices.value
    override suspend fun allServices() = services.value.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
    override suspend fun allServiceRecords() = serviceRecords.value.sortedWith(compareBy({ it.timeMs }, { it.id }))
    override suspend fun deleteAllServices() { services.value = emptyList() }
    override suspend fun deleteAllServiceRecords() { serviceRecords.value = emptyList() }
}
