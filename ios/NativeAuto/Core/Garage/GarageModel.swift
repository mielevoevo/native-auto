import Foundation

// Port of Android `garage/Garage.kt` (entities and the DAO's contract), plus the records of `Fuel.kt` and `Service.kt`.
// Enum-typed columns stay strings, as on Android, so a backup moves between the phones unchanged.

/// The kind of bike. v1 builds scooters (CVT) only.
enum BikeType: String, CaseIterable {
    case scooterCvt = "SCOOTER_CVT"
    var label: String { "Scooter (CVT)" }
}

/// Indonesian pump fuels. `defaultPricePerL` (Rp, rates of 2026-11-09) is only a starting point: the rider's price
/// (12c) or the pump's is stored and used from then on. `brand` / `grade` are the two-line button label.
enum FuelType: String, CaseIterable {
    case pertalite = "PERTALITE", pertamax = "PERTAMAX", pertamaxGreen = "PERTAMAX_GREEN", pertamaxTurbo = "PERTAMAX_TURBO"

    var label: String {
        switch self {
        case .pertalite: "Pertalite"; case .pertamax: "Pertamax"; case .pertamaxGreen: "Pertamax Green"; case .pertamaxTurbo: "Pertamax Turbo"
        }
    }
    var brand: String? { self == .pertalite ? nil : "Pertamax" }
    var grade: String {
        switch self { case .pertalite: "Pertalite"; case .pertamax: "92"; case .pertamaxGreen: "Green"; case .pertamaxTurbo: "Turbo" }
    }
    var defaultPricePerL: Double {
        switch self { case .pertalite: 10_000; case .pertamax: 15_950; case .pertamaxGreen: 19_150; case .pertamaxTurbo: 19_600 }
    }

    static func named(_ s: String) -> FuelType { FuelType(rawValue: s) ?? .pertamax }
}

/// Lean v1 presets: the NMAX generations, which differ by tank. Reserve ~20% of the tank. Everything stays editable.
enum BikeModel: String, CaseIterable {
    case nmaxGen1 = "NMAX_GEN1", nmaxGen2 = "NMAX_GEN2", other = "OTHER"

    var label: String { switch self { case .nmaxGen1: "NMAX Gen 1"; case .nmaxGen2: "NMAX Gen 2"; case .other: "Other scooter" } }
    var shortLabel: String { switch self { case .nmaxGen1: "NMAX Gen 1"; case .nmaxGen2: "NMAX Gen 2"; case .other: "Other" } }
    var tankLitres: Double { self == .nmaxGen1 ? 6.6 : 7.1 }
    var reserveLitres: Double { self == .nmaxGen1 ? 1.3 : 1.4 }

    static func named(_ s: String) -> BikeModel { BikeModel(rawValue: s) ?? .other }
}

/// Starting consumption for a new bike; learned from full-to-full fill-ups afterwards.
let defaultKmPerL = 36.0

/// One of the rider's bikes. Exactly one is `active` once any exist: trips are tagged with it.
struct BikeRecord: Equatable, Identifiable {
    var id: String
    var name: String
    var type: String = BikeType.scooterCvt.rawValue
    var model: String
    var tankLitres: Double
    var reserveLitres: Double
    var fuelType: String
    var startKmPerL: Double
    /// Bike km per GPS km, learned from odometer corrections (1.0 until then).
    var odoFactor: Double = 1.0
    var active: Bool = false
    /// When the bike stopped being the active one (or was created): the away-km check.
    var lastUsedAtMs: Int64
    var createdAtMs: Int64

    var bikeModel: BikeModel { BikeModel.named(model) }
    var fuel: FuelType { FuelType.named(fuelType) }
}

/// Why an odometer reading was entered. Stored by name.
enum ReadingKind: String, CaseIterable {
    /// The first reading of a bike.
    case set = "SET"
    /// The rider fixed the estimate (teaches `odoFactor`).
    case correction = "CORRECTION"
    /// Read after the bike was away (ridden by others): the gap is `awayKm`. Fuel is unknown until the next full fill.
    case away = "AWAY"
    /// 11b: the rider's own ride without the app, added by hand. Counts like a tracked ride, teaches nothing.
    case untracked = "UNTRACKED"
}

/// The bike's real odometer at `timeMs`.
struct OdometerReading: Equatable {
    var id: Int64 = 0
    var bikeId: String
    var timeMs: Int64
    var km: Double
    var kind: String
    /// AWAY and UNTRACKED: km ridden while the app wasn't there.
    var awayKm: Double = 0
    /// GPS metres the trip running at `timeMs` had already covered (0 if none).
    var tripOffsetM: Double = 0

    var readingKind: ReadingKind { ReadingKind(rawValue: kind) ?? .correction }
}

/// One fill-up. `odometerKm` is the bike's real reading at the pump (prefilled with the estimate).
struct RefuelRecord: Equatable, Identifiable {
    var id: Int64 = 0
    var bikeId: String
    var timeMs: Int64
    var odometerKm: Double
    var litres: Double
    /// Rp per litre at the pump.
    var pricePerL: Double
    /// Rp paid.
    var totalRp: Double
    /// Filled to the brim (the default) or partial.
    var full: Bool
    var fuelType: String

    var fuel: FuelType { FuelType.named(fuelType) }
}

/// The last price per litre seen at the pump, per fuel type.
struct FuelPrice: Equatable {
    var fuelType: String
    var pricePerL: Double
    var updatedAtMs: Int64

    init(_ fuelType: String, _ pricePerL: Double, _ updatedAtMs: Int64) {
        self.fuelType = fuelType; self.pricePerL = pricePerL; self.updatedAtMs = updatedAtMs
    }
}

/// 12b "Set fuel level": the tank as read off the bike's gauge, with the odometer then.
struct FuelMark: Equatable {
    var id: Int64 = 0
    var bikeId: String
    var timeMs: Int64
    var odometerKm: Double
    var bars: Int
    var ofBars: Int
    /// What that means in litres (`gaugeLitres`, cautious).
    var litres: Double
}

/// 12b: the bike's own trip average (km/L from its dash), given after a ride.
struct DashKmPerL: Equatable {
    var id: Int64 = 0
    var bikeId: String
    var timeMs: Int64
    var kmPerL: Double
    /// GPS km of the ride it was given after.
    var tripKm: Double
}

enum ServiceKind: String, CaseIterable {
    /// Due every `intervalKm` and/or `intervalMonths`.
    case distance = "DISTANCE"
    /// Due on `dueDateMs`; marking it done moves that on by `intervalMonths`.
    case date = "DATE"
}

struct ServiceItem: Equatable, Identifiable {
    var id: Int64 = 0
    var bikeId: String
    var name: String
    var kind: String
    var intervalKm: Double? = nil
    var intervalMonths: Int? = nil
    /// DATE only: the next due date.
    var dueDateMs: Int64? = nil
    /// DISTANCE: when it was last done (or added).
    var lastDoneMs: Int64
    /// DISTANCE: the odometer then; nil if the odometer wasn't set.
    var lastDoneKm: Double? = nil
    var createdAtMs: Int64

    var serviceKind: ServiceKind { ServiceKind(rawValue: kind) ?? .distance }
}

/// One "Mark done". `itemName` is kept so the history reads well after a rename or delete.
struct ServiceRecord: Equatable, Identifiable {
    var id: Int64 = 0
    var itemId: Int64
    var bikeId: String
    var itemName: String
    var timeMs: Int64
    var odometerKm: Double? = nil
    var costRp: Double? = nil
    var note: String? = nil
}

/// The Garage's storage: what Android's `GarageDao` does, with the queries done in Swift (the data is small).
/// Synchronous; inserts return the new row id. Lists come back oldest first unless said otherwise.
protocol GarageDao: AnyObject {
    // Bikes
    func allBikes() -> [BikeRecord]          // by createdAtMs
    func insertBike(_ bike: BikeRecord)
    func updateBike(_ bike: BikeRecord)
    /// The bike, its readings, fill-ups, marks, dash figures and services go; its trips stay, untagged.
    func deleteBike(_ id: String)
    func tagUntaggedTrips(_ bikeId: String)
    /// The bike's saved trips (for metres since a reading and the last ride).
    func trips(bikeId: String) -> [TripRecord]

    // Readings
    func allReadings() -> [OdometerReading]  // by timeMs, id
    @discardableResult func insertReading(_ r: OdometerReading) -> Int64

    // Fuel
    func allRefuels() -> [RefuelRecord]
    @discardableResult func insertRefuel(_ r: RefuelRecord) -> Int64
    func deleteRefuel(_ id: Int64)
    func allPrices() -> [FuelPrice]
    func upsertPrice(_ p: FuelPrice)
    func allFuelMarks() -> [FuelMark]
    @discardableResult func insertFuelMark(_ m: FuelMark) -> Int64
    func allDashKmPerL() -> [DashKmPerL]
    @discardableResult func insertDashKmPerL(_ d: DashKmPerL) -> Int64

    // Services
    func allServices() -> [ServiceItem]      // by createdAtMs, id
    @discardableResult func insertService(_ s: ServiceItem) -> Int64
    func updateService(_ s: ServiceItem)
    func deleteService(_ id: Int64)
    func allServiceRecords() -> [ServiceRecord]
    @discardableResult func insertServiceRecord(_ r: ServiceRecord) -> Int64

    /// Device-test style reset: an empty Garage.
    func clearGarage()
}

extension GarageDao {
    func bike(_ id: String) -> BikeRecord? { allBikes().first { $0.id == id } }
    func activeBike() -> BikeRecord? { allBikes().first { $0.active } }
    func bikesByUse() -> [BikeRecord] { allBikes().sorted { $0.lastUsedAtMs > $1.lastUsedAtMs } }

    /// One active bike at a time; the one left behind remembers when (the away-km check).
    func switchActive(_ id: String, nowMs: Int64) {
        for var b in allBikes() {
            if b.active { b.active = false; b.lastUsedAtMs = nowMs; updateBike(b) }
        }
        if var b = bike(id) { b.active = true; updateBike(b) }
    }

    func setFactor(_ id: String, _ factor: Double) {
        if var b = bike(id) { b.odoFactor = factor; updateBike(b) }
    }

    /// Newest first.
    func readings(_ bikeId: String) -> [OdometerReading] {
        allReadings().filter { $0.bikeId == bikeId }.sorted { ($0.timeMs, $0.id) > ($1.timeMs, $1.id) }
    }

    func lastReading(_ bikeId: String) -> OdometerReading? { readings(bikeId).first }

    /// GPS metres of the bike's saved trips after `sinceMs`. A trip running at that moment counts only past `offsetM`.
    func metersSince(_ bikeId: String, sinceMs: Int64, offsetM: Double) -> Double {
        trips(bikeId: bikeId).filter { $0.endedAtMs > sinceMs }
            .reduce(0) { $0 + ($1.startedAtMs < sinceMs ? max($1.distanceM - offsetM, 0) : $1.distanceM) }
    }

    /// Where the bike was last ridden to.
    func lastTrip(_ bikeId: String) -> TripRecord? { trips(bikeId: bikeId).max { $0.endedAtMs < $1.endedAtMs } }

    /// Newest first.
    func refuels(_ bikeId: String) -> [RefuelRecord] {
        allRefuels().filter { $0.bikeId == bikeId }.sorted { ($0.timeMs, $0.id) > ($1.timeMs, $1.id) }
    }
    func fuelMarks(_ bikeId: String) -> [FuelMark] { allFuelMarks().filter { $0.bikeId == bikeId } }
    func dashKmPerL(_ bikeId: String) -> [DashKmPerL] { allDashKmPerL().filter { $0.bikeId == bikeId } }
    func services(_ bikeId: String) -> [ServiceItem] { allServices().filter { $0.bikeId == bikeId } }
    func service(_ id: Int64) -> ServiceItem? { allServices().first { $0.id == id } }
    /// Newest first.
    func serviceRecords(_ bikeId: String) -> [ServiceRecord] {
        allServiceRecords().filter { $0.bikeId == bikeId }.sorted { ($0.timeMs, $0.id) > ($1.timeMs, $1.id) }
    }

    /// "Mark done": the item's new starting point and the history entry.
    func markDone(_ item: ServiceItem, _ record: ServiceRecord) {
        updateService(item)
        insertServiceRecord(record)
    }
}

/// The one figure the small Garage card shows (11c, a setting).
enum GarageValue: String, CaseIterable {
    case range = "RANGE", sinceRefuel = "SINCE_REFUEL", odometer = "ODOMETER", nextService = "NEXT_SERVICE", kmPerL = "KM_PER_L"
    var label: String {
        switch self {
        case .range: "Range"; case .sinceRefuel: "Since fill-up"; case .odometer: "Odometer"; case .nextService: "Next service"; case .kmPerL: "km/L"
        }
    }
}

/// The Garage card's tank: the 5-segment gauge, or a plain pump icon that turns red in the last segment (13a).
enum TankIcon: String, CaseIterable {
    case gauge = "GAUGE", plain = "PLAIN"
    var label: String { self == .gauge ? "Gauge" : "Plain" }
}
