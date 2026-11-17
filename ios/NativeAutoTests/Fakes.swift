import Foundation
@testable import NativeAuto

/// Port of Android `FakeGarageDao` plus the fake `TripDao`: one in-memory store for the history and the Garage, with
/// the same semantics as the SQL (ids count up, a deleted bike untags its trips).
final class InMemoryStore: TripDao, GarageDao {
    var trips: [TripRecord] = []
    var track: [TrackPointRecord] = []
    var bikes: [BikeRecord] = []
    var readings: [OdometerReading] = []
    var refuelRows: [RefuelRecord] = []
    var prices: [FuelPrice] = []
    var marks: [FuelMark] = []
    var dash: [DashKmPerL] = []
    var serviceItems: [ServiceItem] = []
    var records: [ServiceRecord] = []
    private var nextId: Int64 = 0
    private func id() -> Int64 { nextId += 1; return nextId }

    // TripDao
    func allTrips() -> [TripRecord] { trips.sorted { $0.startedAtMs > $1.startedAtMs } }
    func trip(_ id: Int64) -> TripRecord? { trips.first { $0.id == id } }
    func insertWithTrack(_ trip: TripRecord, _ points: [TrackPointRecord]) -> Int64 {
        let new = Int64(trips.count + 1)
        var t = trip; t.id = new
        trips.append(t)
        track += points.map { var p = $0; p.tripId = new; return p }
        return new
    }
    func points(_ tripId: Int64) -> [TrackPointRecord] { track.filter { $0.tripId == tripId } }
    func setAddresses(_ id: Int64, start: String?, end: String?) {
        trips = trips.map { t in var x = t; if t.id == id { x.startAddress = start; x.endAddress = end }; return x }
    }
    func delete(_ id: Int64) { trips.removeAll { $0.id == id }; track.removeAll { $0.tripId == id } }

    // GarageDao
    func allBikes() -> [BikeRecord] { bikes.sorted { $0.createdAtMs < $1.createdAtMs } }
    func insertBike(_ bike: BikeRecord) { bikes.append(bike) }
    func updateBike(_ bike: BikeRecord) { bikes = bikes.map { $0.id == bike.id ? bike : $0 } }
    func deleteBike(_ id: String) {
        readings.removeAll { $0.bikeId == id }
        refuelRows.removeAll { $0.bikeId == id }
        marks.removeAll { $0.bikeId == id }
        dash.removeAll { $0.bikeId == id }
        serviceItems.removeAll { $0.bikeId == id }
        records.removeAll { $0.bikeId == id }
        trips = trips.map { t in var x = t; if t.bikeId == id { x.bikeId = nil }; return x }
        bikes.removeAll { $0.id == id }
    }
    func tagUntaggedTrips(_ bikeId: String) { trips = trips.map { t in var x = t; if t.bikeId == nil { x.bikeId = bikeId }; return x } }
    func trips(bikeId: String) -> [TripRecord] { trips.filter { $0.bikeId == bikeId } }
    func allReadings() -> [OdometerReading] { readings.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }
    func insertReading(_ r: OdometerReading) -> Int64 { var x = r; x.id = id(); readings.append(x); return x.id }
    func allRefuels() -> [RefuelRecord] { refuelRows.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }
    func insertRefuel(_ r: RefuelRecord) -> Int64 { var x = r; x.id = id(); refuelRows.append(x); return x.id }
    func deleteRefuel(_ id: Int64) { refuelRows.removeAll { $0.id == id } }
    func allPrices() -> [FuelPrice] { prices }
    func upsertPrice(_ p: FuelPrice) { prices.removeAll { $0.fuelType == p.fuelType }; prices.append(p) }
    func allFuelMarks() -> [FuelMark] { marks }
    func insertFuelMark(_ m: FuelMark) -> Int64 { var x = m; x.id = id(); marks.append(x); return x.id }
    func allDashKmPerL() -> [DashKmPerL] { dash }
    func insertDashKmPerL(_ d: DashKmPerL) -> Int64 { var x = d; x.id = id(); dash.append(x); return x.id }
    func allServices() -> [ServiceItem] { serviceItems.sorted { ($0.createdAtMs, $0.id) < ($1.createdAtMs, $1.id) } }
    func insertService(_ s: ServiceItem) -> Int64 { var x = s; x.id = id(); serviceItems.append(x); return x.id }
    func updateService(_ s: ServiceItem) { serviceItems = serviceItems.map { $0.id == s.id ? s : $0 } }
    func deleteService(_ id: Int64) { serviceItems.removeAll { $0.id == id } }
    func allServiceRecords() -> [ServiceRecord] { records.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }
    func insertServiceRecord(_ r: ServiceRecord) -> Int64 { var x = r; x.id = id(); records.append(x); return x.id }
    func clearGarage() { bikes = []; readings = []; refuelRows = []; prices = []; marks = []; dash = []; serviceItems = []; records = [] }
}

/// Wall time driven by the virtual scheduler: `base + virtual ms`.
final class TestWall: WallClock {
    let vt: VirtualScheduler
    let base: Int64
    init(_ vt: VirtualScheduler, base: Int64) { self.vt = vt; self.base = base }
    func nowMs() -> Int64 { base + vt.nowMs }
}
