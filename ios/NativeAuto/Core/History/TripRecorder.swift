import Foundation

// Port of Android `history/TripRecorder.kt` + `TripCheckpoint.kt` (9a–11b). It watches the tracker from outside
// (`observe` is called on every tracker change), so the tracker stays unaware of storage.

/// A short, human address for a trip's start or end. Nil when offline or nothing is known; callers retry later.
protocol AddressResolver: AnyObject {
    func addressOf(_ point: GeoPoint, _ done: @escaping (String?) -> Void)
}

/// The figures of the trip in progress at `savedAtNanos` (monotonic, same boot only) and `savedAtMs` (wall clock).
struct LiveTripRecord: Equatable, Codable {
    var savedAtMs: Int64
    var savedAtNanos: Int64
    var startedAtNanos: Int64
    var distanceM: Double
    var maxSpeedMps: Float
    var movingNanos: Int64
    var pausedNanos: Int64
    var pausedAtNanos: Int64?
    var lastMovingAtNanos: Int64?
    var autoPaused: Bool
    var paused: Bool
}

/// A stored checkpoint: the figures and the fixes so far (oldest first).
struct Checkpoint: Equatable {
    var trip: LiveTripRecord
    var fixes: [SpeedSample]
}

/// Storage for the trip in progress; a file in the app, in memory in the tests.
protocol TripCheckpoints: AnyObject {
    /// `replace`: a new trip's first checkpoint drops whatever an older one left.
    func write(_ trip: LiveTripRecord, newFixes: [SpeedSample], replace: Bool)
    func read() -> Checkpoint?
    func clear()
}

/// What `TripRecorder.recover` found.
enum Recovery { case none, resumed, savedEnded }

/// The fate of an ended trip, for the end-of-trip summary.
enum TripOutcome: Equatable {
    case saved(TripRecord)
    /// Under the short-trip limits (or no usable path): not kept.
    case tooShort
}

extension TripStats {
    func toCheckpoint(paused: Bool, savedAtMs: Int64, savedAtNanos: Int64, startedAtNanos: Int64) -> LiveTripRecord {
        LiveTripRecord(savedAtMs: savedAtMs, savedAtNanos: savedAtNanos, startedAtNanos: startedAtNanos, distanceM: distanceM,
                       maxSpeedMps: maxSpeedMps, movingNanos: movingNanos, pausedNanos: pausedNanos, pausedAtNanos: pausedAtNanos,
                       lastMovingAtNanos: lastMovingAtNanos, autoPaused: autoPaused, paused: paused)
    }
}

extension LiveTripRecord {
    /// The figures back, with `last` as the reducer's last good fix (the next fix joins it in a straight line).
    func toStats(last: SpeedSample?) -> TripStats {
        TripStats(startedAtNanos: startedAtNanos, lastSample: last, currentSpeedMps: 0, maxSpeedMps: maxSpeedMps, distanceM: distanceM,
                  movingNanos: movingNanos, gpsStatus: .searching, pausedNanos: pausedNanos, pausedAtNanos: pausedAtNanos,
                  lastMovingAtNanos: lastMovingAtNanos, autoPaused: autoPaused)
    }
}

/// Writes each finished trip to the history:
/// - the path is the fixes the reducer *accepted* (each new `lastSample`), so it matches the card's distance;
/// - a trip is saved when it ends (End, auto-end, swiped away), tagged with the active bike at save time (11a);
/// - a discarded trip (Reset) is dropped; very short trips aren't kept (under `minDistanceM` or `minMovingMs`);
/// - the trip in progress is checkpointed about once a minute and at each pause / resume (11b), so a process killed
///   mid-ride can be resumed or saved on the next launch (`recover`).
final class TripRecorder {
    static let minDistanceMDefault = 300.0
    static let minMovingMsDefault: Int64 = 120_000
    static let minStepM = 3.0
    static let checkpointEveryNanos: Int64 = 60_000_000_000
    /// A lost trip whose last checkpoint is younger than this is resumed; older, it's saved as ended.
    static let resumeWithinMs: Int64 = 10 * 60_000
    static let simplifyToleranceM = 4.0

    /// The short-trip limits; tests lower them to check the path geometry on short drives.
    var minDistanceM = TripRecorder.minDistanceMDefault
    var minMovingMs = TripRecorder.minMovingMsDefault

    /// What happened to the last ended trip (the summary): saved, or too short. Nil while a trip is in progress.
    private(set) var lastOutcome: TripOutcome? { didSet { if oldValue != lastOutcome { onOutcome?() } } }
    /// Called when `lastOutcome` changes, and after any write to the history.
    var onOutcome: (() -> Void)?
    var onSaved: (() -> Void)?

    private let tracker: TripTracker
    private let dao: TripDao
    private let addresses: AddressResolver
    private let bike: () -> String?
    private let checkpoints: TripCheckpoints
    private let clock: MonotonicClock
    private let wall: WallClock

    private final class Recording {
        let startedAtNanos: Int64
        var points: [GeoPoint] = []
        /// Every accepted fix, unsimplified: the full track (9c).
        var fixes: [SpeedSample] = []
        var stats: TripStats?
        var lastSample: SpeedSample?
        var saved = false
        var checkpointed = 0
        var checkpointAtNanos: Int64
        var checkpointedPaused = false

        init(_ startedAtNanos: Int64) { self.startedAtNanos = startedAtNanos; checkpointAtNanos = startedAtNanos }

        func add(_ sample: SpeedSample) {
            lastSample = sample
            fixes.append(sample)
            let p = GeoPoint(sample.latitude, sample.longitude)
            if let prev = points.last, haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude) < TripRecorder.minStepM { return }
            points.append(p)
        }
    }

    private var rec: Recording?

    init(tracker: TripTracker, dao: TripDao, addresses: AddressResolver, bike: @escaping () -> String?, checkpoints: TripCheckpoints,
         clock: MonotonicClock, wall: WallClock) {
        self.tracker = tracker; self.dao = dao; self.addresses = addresses; self.bike = bike
        self.checkpoints = checkpoints; self.clock = clock; self.wall = wall
    }

    private func worthKeeping(_ s: TripStats) -> Bool { s.distanceM >= minDistanceM && s.movingNanos / 1_000_000 >= minMovingMs }

    /// Call after every tracker change (state or stats).
    func observe() {
        let state = tracker.state
        let stats = tracker.stats
        let key = stats.startedAtNanos
        if let r = rec, r.startedAtNanos != key {
            // Cleared without ending: discarded (Reset), so nothing is saved.
            rec = nil
            if !r.saved { checkpoints.clear() }
        }
        guard let key else { return }
        let r: Recording
        if let existing = rec { r = existing } else {
            r = Recording(key)
            rec = r
            lastOutcome = nil
        }
        r.stats = stats
        if let sample = stats.lastSample, sample != r.lastSample { r.add(sample) }
        if state == .ended && !r.saved {
            r.saved = true
            save(r, endNanos: stats.endedAtNanos ?? clock.nowNanos())
            checkpoints.clear()
        } else if state == .running || state == .paused {
            let paused = state == .paused
            let now = clock.nowNanos()
            if now - r.checkpointAtNanos >= Self.checkpointEveryNanos || paused != r.checkpointedPaused {
                checkpoint(r, stats, paused: paused, nowNanos: now)
            }
        }
    }

    private func checkpoint(_ r: Recording, _ stats: TripStats, paused: Bool, nowNanos: Int64) {
        r.checkpointAtNanos = nowNanos
        r.checkpointedPaused = paused
        let from = r.checkpointed
        let newFixes = Array(r.fixes[from...])
        r.checkpointed = r.fixes.count
        checkpoints.write(stats.toCheckpoint(paused: paused, savedAtMs: wall.nowMs(), savedAtNanos: nowNanos, startedAtNanos: r.startedAtNanos),
                          newFixes: newFixes, replace: from == 0)
    }

    private var recoveryChecked = false

    /// 11b, when the app becomes active (once per process). A trip the process lost mid-ride is resumed when its last
    /// checkpoint is under `resumeWithinMs` old and from this boot, otherwise saved as ended at the last checkpointed
    /// point. Nothing happens while a trip is already in progress. The caller restarts tracking on `.resumed`.
    func recover() -> Recovery {
        if recoveryChecked { return .none }
        recoveryChecked = true
        guard tracker.state == .idle, let cp = checkpoints.read() else { return .none }
        let row = cp.trip
        let nowNanos = clock.nowNanos()
        let nowMs = wall.nowMs()
        let sameBoot = nowNanos >= row.savedAtNanos
        let r = Recording(row.startedAtNanos)
        cp.fixes.forEach(r.add)
        r.checkpointed = cp.fixes.count
        r.checkpointAtNanos = sameBoot ? row.savedAtNanos : nowNanos
        r.checkpointedPaused = row.paused
        let stats = row.toStats(last: cp.fixes.last)
        r.stats = stats
        if sameBoot, nowMs - row.savedAtMs >= 0, nowMs - row.savedAtMs < Self.resumeWithinMs {
            rec = r
            tracker.restore(stats, paused: row.paused)
            return .resumed
        }
        // Too old, or the phone rebooted: the trip ends where it was last saved.
        let endNanos = row.pausedAtNanos ?? cp.fixes.last?.timestampNanos ?? row.savedAtNanos
        var ended = stats
        ended.endedAtNanos = endNanos
        r.stats = ended
        save(r, endNanos: endNanos, refNanos: row.savedAtNanos, refMs: row.savedAtMs, report: false)
        checkpoints.clear()
        return .savedEnded
    }

    /// `refNanos` / `refMs`: one instant on both clocks, to turn monotonic times into wall times. `report`: set
    /// `lastOutcome` for the summary (not for a recovered trip).
    private func save(_ r: Recording, endNanos: Int64, refNanos: Int64? = nil, refMs: Int64? = nil, report: Bool = true) {
        r.saved = true
        guard let s = r.stats else { return }
        let refN = refNanos ?? clock.nowNanos()
        let refM = refMs ?? wall.nowMs()
        // The last accepted fix may have been skipped as too close; the path still ends there.
        if let last = r.lastSample {
            let end = GeoPoint(last.latitude, last.longitude)
            if r.points.last != end { r.points.append(end) }
        }
        guard worthKeeping(s), r.points.count >= 2 else {
            if report { lastOutcome = .tooShort }
            return
        }
        func wallMs(_ nanos: Int64) -> Int64 { refM - (refN - nanos) / 1_000_000 }
        let track = simplify(r.points, toleranceM: Self.simplifyToleranceM)
        let start = r.points.first!, end = r.points.last!
        let elapsed = s.elapsedNanos(now: endNanos)
        var record = TripRecord(
            startedAtMs: wallMs(r.startedAtNanos), endedAtMs: wallMs(endNanos), durationMs: elapsed / 1_000_000,
            movingMs: s.movingNanos / 1_000_000, distanceM: s.distanceM, maxSpeedMps: s.maxSpeedMps,
            startLat: start.latitude, startLon: start.longitude, endLat: end.latitude, endLon: end.longitude,
            track: Polyline.encode(track), stoppedMs: max(elapsed - s.movingNanos, 0) / 1_000_000,
            // 11a: the bike selected last (a switch mid-trip moves the whole trip to it).
            bikeId: bike())
        let full = r.fixes.map {
            TrackPointRecord(timeMs: wallMs($0.timestampNanos), latitude: $0.latitude, longitude: $0.longitude, speedMps: $0.speedMps,
                             bearingDeg: $0.bearingDeg, accuracyM: $0.horizontalAccuracyM)
        }
        let id = dao.insertWithTrack(record, full)
        record.id = id
        if report { lastOutcome = .saved(record) }
        onSaved?()
        resolve(id, start, end, knownStart: nil, knownEnd: nil) {}
    }

    /// Retries addresses that couldn't be looked up (offline at the time). The history calls this on open.
    func fillMissingAddresses() {
        var queue = dao.missingAddresses()
        func next() {
            guard !queue.isEmpty else { return }
            let t = queue.removeFirst()
            resolve(t.id, GeoPoint(t.startLat, t.startLon), GeoPoint(t.endLat, t.endLon), knownStart: t.startAddress, knownEnd: t.endAddress, then: next)
        }
        next()
    }

    private func resolve(_ id: Int64, _ start: GeoPoint, _ end: GeoPoint, knownStart: String?, knownEnd: String?, then: @escaping () -> Void) {
        func lookup(_ known: String?, _ p: GeoPoint, _ done: @escaping (String?) -> Void) {
            if let known { done(known) } else { addresses.addressOf(p, done) }
        }
        lookup(knownStart, start) { [weak self] s in
            lookup(knownEnd, end) { e in
                guard let self else { return }
                if s != knownStart || e != knownEnd {
                    self.dao.setAddresses(id, start: s, end: e)
                    self.onSaved?()
                }
                then()
            }
        }
    }
}
