import Foundation

/// What auto-pause / auto-end want done now. Port of Android `trip/AutoTrip.kt`.
enum AutoAction { case pause, end }

enum AutoTripConfig {
    static let defaultPauseMin = 5
    static let defaultEndMin = 30
    /// How often the rules are checked while a trip is running or paused.
    static let checkMs: Int64 = 10_000
    static let pauseChoicesMin = [0, 3, 5, 10]
    static let endChoicesMin = [0, 15, 30, 60]
}

private let nanosPerMinute: Int64 = 60_000_000_000

/// Running, below walking pace for `pauseMin` → pause; paused for `endMin` → end. 0 = off.
/// "Below walking pace" counts from the last moving fix, or from the start / resume.
func autoAction(_ state: TripState, _ stats: TripStats, nowNanos: Int64, pauseMin: Int, endMin: Int) -> AutoAction? {
    switch state {
    case .running:
        guard pauseMin > 0, let since = stats.lastMovingAtNanos ?? stats.startedAtNanos else { return nil }
        return nowNanos - since >= Int64(pauseMin) * nanosPerMinute ? .pause : nil
    case .paused:
        guard endMin > 0, let since = stats.pausedAtNanos else { return nil }
        return nowNanos - since >= Int64(endMin) * nanosPerMinute ? .end : nil
    case .idle, .ended:
        return nil
    }
}

/// Applies `autoAction` to a tracker every `checkMs` while a trip is running or paused.
final class AutoTrip {
    private let tracker: TripTracker
    private let clock: MonotonicClock
    private let scheduler: TaskScheduler
    private let limits: () -> (pauseMin: Int, endMin: Int)
    private var timer: Cancellable?

    init(tracker: TripTracker, clock: MonotonicClock, scheduler: TaskScheduler, limits: @escaping () -> (pauseMin: Int, endMin: Int)) {
        self.tracker = tracker
        self.clock = clock
        self.scheduler = scheduler
        self.limits = limits
    }

    /// Call whenever the trip state changes: the check runs only while a trip is running or paused.
    func update() {
        let active = tracker.state == .running || tracker.state == .paused
        if active, timer == nil { arm() }
        if !active { timer?.cancel(); timer = nil }
    }

    private func arm() {
        timer = scheduler.schedule(afterMs: AutoTripConfig.checkMs) { [weak self] in
            guard let self else { return }
            self.timer = nil
            let l = self.limits()
            switch autoAction(self.tracker.state, self.tracker.stats, nowNanos: self.clock.nowNanos(), pauseMin: l.pauseMin, endMin: l.endMin) {
            case .pause: self.tracker.pause(auto: true)
            case .end: self.tracker.end()
            case nil: break
            }
            self.update()
        }
    }
}
