import Foundation
import SwiftData

// The app's storage (15d): SwiftData behind the pure `TripDao` / `GarageDao` protocols. Everything except the full
// tracks is small, so the rows are read once at launch and kept in memory as value records; every write goes to
// SwiftData and is saved at once (a trip ended by swiping the app away must reach the disk before the process ends).
// Tracks are one blob per trip (`TrackCodec`), read only for an export, a backup or the detail map.

@Model final class TripEntity {
    @Attribute(.unique) var rowId: Int64
    var startedAtMs: Int64
    var endedAtMs: Int64
    var durationMs: Int64
    var movingMs: Int64
    var distanceM: Double
    var maxSpeedMps: Float
    var startLat: Double
    var startLon: Double
    var endLat: Double
    var endLon: Double
    var startAddress: String?
    var endAddress: String?
    var track: String
    var stoppedMs: Int64
    var bikeId: String?

    init(_ r: TripRecord) {
        rowId = r.id; startedAtMs = r.startedAtMs; endedAtMs = r.endedAtMs; durationMs = r.durationMs; movingMs = r.movingMs
        distanceM = r.distanceM; maxSpeedMps = r.maxSpeedMps; startLat = r.startLat; startLon = r.startLon; endLat = r.endLat
        endLon = r.endLon; startAddress = r.startAddress; endAddress = r.endAddress; track = r.track; stoppedMs = r.stoppedMs; bikeId = r.bikeId
    }

    var record: TripRecord {
        TripRecord(id: rowId, startedAtMs: startedAtMs, endedAtMs: endedAtMs, durationMs: durationMs, movingMs: movingMs, distanceM: distanceM,
                   maxSpeedMps: maxSpeedMps, startLat: startLat, startLon: startLon, endLat: endLat, endLon: endLon, startAddress: startAddress,
                   endAddress: endAddress, track: track, stoppedMs: stoppedMs, bikeId: bikeId)
    }
}

@Model final class TrackEntity {
    @Attribute(.unique) var tripId: Int64
    @Attribute(.externalStorage) var points: Data
    init(tripId: Int64, points: Data) { self.tripId = tripId; self.points = points }
}

@Model final class BikeEntity {
    @Attribute(.unique) var bikeId: String
    var name: String
    var type: String
    var model: String
    var tankLitres: Double
    var reserveLitres: Double
    var fuelType: String
    var startKmPerL: Double
    var odoFactor: Double
    var active: Bool
    var lastUsedAtMs: Int64
    var createdAtMs: Int64

    init(_ b: BikeRecord) {
        bikeId = b.id; name = b.name; type = b.type; model = b.model; tankLitres = b.tankLitres; reserveLitres = b.reserveLitres
        fuelType = b.fuelType; startKmPerL = b.startKmPerL; odoFactor = b.odoFactor; active = b.active; lastUsedAtMs = b.lastUsedAtMs
        createdAtMs = b.createdAtMs
    }

    func set(_ b: BikeRecord) {
        name = b.name; type = b.type; model = b.model; tankLitres = b.tankLitres; reserveLitres = b.reserveLitres; fuelType = b.fuelType
        startKmPerL = b.startKmPerL; odoFactor = b.odoFactor; active = b.active; lastUsedAtMs = b.lastUsedAtMs; createdAtMs = b.createdAtMs
    }

    var record: BikeRecord {
        BikeRecord(id: bikeId, name: name, type: type, model: model, tankLitres: tankLitres, reserveLitres: reserveLitres, fuelType: fuelType,
                   startKmPerL: startKmPerL, odoFactor: odoFactor, active: active, lastUsedAtMs: lastUsedAtMs, createdAtMs: createdAtMs)
    }
}

@Model final class ReadingEntity {
    @Attribute(.unique) var rowId: Int64
    var bikeId: String
    var timeMs: Int64
    var km: Double
    var kind: String
    var awayKm: Double
    var tripOffsetM: Double

    init(_ r: OdometerReading) {
        rowId = r.id; bikeId = r.bikeId; timeMs = r.timeMs; km = r.km; kind = r.kind; awayKm = r.awayKm; tripOffsetM = r.tripOffsetM
    }

    var record: OdometerReading {
        OdometerReading(id: rowId, bikeId: bikeId, timeMs: timeMs, km: km, kind: kind, awayKm: awayKm, tripOffsetM: tripOffsetM)
    }
}

@Model final class RefuelEntity {
    @Attribute(.unique) var rowId: Int64
    var bikeId: String
    var timeMs: Int64
    var odometerKm: Double
    var litres: Double
    var pricePerL: Double
    var totalRp: Double
    var full: Bool
    var fuelType: String

    init(_ r: RefuelRecord) {
        rowId = r.id; bikeId = r.bikeId; timeMs = r.timeMs; odometerKm = r.odometerKm; litres = r.litres; pricePerL = r.pricePerL
        totalRp = r.totalRp; full = r.full; fuelType = r.fuelType
    }

    var record: RefuelRecord {
        RefuelRecord(id: rowId, bikeId: bikeId, timeMs: timeMs, odometerKm: odometerKm, litres: litres, pricePerL: pricePerL, totalRp: totalRp,
                     full: full, fuelType: fuelType)
    }
}

@Model final class PriceEntity {
    @Attribute(.unique) var fuelType: String
    var pricePerL: Double
    var updatedAtMs: Int64
    init(_ p: FuelPrice) { fuelType = p.fuelType; pricePerL = p.pricePerL; updatedAtMs = p.updatedAtMs }
    var record: FuelPrice { FuelPrice(fuelType, pricePerL, updatedAtMs) }
}

@Model final class FuelMarkEntity {
    @Attribute(.unique) var rowId: Int64
    var bikeId: String
    var timeMs: Int64
    var odometerKm: Double
    var bars: Int
    var ofBars: Int
    var litres: Double
    init(_ m: FuelMark) { rowId = m.id; bikeId = m.bikeId; timeMs = m.timeMs; odometerKm = m.odometerKm; bars = m.bars; ofBars = m.ofBars; litres = m.litres }
    var record: FuelMark { FuelMark(id: rowId, bikeId: bikeId, timeMs: timeMs, odometerKm: odometerKm, bars: bars, ofBars: ofBars, litres: litres) }
}

@Model final class DashEntity {
    @Attribute(.unique) var rowId: Int64
    var bikeId: String
    var timeMs: Int64
    var kmPerL: Double
    var tripKm: Double
    init(_ d: DashKmPerL) { rowId = d.id; bikeId = d.bikeId; timeMs = d.timeMs; kmPerL = d.kmPerL; tripKm = d.tripKm }
    var record: DashKmPerL { DashKmPerL(id: rowId, bikeId: bikeId, timeMs: timeMs, kmPerL: kmPerL, tripKm: tripKm) }
}

@Model final class ServiceItemEntity {
    @Attribute(.unique) var rowId: Int64
    var bikeId: String
    var name: String
    var kind: String
    var intervalKm: Double?
    var intervalMonths: Int?
    var dueDateMs: Int64?
    var lastDoneMs: Int64
    var lastDoneKm: Double?
    var createdAtMs: Int64

    init(_ s: ServiceItem) {
        rowId = s.id; bikeId = s.bikeId; name = s.name; kind = s.kind; intervalKm = s.intervalKm; intervalMonths = s.intervalMonths
        dueDateMs = s.dueDateMs; lastDoneMs = s.lastDoneMs; lastDoneKm = s.lastDoneKm; createdAtMs = s.createdAtMs
    }

    func set(_ s: ServiceItem) {
        bikeId = s.bikeId; name = s.name; kind = s.kind; intervalKm = s.intervalKm; intervalMonths = s.intervalMonths; dueDateMs = s.dueDateMs
        lastDoneMs = s.lastDoneMs; lastDoneKm = s.lastDoneKm; createdAtMs = s.createdAtMs
    }

    var record: ServiceItem {
        ServiceItem(id: rowId, bikeId: bikeId, name: name, kind: kind, intervalKm: intervalKm, intervalMonths: intervalMonths, dueDateMs: dueDateMs,
                    lastDoneMs: lastDoneMs, lastDoneKm: lastDoneKm, createdAtMs: createdAtMs)
    }
}

@Model final class ServiceRecordEntity {
    @Attribute(.unique) var rowId: Int64
    var itemId: Int64
    var bikeId: String
    var itemName: String
    var timeMs: Int64
    var odometerKm: Double?
    var costRp: Double?
    var note: String?

    init(_ r: ServiceRecord) {
        rowId = r.id; itemId = r.itemId; bikeId = r.bikeId; itemName = r.itemName; timeMs = r.timeMs; odometerKm = r.odometerKm
        costRp = r.costRp; note = r.note
    }

    var record: ServiceRecord {
        ServiceRecord(id: rowId, itemId: itemId, bikeId: bikeId, itemName: itemName, timeMs: timeMs, odometerKm: odometerKm, costRp: costRp, note: note)
    }
}

/// The history and the Garage on disk. Used from the main thread only (its context isn't shared).
final class AppDatabase: TripDao, GarageDao {
    private let context: ModelContext

    private var trips: [TripRecord]
    private var bikes: [BikeRecord]
    private var readings: [OdometerReading]
    private var refuels: [RefuelRecord]
    private var prices: [FuelPrice]
    private var marks: [FuelMark]
    private var dash: [DashKmPerL]
    private var services: [ServiceItem]
    private var records: [ServiceRecord]

    static let schema: [any PersistentModel.Type] = [TripEntity.self, TrackEntity.self, BikeEntity.self, ReadingEntity.self, RefuelEntity.self,
                                                     PriceEntity.self, FuelMarkEntity.self, DashEntity.self, ServiceItemEntity.self,
                                                     ServiceRecordEntity.self]

    /// The store file, in Application Support (backed up by iCloud / Finder like any app data).
    static var storeURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("native-auto.store")
    }

    /// `inMemory` for previews; the app opens the file. A store that can't be opened (damaged) is moved aside and a new
    /// one started, so the app always starts; the old file stays next to it for a later look.
    init(inMemory: Bool = false) {
        let schema = Schema(Self.schema)
        let container: ModelContainer
        if inMemory {
            container = try! ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        } else if let c = try? ModelContainer(for: schema, configurations: ModelConfiguration(url: Self.storeURL)) {
            container = c
        } else {
            let url = Self.storeURL
            try? FileManager.default.moveItem(at: url, to: url.appendingPathExtension("damaged-\(Int(Date().timeIntervalSince1970))"))
            container = (try? ModelContainer(for: schema, configurations: ModelConfiguration(url: url)))
                ?? (try! ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true)))
        }
        let context = ModelContext(container)
        context.autosaveEnabled = false
        self.context = context
        func all<T: PersistentModel>(_ t: T.Type) -> [T] { (try? context.fetch(FetchDescriptor<T>())) ?? [] }
        trips = all(TripEntity.self).map(\.record)
        bikes = all(BikeEntity.self).map(\.record)
        readings = all(ReadingEntity.self).map(\.record)
        refuels = all(RefuelEntity.self).map(\.record)
        prices = all(PriceEntity.self).map(\.record)
        marks = all(FuelMarkEntity.self).map(\.record)
        dash = all(DashEntity.self).map(\.record)
        services = all(ServiceItemEntity.self).map(\.record)
        records = all(ServiceRecordEntity.self).map(\.record)
    }

    /// Debug `-reset_data 1` (UI tests start from an empty history and Garage).
    static func deleteStore() {
        let url = storeURL
        for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) }
    }

    private func save() {
        do { try context.save() } catch { NSLog("NativeAuto: database save failed: \(error)") }
    }

    private func first<T: PersistentModel>(_ p: Predicate<T>) -> T? {
        var d = FetchDescriptor<T>(predicate: p)
        d.fetchLimit = 1
        return (try? context.fetch(d))?.first
    }

    private func each<T: PersistentModel>(_ p: Predicate<T>, _ f: (T) -> Void) {
        ((try? context.fetch(FetchDescriptor<T>(predicate: p))) ?? []).forEach(f)
    }

    /// Optional columns compared in Swift: `#Predicate` with optionals is fragile across iOS versions.
    private func allTripEntities() -> [TripEntity] { (try? context.fetch(FetchDescriptor<TripEntity>())) ?? [] }

    private func nextId(_ ids: [Int64]) -> Int64 { (ids.max() ?? 0) + 1 }

    // MARK: TripDao

    func allTrips() -> [TripRecord] { trips.sorted { $0.startedAtMs > $1.startedAtMs } }
    func trip(_ id: Int64) -> TripRecord? { trips.first { $0.id == id } }

    func insertWithTrack(_ trip: TripRecord, _ points: [TrackPointRecord]) -> Int64 {
        var t = trip
        t.id = nextId(trips.map(\.id))
        context.insert(TripEntity(t))
        if !points.isEmpty { context.insert(TrackEntity(tripId: t.id, points: TrackCodec.encode(points))) }
        trips.append(t)
        save()
        return t.id
    }

    func points(_ tripId: Int64) -> [TrackPointRecord] {
        first(#Predicate<TrackEntity> { $0.tripId == tripId }).map { TrackCodec.decode($0.points, tripId: tripId) } ?? []
    }

    func setAddresses(_ id: Int64, start: String?, end: String?) {
        guard let e = first(#Predicate<TripEntity> { $0.rowId == id }) else { return }
        e.startAddress = start; e.endAddress = end
        if let i = trips.firstIndex(where: { $0.id == id }) { trips[i].startAddress = start; trips[i].endAddress = end }
        save()
    }

    func delete(_ id: Int64) {
        each(#Predicate<TrackEntity> { $0.tripId == id }) { context.delete($0) }
        each(#Predicate<TripEntity> { $0.rowId == id }) { context.delete($0) }
        trips.removeAll { $0.id == id }
        save()
    }

    // MARK: GarageDao: bikes

    func allBikes() -> [BikeRecord] { bikes.sorted { $0.createdAtMs < $1.createdAtMs } }

    func insertBike(_ bike: BikeRecord) {
        context.insert(BikeEntity(bike))
        bikes.append(bike)
        save()
    }

    func updateBike(_ bike: BikeRecord) {
        let id = bike.id
        first(#Predicate<BikeEntity> { $0.bikeId == id })?.set(bike)
        bikes = bikes.map { $0.id == id ? bike : $0 }
        save()
    }

    func deleteBike(_ id: String) {
        each(#Predicate<ReadingEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<RefuelEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<FuelMarkEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<DashEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<ServiceItemEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<ServiceRecordEntity> { $0.bikeId == id }) { context.delete($0) }
        each(#Predicate<BikeEntity> { $0.bikeId == id }) { context.delete($0) }
        allTripEntities().filter { $0.bikeId == id }.forEach { $0.bikeId = nil }
        readings.removeAll { $0.bikeId == id }
        refuels.removeAll { $0.bikeId == id }
        marks.removeAll { $0.bikeId == id }
        dash.removeAll { $0.bikeId == id }
        services.removeAll { $0.bikeId == id }
        records.removeAll { $0.bikeId == id }
        bikes.removeAll { $0.id == id }
        for i in trips.indices where trips[i].bikeId == id { trips[i].bikeId = nil }
        save()
    }

    func tagUntaggedTrips(_ bikeId: String) {
        allTripEntities().filter { $0.bikeId == nil }.forEach { $0.bikeId = bikeId }
        for i in trips.indices where trips[i].bikeId == nil { trips[i].bikeId = bikeId }
        save()
    }

    func trips(bikeId: String) -> [TripRecord] { trips.filter { $0.bikeId == bikeId } }

    // MARK: readings, fuel

    func allReadings() -> [OdometerReading] { readings.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }

    func insertReading(_ r: OdometerReading) -> Int64 {
        var x = r; x.id = nextId(readings.map(\.id))
        context.insert(ReadingEntity(x)); readings.append(x); save()
        return x.id
    }

    func allRefuels() -> [RefuelRecord] { refuels.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }

    func insertRefuel(_ r: RefuelRecord) -> Int64 {
        var x = r; x.id = nextId(refuels.map(\.id))
        context.insert(RefuelEntity(x)); refuels.append(x); save()
        return x.id
    }

    func deleteRefuel(_ id: Int64) {
        each(#Predicate<RefuelEntity> { $0.rowId == id }) { context.delete($0) }
        refuels.removeAll { $0.id == id }
        save()
    }

    func allPrices() -> [FuelPrice] { prices }

    func upsertPrice(_ p: FuelPrice) {
        let type = p.fuelType
        if let e = first(#Predicate<PriceEntity> { $0.fuelType == type }) {
            e.pricePerL = p.pricePerL; e.updatedAtMs = p.updatedAtMs
        } else {
            context.insert(PriceEntity(p))
        }
        prices.removeAll { $0.fuelType == type }
        prices.append(p)
        save()
    }

    func allFuelMarks() -> [FuelMark] { marks }

    func insertFuelMark(_ m: FuelMark) -> Int64 {
        var x = m; x.id = nextId(marks.map(\.id))
        context.insert(FuelMarkEntity(x)); marks.append(x); save()
        return x.id
    }

    func allDashKmPerL() -> [DashKmPerL] { dash }

    func insertDashKmPerL(_ d: DashKmPerL) -> Int64 {
        var x = d; x.id = nextId(dash.map(\.id))
        context.insert(DashEntity(x)); dash.append(x); save()
        return x.id
    }

    // MARK: services

    func allServices() -> [ServiceItem] { services.sorted { ($0.createdAtMs, $0.id) < ($1.createdAtMs, $1.id) } }

    func insertService(_ s: ServiceItem) -> Int64 {
        var x = s; x.id = nextId(services.map(\.id))
        context.insert(ServiceItemEntity(x)); services.append(x); save()
        return x.id
    }

    func updateService(_ s: ServiceItem) {
        let id = s.id
        first(#Predicate<ServiceItemEntity> { $0.rowId == id })?.set(s)
        services = services.map { $0.id == id ? s : $0 }
        save()
    }

    func deleteService(_ id: Int64) {
        each(#Predicate<ServiceItemEntity> { $0.rowId == id }) { context.delete($0) }
        services.removeAll { $0.id == id }
        save()
    }

    func allServiceRecords() -> [ServiceRecord] { records.sorted { ($0.timeMs, $0.id) < ($1.timeMs, $1.id) } }

    func insertServiceRecord(_ r: ServiceRecord) -> Int64 {
        var x = r; x.id = nextId(records.map(\.id))
        context.insert(ServiceRecordEntity(x)); records.append(x); save()
        return x.id
    }

    func clearGarage() {
        for b in bikes.map(\.id) { deleteBike(b) }
        try? context.delete(model: PriceEntity.self)
        prices = []
        save()
    }
}

/// The trip in progress, checkpointed to two files in Application Support: the figures (JSON, replaced each time) and
/// the fixes (one JSON line each, appended). About once a minute, so a killed process loses at most a minute.
final class FileTripCheckpoints: TripCheckpoints {
    private let dir: URL
    private var tripURL: URL { dir.appendingPathComponent("live-trip.json") }
    private var pointsURL: URL { dir.appendingPathComponent("live-points.jsonl") }

    init(dir: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]) {
        self.dir = dir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    func write(_ trip: LiveTripRecord, newFixes: [SpeedSample], replace: Bool) {
        let enc = JSONEncoder()
        var lines = Data()
        for f in newFixes { if let d = try? enc.encode(f) { lines.append(d); lines.append(0x0A) } }
        if replace || !FileManager.default.fileExists(atPath: pointsURL.path) {
            try? lines.write(to: pointsURL, options: .atomic)
        } else if let h = try? FileHandle(forWritingTo: pointsURL) {
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: lines)
            try? h.close()
        }
        if let d = try? enc.encode(trip) { try? d.write(to: tripURL, options: .atomic) }
    }

    func read() -> Checkpoint? {
        guard let d = try? Data(contentsOf: tripURL), let trip = try? JSONDecoder().decode(LiveTripRecord.self, from: d) else { return nil }
        let text = (try? String(contentsOf: pointsURL, encoding: .utf8)) ?? ""
        // A line cut off by the kill is skipped.
        let fixes = text.split(separator: "\n").compactMap { try? JSONDecoder().decode(SpeedSample.self, from: Data($0.utf8)) }
        return Checkpoint(trip: trip, fixes: fixes)
    }

    func clear() {
        try? FileManager.default.removeItem(at: tripURL)
        try? FileManager.default.removeItem(at: pointsURL)
    }
}
