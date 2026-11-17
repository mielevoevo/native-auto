import Foundation
import SwiftUI

/// The Garage as the screens see it: the repository (pure, `Core/Garage`) over the database, republished after every
/// write and whenever the live trip moves 10 m (the active bike's odometer runs during a ride). Counterpart of
/// Android's `GarageViewModel` + the repository's Flows.
@MainActor
final class GarageStore: ObservableObject {
    let repo: GarageRepository
    /// Bumped on every change; views that compute from `repo` redraw with it.
    @Published private(set) var revision = 0
    @Published private(set) var bikes: [BikeRecord] = []
    @Published private(set) var card: GarageCardState?
    @Published private(set) var prompt: GaragePrompt?

    private let trip: TripStore
    private var liveKey: [Int64]?

    init(db: GarageDao, trip: TripStore) {
        self.trip = trip
        let tracker = trip.tracker, clock = trip.clock
        let wall = SystemWallClock()
        repo = GarageRepository(dao: db, wall: wall, live: {
            liveTrip(state: tracker.state, stats: tracker.stats, nowNanos: clock.nowNanos(), nowMs: wall.nowMs())
        })
        trip.addTrackerObserver { [weak self] in
            MainActor.assumeIsolated { self?.trackerChanged() }
        }
        changed()
    }

    private func trackerChanged() {
        let t = trip.tracker
        let running = t.state == .running || t.state == .paused
        let key: [Int64]? = running ? [t.stats.startedAtNanos ?? 0, Int64(t.stats.distanceM / 10)] : nil
        #if DEBUG
        // Debug builds only (Android: inferring "home" from location needs a disclosure before a public release).
        if t.state == .running { repo.checkWhichBike(tripStartedAtNanos: t.stats.startedAtNanos, fix: t.stats.lastSample) }
        if repo.prompt != prompt { prompt = repo.prompt }
        #endif
        if key != liveKey { liveKey = key; changed() }
    }

    /// Re-reads everything after a write (or an import).
    func changed() {
        revision += 1
        bikes = repo.bikes
        card = repo.activeCard()
        prompt = repo.prompt
    }

    private func write(_ f: (GarageRepository) -> Void) {
        f(repo)
        changed()
    }

    var active: BikeRecord? { bikes.first { $0.active } }

    // MARK: Writes (each republishes)

    @discardableResult
    func addBike(_ d: BikeDraft, odometerKm: Double?) -> String {
        var id = ""
        write { id = $0.addBike(d, odometerKm: odometerKm) }
        return id
    }
    func updateBike(_ id: String, _ d: BikeDraft) { write { $0.updateBike(id, d) } }
    func deleteBike(_ id: String) { write { $0.deleteBike(id) } }
    func activate(_ id: String) { write { $0.activate(id) } }
    func enterReading(_ id: String, km: Double) { write { $0.enterReading(id, km: km) } }
    func addUntrackedKm(_ id: String, _ km: Double) -> Bool {
        var ok = false
        write { ok = $0.addUntrackedKm(id, km) }
        return ok
    }
    func logRefuel(_ id: String, _ d: RefuelDraft) { write { $0.logRefuel(id, d) } }
    func deleteRefuel(_ id: Int64) { write { $0.deleteRefuel(id) } }
    func setFuelLevel(_ id: String, bars: Int, ofBars: Int, odometerKm: Double) {
        write { $0.setFuelLevel(id, bars: bars, ofBars: ofBars, odometerKm: odometerKm) }
    }
    func addDashKmPerL(_ id: String, kmPerL: Double, tripKm: Double) -> Bool {
        var ok = false
        write { ok = $0.addDashKmPerL(id, kmPerL: kmPerL, tripKm: tripKm) }
        return ok
    }
    func setPrice(_ fuel: FuelType, _ price: Double) { write { $0.setPrice(fuel, price) } }
    func addService(_ id: String, _ d: ServiceDraft) { write { $0.addService(id, d) } }
    func updateService(_ id: Int64, _ d: ServiceDraft) { write { $0.updateService(id, d) } }
    func deleteService(_ id: Int64) { write { $0.deleteService(id) } }
    func markServiceDone(_ id: Int64, odometerKm: Double?, costRp: Double?, note: String?) {
        write { $0.markServiceDone(id, odometerKm: odometerKm, costRp: costRp, note: note) }
    }
    func answerOdometerPrompt(_ km: Double?) { write { $0.answerOdometerPrompt(km) } }
    func answerWhichBike(_ id: String) { write { $0.answerWhichBike(id) } }
    func dismissPrompt() { write { $0.dismissPrompt() } }
}
