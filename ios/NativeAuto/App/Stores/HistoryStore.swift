import CoreLocation
import Foundation
import SwiftUI

/// The trip history as the screens see it: the recorder (saves each ended trip, checkpoints the one in progress),
/// the saved trips, the end-of-trip outcome, GPX export and the ZIP backup. Counterpart of Android's `TripRecorder`
/// wiring, `TripHistoryViewModel` and `TripFiles`.
@MainActor
final class HistoryStore: ObservableObject {
    @Published private(set) var trips: [TripRecord] = []
    /// The last ended trip's fate (the summary); nil while a trip runs.
    @Published private(set) var outcome: TripOutcome?
    /// A short status line on the history screen ("Imported 3 trips").
    @Published var message: String?
    /// The dash km/L given for a saved trip (one answer per trip, 12b).
    @Published private(set) var dashGiven: [Int64: Double] = [:]

    let db: AppDatabase
    let recorder: TripRecorder
    private let garage: GarageStore

    init(db: AppDatabase, trip: TripStore, garage: GarageStore) {
        self.db = db
        self.garage = garage
        recorder = TripRecorder(tracker: trip.tracker, dao: db, addresses: TripAddressLookup(), bike: { [weak garage] in
            MainActor.assumeIsolated { garage?.repo.activeId() }
        }, checkpoints: FileTripCheckpoints(), clock: trip.clock, wall: SystemWallClock())
        recorder.onOutcome = { [weak self] in MainActor.assumeIsolated { self?.outcome = self?.recorder.lastOutcome } }
        recorder.onSaved = { [weak self] in MainActor.assumeIsolated { self?.reload() } }
        trip.addTrackerObserver { [weak self] in MainActor.assumeIsolated { self?.recorder.observe() } }
        #if DEBUG
        // UI tests: `-debug_min_trip_m 10 -debug_min_moving_ms 1000` save a few seconds of the scripted drive.
        let d = UserDefaults.standard
        if d.object(forKey: "debug_min_trip_m") != nil { recorder.minDistanceM = d.double(forKey: "debug_min_trip_m") }
        if d.object(forKey: "debug_min_moving_ms") != nil { recorder.minMovingMs = Int64(d.integer(forKey: "debug_min_moving_ms")) }
        #endif
        reload()
    }

    func reload() {
        trips = db.allTrips()
        garage.changed()   // a saved trip moves the odometer
    }

    /// 11b: a trip the process lost mid-ride is resumed or saved (once per launch).
    func recover() {
        if recorder.recover() != .none { reload() }
    }

    func fillMissingAddresses() { recorder.fillMissingAddresses() }

    func delete(_ id: Int64) {
        db.delete(id)
        reload()
    }

    /// The bike's own km/L for a saved trip, from its dash: trains the Garage's consumption.
    func giveDashKmPerL(_ trip: TripRecord, _ kmPerL: Double) {
        guard let bike = trip.bikeId ?? garage.repo.activeId() else { return }
        _ = garage.addDashKmPerL(bike, kmPerL: kmPerL, tripKm: trip.distanceM / 1000)
        dashGiven[trip.id] = kmPerL
    }

    /// Metres of the saved trips in `period`, plus the trip in progress (12b, "Today 42.3 km").
    func periodMeters(_ period: TotalPeriod, liveM: Double) -> Double {
        periodDistanceM(trips, period, nowMs: SystemWallClock().nowMs(), zone: .current) + liveM
    }

    // MARK: Files

    /// A fresh folder for one export (the previous one is cleared, like Android's `cache/exports`).
    private func exportDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func gpxFile(_ trip: TripRecord) -> URL? {
        let url = exportDir().appendingPathComponent(gpxFileName(trip))
        do {
            try Data(buildGpx(trip, db.points(trip.id)).utf8).write(to: url)
            return url
        } catch {
            message = "Export failed: \(error.localizedDescription)"
            return nil
        }
    }

    /// "native-auto-backup-2026-11-17.zip": every trip with its full track, and the Garage.
    func backupFile() -> URL? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        let url = exportDir().appendingPathComponent("native-auto-backup-\(f.string(from: Date())).zip")
        let all = db.allTrips().map { Backup.TripWithTrack(trip: $0, points: db.points($0.id)) }
        do {
            try Backup.write(all, garage: GarageBackup.collect(db)).write(to: url)
            return url
        } catch {
            message = "Backup failed: \(error.localizedDescription)"
            return nil
        }
    }

    /// Reads a picked backup and merges it (the Garage first, so imported trips find their bikes).
    func importBackup(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let contents = try Backup.readAll(try Data(contentsOf: url))
            let garageAdded = contents.garage.map { GarageBackup.restore(db, $0) } ?? 0
            let added = Backup.restore(db, contents.trips)
            reload()
            let trips = added == 0 ? nil : (added == 1 ? "1 trip" : "\(added) trips")
            let parts = [trips, garageAdded == 0 ? nil : "Garage data"].compactMap { $0 }
            message = parts.isEmpty ? "Nothing new in that backup" : "Imported " + parts.joined(separator: " and ")
            if added > 0 { fillMissingAddresses() }
        } catch {
            message = "Import failed: \(error)"
        }
    }
}

/// Trip addresses: Apple's geocoder first (free, no key, like Android's platform `Geocoder`), Photon as a fallback.
final class TripAddressLookup: AddressResolver {
    private let places = PlaceSearch()

    func addressOf(_ point: GeoPoint, _ done: @escaping (String?) -> Void) {
        Task { @MainActor in done(await self.lookup(point)) }
    }

    private func lookup(_ point: GeoPoint) async -> String? {
        let loc = CLLocation(latitude: point.latitude, longitude: point.longitude)
        if let m = try? await CLGeocoder().reverseGeocodeLocation(loc).first,
           let s = shortAddress(street: m.thoroughfare ?? m.name, area: m.subLocality ?? m.locality ?? m.subAdministrativeArea, line: nil) {
            return s
        }
        guard let p = await places.photonReverse(point) else { return nil }
        return shortAddress(street: p.name, area: p.detail?.components(separatedBy: ", ").first, line: nil)
    }
}
