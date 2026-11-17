import Foundation

// Port of Android `garage/GarageRepository.kt` (11a–12c). Synchronous over `GarageDao`: the app's `GarageStore`
// republishes after each write and when the live trip moves 10 m; Android does the same with Flows.

/// What the rider types in the bike editor.
struct BikeDraft: Equatable {
    var name: String
    var model: BikeModel
    var tankLitres: Double
    var reserveLitres: Double
    var fuel: FuelType
    var startKmPerL: Double
}

/// A question the dashboard shows as a dialog.
enum GaragePrompt: Equatable {
    /// "Gen 2's odometer now?": the bike had been away; prefilled with the estimate.
    case odometerNow(bike: BikeRecord, estimateKm: Double?)
    /// Debug builds: the trip started far from where the active bike was last ridden.
    case whichBike(bikes: [BikeRecord], activeId: String, distanceKm: Double)
}

/// A bike's odometer as the Garage shows it.
struct OdometerState: Equatable {
    var estimateKm: Double?
    var lastReading: OdometerReading?
    var factor: Double
}

/// One fill-up the rider logs at the pump.
struct RefuelDraft: Equatable {
    var full: Bool
    var litres: Double
    var pricePerL: Double
    var totalRp: Double
    var fuel: FuelType
    /// The bike's real odometer, prefilled with the estimate.
    var odometerKm: Double
}

/// A bike's fuel as the Garage shows it.
struct FuelState: Equatable {
    var level: FuelLevel
    var consumption: Consumption
    /// Newest first.
    var refuels: [RefuelRecord]
    /// Rp per litre of the bike's fuel: the last price logged, else the default.
    var pricePerL: Double
}

/// What the rider types when adding or editing a service item.
struct ServiceDraft: Equatable {
    var name: String
    var kind: ServiceKind
    var intervalKm: Double?
    var intervalMonths: Int?
    /// DATE: the next due date.
    var dueDateMs: Int64?
    /// DISTANCE, when adding: the odometer it was last done at (nil: now).
    var lastDoneKm: Double?
}

/// Everything the Garage card shows for the active bike.
struct GarageCardState: Equatable {
    var bike: BikeRecord
    var odometer: OdometerState
    var fuel: FuelState
    /// Most urgent first.
    var services: [ServiceDue]

    /// The item with an interval that comes due first (the card's "Next service").
    var nextService: ServiceDue? { services.first { $0.level != .noInterval } }
    /// Shown at trip start and end, only when something is due soon or overdue.
    var reminder: ServiceDue? { reminderOf(services) }
}

/// A refuel's odometer within this of the estimate is the estimate (the rounded prefill accepted).
let refuelSameKm = 1.0
/// A trip starting this far from the active bike's last ride asks "which bike?" (debug).
let whichBikeKm = 50.0

/// The distance that warrants the question, or nil when there's nothing to ask.
func whichBikeDistanceKm(bikeCount: Int, lastRideEnd: GeoPoint?, here: GeoPoint) -> Double? {
    guard bikeCount >= 2, let last = lastRideEnd else { return nil }
    let km = haversineMeters(last.latitude, last.longitude, here.latitude, here.longitude) / 1000
    return km >= whichBikeKm ? km : nil
}

/// The trip in progress (running or paused) in wall time; nil otherwise (an ended trip is already saved).
func liveTrip(state: TripState, stats: TripStats, nowNanos: Int64, nowMs: Int64) -> LiveTrip? {
    guard state == .running || state == .paused, let start = stats.startedAtNanos else { return nil }
    return LiveTrip(startedAtMs: nowMs - (nowNanos - start) / 1_000_000, distanceM: stats.distanceM)
}

final class GarageRepository {
    let dao: GarageDao
    private let wall: WallClock
    private let live: () -> LiveTrip?
    let zone: TimeZone
    private var newId: () -> String

    private(set) var prompt: GaragePrompt?

    init(dao: GarageDao, wall: WallClock, zone: TimeZone = .current, live: @escaping () -> LiveTrip?,
         newId: @escaping () -> String = { UUID().uuidString.lowercased() }) {
        self.dao = dao; self.wall = wall; self.zone = zone; self.live = live; self.newId = newId
    }

    /// The bike trips are tagged with when saved. Nil before the Garage has any bike.
    func activeId() -> String? { dao.activeBike()?.id }

    var bikes: [BikeRecord] { dao.allBikes() }
    var active: BikeRecord? { dao.activeBike() }

    func readings(_ bikeId: String) -> [OdometerReading] { dao.readings(bikeId) }

    /// The live trip as the Garage shows it: rounded down to 10 m, so the screen needn't redraw at 1 Hz.
    private func liveShown() -> LiveTrip? {
        live().map { LiveTrip(startedAtMs: $0.startedAtMs, distanceM: ($0.distanceM / 10).rounded(.down) * 10) }
    }

    func odometer(_ bikeId: String) -> OdometerState {
        let factor = dao.bike(bikeId)?.odoFactor ?? 1
        guard let reading = dao.lastReading(bikeId) else { return OdometerState(estimateKm: nil, lastReading: nil, factor: factor) }
        let saved = dao.metersSince(bikeId, sinceMs: reading.timeMs, offsetM: reading.tripOffsetM)
        let liveTrip = dao.bike(bikeId)?.active == true ? liveShown() : nil
        return OdometerState(estimateKm: estimateOdometerKm(reading, factor: factor, gpsMetersSince: gpsMetersSince(reading, savedM: saved, live: liveTrip)),
                             lastReading: reading, factor: factor)
    }

    func prices() -> [FuelPrice] { dao.allPrices() }

    /// 12c: the rider's price for `fuel`, kept until changed again. Past refuels keep theirs.
    func setPrice(_ fuel: FuelType, _ pricePerL: Double) {
        dao.upsertPrice(FuelPrice(fuel.rawValue, pricePerL, wall.nowMs()))
    }

    /// Range, km/L and the refuel log; everything on the odometer. Away readings make the level unknown.
    func fuel(_ bikeId: String) -> FuelState {
        let bike = dao.bike(bikeId)
        let refuels = dao.refuels(bikeId)
        let away = dao.readings(bikeId).filter { $0.readingKind == .away }.map(\.timeMs)
        let odo = odometer(bikeId).estimateKm
        let consumption = learnConsumption(startKmPerL: bike?.startKmPerL ?? defaultKmPerL, refuels: refuels, awayTimesMs: away,
                                           dash: dao.dashKmPerL(bikeId))
        // 12b: the level burns at the cautious figure, so litres left and range lean low together.
        let level = fuelLevel(tankLitres: bike?.tankLitres ?? BikeModel.other.tankLitres, kmPerL: consumption.cautiousKmPerL,
                              refuels: refuels, awayTimesMs: away, odometerNowKm: odo, marks: dao.fuelMarks(bikeId))
        return FuelState(level: level, consumption: consumption, refuels: refuels, pricePerL: priceOf(bike?.fuel ?? .pertamax, dao.allPrices()))
    }

    /// The pump's odometer is the truth: a kilometre or more from the estimate is stored as a reading too; otherwise
    /// the exact estimate is kept, so accepting the rounded prefill doesn't log a correction every time.
    private func odometerAtThePump(_ bikeId: String, _ typed: Double) -> Double {
        if let estimate = estimateNow(bikeId), abs(typed - estimate) < refuelSameKm { return estimate }
        enterReading(bikeId, km: typed)
        return typed
    }

    /// Logs a fill-up. The pump price becomes the fuel type's price.
    func logRefuel(_ bikeId: String, _ draft: RefuelDraft) {
        // 13a: more than the estimated room left means the tank was fuller than thought: a full anchor.
        let full = countsAsFull(filledToTop: draft.full, litres: draft.litres, spaceLeftL: fuel(bikeId).level.toFillLitres)
        let odo = odometerAtThePump(bikeId, draft.odometerKm)
        let now = wall.nowMs()
        dao.insertRefuel(RefuelRecord(bikeId: bikeId, timeMs: now, odometerKm: odo, litres: draft.litres, pricePerL: draft.pricePerL,
                                      totalRp: draft.totalRp, full: full, fuelType: draft.fuel.rawValue))
        dao.upsertPrice(FuelPrice(draft.fuel.rawValue, draft.pricePerL, now))
    }

    func deleteRefuel(_ id: Int64) { dao.deleteRefuel(id) }

    /// 12b "Set fuel level": `bars` lit of the gauge's `ofBars`, with the bike's odometer (treated like a refuel's).
    func setFuelLevel(_ bikeId: String, bars: Int, ofBars: Int, odometerKm: Double) {
        let odo = odometerAtThePump(bikeId, odometerKm)
        let tank = dao.bike(bikeId)?.tankLitres ?? BikeModel.other.tankLitres
        dao.insertFuelMark(FuelMark(bikeId: bikeId, timeMs: wall.nowMs(), odometerKm: odo, bars: bars, ofBars: ofBars,
                                    litres: gaugeLitres(bars, ofBars, tankLitres: tank)))
    }

    /// 12b: the bike's dash km/L after a ride; trains the estimate with the fill-ups. False if implausible.
    @discardableResult
    func addDashKmPerL(_ bikeId: String, kmPerL: Double, tripKm: Double) -> Bool {
        guard kmplPlausible.contains(kmPerL) else { return false }
        dao.insertDashKmPerL(DashKmPerL(bikeId: bikeId, timeMs: wall.nowMs(), kmPerL: kmPerL, tripKm: tripKm))
        return true
    }

    /// The rider's own ride without the app: re-anchors at the estimate plus `km`. Teaches nothing. False without an
    /// odometer, or for zero km.
    @discardableResult
    func addUntrackedKm(_ bikeId: String, _ km: Double) -> Bool {
        guard km > 0, let bike = dao.bike(bikeId), let prev = dao.lastReading(bikeId),
              let estimate = estimateOdometerKm(prev, factor: bike.odoFactor, gpsMetersSince: gpsSince(bike, prev)) else { return false }
        insertReading(bike, km: estimate + km, kind: .untracked, awayKm: km)
        return true
    }

    // MARK: - Services (11c)

    /// The bike's items with their state now, most urgent first.
    func services(_ bikeId: String) -> [ServiceDue] {
        let odo = odometer(bikeId).estimateKm
        let now = wall.nowMs()
        return byUrgency(dao.services(bikeId).map { serviceDue($0, odometerNowKm: odo, nowMs: now, zone: zone) })
    }

    func serviceHistory(_ bikeId: String) -> [ServiceRecord] { dao.serviceRecords(bikeId) }

    /// The active bike's card: odometer, fuel and services; nil with no bike.
    func activeCard() -> GarageCardState? {
        guard let bike = dao.activeBike() else { return nil }
        return GarageCardState(bike: bike, odometer: odometer(bike.id), fuel: fuel(bike.id), services: services(bike.id))
    }

    /// A new item starts from now (or from the odometer it was last done at, if given).
    @discardableResult
    func addService(_ bikeId: String, _ draft: ServiceDraft) -> Int64 {
        let now = wall.nowMs()
        let odo = draft.lastDoneKm ?? estimateNow(bikeId)
        return dao.insertService(ServiceItem(bikeId: bikeId, name: draft.name.trimmingCharacters(in: .whitespaces), kind: draft.kind.rawValue,
                                             intervalKm: draft.intervalKm, intervalMonths: draft.intervalMonths, dueDateMs: draft.dueDateMs,
                                             lastDoneMs: now, lastDoneKm: odo, createdAtMs: now))
    }

    /// Name and intervals change; when it was last done stays (that's "Mark done").
    func updateService(_ id: Int64, _ draft: ServiceDraft) {
        guard var item = dao.service(id) else { return }
        item.name = draft.name.trimmingCharacters(in: .whitespaces)
        item.intervalKm = draft.intervalKm
        item.intervalMonths = draft.intervalMonths
        if item.serviceKind == .date { item.dueDateMs = draft.dueDateMs }
        item.lastDoneKm = draft.lastDoneKm ?? item.lastDoneKm
        dao.updateService(item)
    }

    /// The item goes; its history stays (it carries the name).
    func deleteService(_ id: Int64) { dao.deleteService(id) }

    /// "Mark done": the date, the odometer (the estimate unless the rider typed the real reading, then stored as a
    /// reading too), an optional cost and note. A km / months item restarts here; a date item moves on by its interval.
    func markServiceDone(_ itemId: Int64, odometerKm: Double?, costRp: Double?, note: String?) {
        guard let item = dao.service(itemId) else { return }
        let estimate = estimateNow(item.bikeId)
        let odo: Double?
        if let typed = odometerKm {
            if let estimate, abs(typed - estimate) < refuelSameKm { odo = estimate } else { enterReading(item.bikeId, km: typed); odo = typed }
        } else {
            odo = estimate
        }
        let now = wall.nowMs()
        var updated = item
        updated.lastDoneMs = now
        switch item.serviceKind {
        case .distance: updated.lastDoneKm = odo
        case .date: updated.dueDateMs = nextDueDate(item.dueDateMs, months: item.intervalMonths ?? 12, nowMs: now, zone: zone)
        }
        let trimmed = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        dao.markDone(updated, ServiceRecord(itemId: item.id, bikeId: item.bikeId, itemName: item.name, timeMs: now, odometerKm: odo,
                                            costRp: costRp, note: trimmed?.nonEmpty))
    }

    // MARK: - Odometer

    /// The estimate right now (exact, not rounded to 10 m).
    func estimateNow(_ bikeId: String) -> Double? {
        guard let reading = dao.lastReading(bikeId), let bike = dao.bike(bikeId) else { return nil }
        return estimateOdometerKm(reading, factor: bike.odoFactor, gpsMetersSince: gpsSince(bike, reading))
    }

    private func gpsSince(_ bike: BikeRecord, _ reading: OdometerReading) -> Double {
        gpsMetersSince(reading, savedM: dao.metersSince(bike.id, sinceMs: reading.timeMs, offsetM: reading.tripOffsetM),
                       live: bike.active ? live() : nil)
    }

    /// Adds a bike. The first one becomes active and adopts every trip recorded before the Garage existed.
    @discardableResult
    func addBike(_ draft: BikeDraft, odometerKm: Double?) -> String {
        let now = wall.nowMs()
        let id = newId()
        let first = dao.activeBike() == nil
        dao.insertBike(BikeRecord(id: id, name: draft.name.trimmingCharacters(in: .whitespaces), model: draft.model.rawValue,
                                  tankLitres: draft.tankLitres, reserveLitres: draft.reserveLitres, fuelType: draft.fuel.rawValue,
                                  startKmPerL: draft.startKmPerL, active: first, lastUsedAtMs: now, createdAtMs: now))
        if first { dao.tagUntaggedTrips(id) }
        if let odometerKm, let bike = dao.bike(id) { insertReading(bike, km: odometerKm, kind: .set, awayKm: 0) }
        return id
    }

    func updateBike(_ id: String, _ draft: BikeDraft) {
        guard var bike = dao.bike(id) else { return }
        bike.name = draft.name.trimmingCharacters(in: .whitespaces)
        bike.model = draft.model.rawValue
        bike.tankLitres = draft.tankLitres
        bike.reserveLitres = draft.reserveLitres
        bike.fuelType = draft.fuel.rawValue
        bike.startKmPerL = draft.startKmPerL
        dao.updateBike(bike)
    }

    /// Its readings go; its trips stay in the history, untagged. Another bike takes over if it was active.
    func deleteBike(_ id: String) {
        let wasActive = dao.bike(id)?.active == true
        dao.deleteBike(id)
        if wasActive, let next = dao.bikesByUse().first { dao.switchActive(next.id, nowMs: wall.nowMs()) }
        if case .odometerNow(let bike, _) = prompt, bike.id == id { prompt = nil }
    }

    /// Makes `id` the bike being ridden. Idle for `awayAfterMs`, it asks once for its odometer.
    func activate(_ id: String) {
        guard let target = dao.bike(id), !target.active else { return }
        dao.switchActive(id, nowMs: wall.nowMs())
        if case .whichBike = prompt { prompt = nil }
        if needsAwayCheck(lastUsedAtMs: target.lastUsedAtMs, nowMs: wall.nowMs()), let bike = dao.bike(id) {
            prompt = .odometerNow(bike: bike, estimateKm: estimateNow(id))
        }
    }

    /// The rider's reading for `bikeId`. From the away prompt, a higher value becomes away km; otherwise it's a
    /// correction, which teaches the GPS factor. Returns what was stored.
    @discardableResult
    func enterReading(_ bikeId: String, km: Double, away: Bool = false) -> ReadingDecision {
        guard let bike = dao.bike(bikeId) else { return .unchanged }
        let prev = dao.lastReading(bikeId)
        let gpsM = prev.map { gpsSince(bike, $0) } ?? 0
        let estimate = estimateOdometerKm(prev, factor: bike.odoFactor, gpsMetersSince: gpsM)
        let decision = decideReading(km, estimate: estimate, away: away)
        if case .store(let kind, let awayKm) = decision {
            if kind == .correction, let prev {
                let factor = learnFactor(bike.odoFactor, prevKm: prev.km, newKm: km, gpsKm: gpsM / 1000)
                if factor != bike.odoFactor { dao.setFactor(bikeId, factor) }
            }
            insertReading(dao.bike(bikeId) ?? bike, km: km, kind: kind, awayKm: awayKm)
        }
        return decision
    }

    private func insertReading(_ bike: BikeRecord, km: Double, kind: ReadingKind, awayKm: Double) {
        // A trip running now belongs to the active bike; only its distance from here on counts.
        let offset = bike.active ? (live()?.distanceM ?? 0) : 0
        dao.insertReading(OdometerReading(bikeId: bike.id, timeMs: wall.nowMs(), km: km, kind: kind.rawValue, awayKm: awayKm, tripOffsetM: offset))
    }

    func dismissPrompt() { prompt = nil }

    /// The away prompt's answer (nil = skipped: counters carry on from the app's own km).
    func answerOdometerPrompt(_ km: Double?) {
        guard case .odometerNow(let bike, _) = prompt else { return }
        prompt = nil
        if let km { enterReading(bike.id, km: km, away: true) }
    }

    // MARK: - Which bike (debug builds)

    private var whichBikeTrip: Int64?

    /// At each trip's first fix, if the active bike was last ridden more than `whichBikeKm` away, asks which bike this
    /// is. Once per trip; on the device only (the trip's own fix against a saved trip's end).
    func checkWhichBike(tripStartedAtNanos: Int64?, fix: SpeedSample?) {
        guard let start = tripStartedAtNanos, let fix, whichBikeTrip != start else { return }
        whichBikeTrip = start
        let bikes = dao.allBikes()
        guard let active = bikes.first(where: { $0.active }) else { return }
        let last = dao.lastTrip(active.id).map { GeoPoint($0.endLat, $0.endLon) }
        guard let km = whichBikeDistanceKm(bikeCount: bikes.count, lastRideEnd: last, here: GeoPoint(fix.latitude, fix.longitude)) else { return }
        if prompt == nil { prompt = .whichBike(bikes: bikes, activeId: active.id, distanceKm: km) }
    }

    /// The prompt's answer: switching runs the usual away check for the chosen bike.
    func answerWhichBike(_ id: String) {
        prompt = nil
        activate(id)
    }
}
