import Foundation

/// Why a source stopped. Port of Android's `LocationPermissionException` / `LocationDisabledException`.
enum SourceFailure: Error, Equatable {
    /// No location access: terminal until the rider grants it.
    case permissionDenied
    /// Location services are off: worth retrying.
    case disabled
    /// Anything else: retried after a pause.
    case other
}

enum SourceEvent {
    case fix(SpeedSample)
    /// The subscription is over after a failure; the tracker decides whether to subscribe again.
    case failure(SourceFailure)
}

/// A stream of GPS fixes. Subscribing starts updates; cancelling stops them. Port of `SpeedSampleSource`.
protocol SpeedSampleSource: AnyObject {
    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable
    /// Whether a retry after `.disabled` can succeed now (location switched back on).
    var isLocationEnabled: Bool { get }
}

/// Port of Android `trip/TripTracker.kt`: the trip's life cycle (Idle / Running / Paused / Ended) and the fold of
/// fixes into `TripStats`. Source failures never escape; they become a `GpsStatus`:
/// - disabled → `.disabled`, retried every `retryDisabledMs` until location is back on;
/// - permission denied → `.permissionDenied`, the trip ends;
/// - anything else → retried after `retryErrorMs`.
final class TripTracker {
    static let retryDisabledMs: Int64 = 3_000
    static let retryErrorMs: Int64 = 5_000

    private(set) var stats = TripStats() { didSet { onChange?() } }
    private(set) var state: TripState = .idle { didSet { if oldValue != state { onChange?() } } }
    /// The newest fix as received, before filtering. Only updates while running.
    private(set) var lastFix: SpeedSample?

    /// Called after every change of `stats` or `state`.
    var onChange: (() -> Void)?

    private let source: SpeedSampleSource
    private let clock: MonotonicClock
    private let scheduler: TaskScheduler
    private var subscription: Cancellable?
    private var retry: Cancellable?
    private var generation = 0

    init(source: SpeedSampleSource, clock: MonotonicClock, scheduler: TaskScheduler) {
        self.source = source
        self.clock = clock
        self.scheduler = scheduler
    }

    /// Idempotent. Running → no-op. Paused → stays paused (only `resume` continues). Ended → a fresh trip.
    /// Idle → the trip clock starts now.
    func start() {
        switch state {
        case .running, .paused: return
        case .ended: stats = TripStats()
        case .idle: break
        }
        var s = stats
        s.startedAtNanos = s.startedAtNanos ?? clock.nowNanos()
        // A previous denial is stale once tracking is (re)started with access.
        if s.gpsStatus == .permissionDenied { s.gpsStatus = .searching }
        stats = s
        collect()
    }

    /// Stops the GPS and freezes the trip clock. No-op unless running. `auto` marks a pause by auto-pause.
    func pause(auto: Bool = false) {
        guard state == .running else { return }
        cancelCollection()
        var s = stats
        s.pausedAtNanos = clock.nowNanos()
        s.autoPaused = auto
        stats = s
        state = .paused
    }

    /// Continues a paused trip: the paused time is excluded and the last fix forgotten, so wherever the bike went
    /// while paused adds no distance.
    func resume() {
        guard state == .paused else { return }
        let now = clock.nowNanos()
        var s = stats
        s.pausedNanos += now - (s.pausedAtNanos ?? now)
        s.lastMovingAtNanos = now   // auto-pause counts from the resume
        s.pausedAtNanos = nil
        s.autoPaused = false
        s.lastSample = nil
        s.currentSpeedMps = 0
        s.gpsStatus = .searching
        stats = s
        collect()
    }

    /// Ends the trip: the GPS stops and every figure freezes (at the pause, if paused).
    func end() {
        cancelCollection()
        guard state == .running || state == .paused else { return }
        var s = stats
        s.endedAtNanos = s.pausedAtNanos ?? clock.nowNanos()
        stats = s
        state = .ended
    }

    /// Reset = discard: the trip is dropped without saving and the tracker goes back to Idle.
    func discard() {
        cancelCollection()
        lastFix = nil
        stats = TripStats()
        state = .idle
    }

    /// Trip recovery (11b): continues a trip from a checkpoint. No-op unless Idle.
    func restore(_ saved: TripStats, paused: Bool) {
        guard state == .idle, saved.startedAtNanos != nil else { return }
        let now = clock.nowNanos()
        var s = saved
        s.endedAtNanos = nil
        s.pausedAtNanos = paused ? (saved.pausedAtNanos ?? now) : nil
        s.autoPaused = paused && saved.autoPaused
        s.lastMovingAtNanos = paused ? saved.lastMovingAtNanos : now
        s.currentSpeedMps = 0
        s.gpsStatus = .searching
        stats = s
        if paused { state = .paused } else { collect() }
    }

    // MARK: - Collection

    private func collect() {
        state = .running
        subscribe()
    }

    private func subscribe() {
        generation += 1
        let gen = generation
        let sub = source.subscribe { [weak self] event in
            guard let self, gen == self.generation else { return }
            self.handle(event)
        }
        // A synchronous failure may already have moved on to a new generation.
        if gen == generation { subscription = sub } else { sub.cancel() }
    }

    private func handle(_ event: SourceEvent) {
        switch event {
        case .fix(let fix):
            lastFix = fix
            stats = TripReducer.reduce(stats, fix)
        case .failure(let failure):
            subscription = nil
            generation += 1
            switch failure {
            case .permissionDenied:
                var s = stats
                s.gpsStatus = .permissionDenied
                s.endedAtNanos = clock.nowNanos()
                stats = s
                state = .ended
            case .disabled:
                setStatus(.disabled)
                retry = scheduler.schedule(afterMs: Self.retryDisabledMs) { [weak self] in
                    guard let self, self.state == .running else { return }
                    // Only leave "off" when the retry can work, so the tile doesn't flicker on every attempt.
                    if self.source.isLocationEnabled { self.setStatus(.searching) }
                    self.subscribe()
                }
            case .other:
                retry = scheduler.schedule(afterMs: Self.retryErrorMs) { [weak self] in
                    guard let self, self.state == .running else { return }
                    self.subscribe()
                }
            }
        }
    }

    private func cancelCollection() {
        generation += 1
        subscription?.cancel(); subscription = nil
        retry?.cancel(); retry = nil
    }

    private func setStatus(_ status: GpsStatus) {
        var s = stats
        s.gpsStatus = status
        stats = s
    }
}
