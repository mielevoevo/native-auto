import XCTest
@testable import NativeAuto

/// Port of Android `TripTrackerTest`: the real fake-drive script through `TripTracker` in virtual time.
final class TripTrackerTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000
    private var vt: VirtualScheduler!

    override func setUp() { vt = VirtualScheduler() }

    private func tracker(_ source: SpeedSampleSource? = nil) -> TripTracker {
        TripTracker(source: source ?? FakeSpeedSampleSource(clock: vt, scheduler: vt), clock: vt, scheduler: vt)
    }

    private var loopMs: Int64 { Int64(FakeDrive.loopSeconds) * 1_000 }

    /// Ground-truth distance of one loop, from the script's true speeds.
    private func trueLoopDistance() -> Double {
        var prev: Float = 0, d = 0.0
        for step in FakeDrive.script {
            switch step {
            case let .fix(_, trueSpeed, _, _): d += Double(prev + trueSpeed) / 2; prev = trueSpeed
            case let .gap(s): d += Double(prev) * Double(s)
            }
        }
        return d
    }

    func testOneLoopOfTheFakeDriveGivesTheExpectedTrip() {
        let t = tracker()
        t.start()
        vt.advance(by: loopMs)
        let s = t.stats
        XCTAssertEqual(s.startedAtNanos, 0)
        XCTAssertEqual(s.maxSpeedMps, FakeDrive.cruiseMps, accuracy: 1e-4)
        XCTAssertEqual(s.currentSpeedMps, 0)
        XCTAssertEqual(s.gpsStatus, .fix)
        XCTAssertEqual(s.distanceM, trueLoopDistance(), accuracy: 0.05)
        let moving = Double(s.movingNanos) / 1e9
        XCTAssertTrue((70.0...78.0).contains(moving), "moving \(moving) s")
        XCTAssertGreaterThan(s.movingAverageMps, s.averageSpeedMps(now: loopMs * 1_000_000))
    }

    func testTheTunnelLeavesTheFixStaleThenHaversineCatchesUp() {
        let t = tracker()
        t.start()
        let before = FakeDrive.script.firstIndex { if case .gap = $0 { return true } else { return false } }!
        vt.advance(by: Int64(before) * 1_000 + 4_000)
        let inTunnel = t.stats
        XCTAssertGreaterThan(vt.nowNanos() - inTunnel.lastSample!.timestampNanos, 3 * sec)
        vt.advance(by: Int64(FakeDrive.tunnelSeconds - 4 + 1) * 1_000)
        XCTAssertEqual(t.stats.distanceM - inTunnel.distanceM, Double(FakeDrive.cruiseMps) * 9, accuracy: 1)
    }

    func testEndHaltsCollectionAndDiscardZeroesTheTrip() {
        let t = tracker()
        t.start()
        XCTAssertEqual(t.state, .running)
        vt.advance(by: 30_000)
        t.end()
        XCTAssertEqual(t.state, .ended)
        let frozen = t.stats
        vt.advance(by: 10_000)
        XCTAssertEqual(t.stats, frozen)
        XCTAssertEqual(frozen.elapsedNanos(now: vt.nowNanos()), 30 * sec)
        t.discard()
        XCTAssertEqual(t.stats, TripStats())
        XCTAssertEqual(t.state, .idle)
    }

    func testPauseFreezesAndResumeExcludesThePausedTimeAndDistance() {
        let source = TestSource()
        let t = tracker(source)
        var lat = 51.0
        func emit() { source.emit(SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 20, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: lat, longitude: 0)) }
        func drive(_ seconds: Int) { for _ in 0..<seconds { vt.advance(by: 1_000); lat += 20 / metersPerDegreeLat; emit() } }
        t.start(); emit()
        drive(10)
        XCTAssertEqual(t.stats.distanceM, 200, accuracy: 1e-6)
        t.pause()
        XCTAssertEqual(t.state, .paused)
        for _ in 0..<60 { vt.advance(by: 1_000); lat += 20 / metersPerDegreeLat }
        XCTAssertEqual(t.stats.elapsedNanos(now: vt.nowNanos()), 10 * sec)
        t.resume()
        XCTAssertEqual(t.state, .running)
        XCTAssertNil(t.stats.lastSample, "resume starts a new segment")
        emit()
        drive(10)
        let s = t.stats
        XCTAssertEqual(s.distanceM, 400, accuracy: 1e-6)
        XCTAssertEqual(s.movingNanos, 20 * sec)
        XCTAssertEqual(s.elapsedNanos(now: vt.nowNanos()), 20 * sec)
        XCTAssertEqual(s.averageSpeedMps(now: vt.nowNanos()), 20, accuracy: 1e-4)
    }

    func testOpeningKeepsAPauseEndFreezesAtThePauseAndTheNextStartIsNew() {
        let source = TestSource()
        let t = tracker(source)
        t.start()
        vt.advance(by: 30_000)
        t.pause()
        vt.advance(by: 5_000)
        t.start()
        XCTAssertEqual(t.state, .paused)
        XCTAssertEqual(source.subscriptions, 1)
        vt.advance(by: 5_000)
        t.end()
        XCTAssertEqual(t.state, .ended)
        vt.advance(by: 60_000)
        XCTAssertEqual(t.stats.elapsedNanos(now: vt.nowNanos()), 30 * sec)
        t.start()
        XCTAssertEqual(t.state, .running)
        XCTAssertEqual(t.stats.startedAtNanos, vt.nowNanos())
        XCTAssertEqual(t.stats.elapsedNanos(now: vt.nowNanos()), 0)
        XCTAssertEqual(source.subscriptions, 2)
    }

    func testPauseResumeAndEndAreIgnoredWhenTheyDontApply() {
        let t = tracker(TestSource())
        t.resume(); t.pause(); t.end()
        XCTAssertEqual(t.state, .idle)
        XCTAssertEqual(t.stats, TripStats())
        t.start()
        t.resume()
        XCTAssertEqual(t.state, .running)
    }

    func testDiscardWhileTrackingDropsTheTripAndStopsTheGps() {
        let source = TestSource()
        let t = tracker(source)
        t.start()
        vt.advance(by: 1_000)
        source.emit(SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 20, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 51, longitude: 0))
        t.discard()
        XCTAssertEqual(t.state, .idle)
        XCTAssertEqual(t.stats, TripStats())
        XCTAssertNil(t.lastFix)
        source.emit(SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 20, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 51.001, longitude: 0))
        XCTAssertEqual(t.stats, TripStats())
        t.resume()
        XCTAssertEqual(t.state, .idle)
    }

    func testPauseByAutoPauseIsMarkedAndResumeClearsIt() {
        let t = tracker()
        t.start()
        t.pause(auto: true)
        XCTAssertTrue(t.stats.autoPaused)
        vt.advance(by: 5_000)
        t.resume()
        XCTAssertFalse(t.stats.autoPaused)
        XCTAssertEqual(t.stats.lastMovingAtNanos, vt.nowNanos())
    }

    func testLocationSwitchedOffShowsOffAndRecovers() {
        let source = TestSource()
        source.failure = .disabled; source.enabled = false
        let t = tracker(source)
        t.start()
        XCTAssertEqual(t.stats.gpsStatus, .disabled)
        XCTAssertEqual(t.state, .running)
        vt.advance(by: TripTracker.retryDisabledMs * 3)
        XCTAssertEqual(t.stats.gpsStatus, .disabled)
        XCTAssertGreaterThanOrEqual(source.subscriptions, 3)
        source.failure = nil; source.enabled = true
        vt.advance(by: TripTracker.retryDisabledMs)
        XCTAssertEqual(t.stats.gpsStatus, .searching)
        source.emit(SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 10, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 51, longitude: 0))
        XCTAssertEqual(t.stats.gpsStatus, .fix)
        XCTAssertEqual(t.stats.currentSpeedMps, 10)
    }

    func testMissingPermissionEndsTrackingAndALaterStartRecovers() {
        let source = TestSource()
        source.failure = .permissionDenied
        let t = tracker(source)
        t.start()
        XCTAssertEqual(t.stats.gpsStatus, .permissionDenied)
        XCTAssertEqual(t.state, .ended)
        vt.advance(by: 60_000)
        XCTAssertEqual(source.subscriptions, 1, "no retry loop without access")
        source.failure = nil
        t.start()
        XCTAssertEqual(t.state, .running)
        XCTAssertEqual(t.stats.gpsStatus, .searching)
        XCTAssertEqual(source.subscriptions, 2)
    }

    func testOtherSourceErrorsAreRetried() {
        let source = TestSource()
        source.failure = .other
        let t = tracker(source)
        t.start()
        XCTAssertEqual(t.state, .running)
        source.failure = nil
        vt.advance(by: TripTracker.retryErrorMs)
        source.emit(SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 10, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 51, longitude: 0))
        XCTAssertEqual(t.stats.gpsStatus, .fix)
        XCTAssertEqual(source.subscriptions, 2)
    }

    func testStartIsIdempotent() {
        let t = tracker()
        t.start()
        vt.advance(by: 10_000)
        t.start()
        vt.advance(by: 10_000)
        XCTAssertEqual(t.stats.startedAtNanos, 0)
        XCTAssertLessThanOrEqual(t.stats.movingNanos, 20 * sec)
    }

    func testRestoreContinuesARunningTripOrKeepsAPause() {
        let saved = TripStats(startedAtNanos: 0, maxSpeedMps: 20, distanceM: 1_000, pausedAtNanos: nil)
        let t = tracker(TestSource())
        vt.advance(by: 5_000)
        t.restore(saved, paused: false)
        XCTAssertEqual(t.state, .running)
        XCTAssertEqual(t.stats.distanceM, 1_000)
        XCTAssertEqual(t.stats.lastMovingAtNanos, vt.nowNanos())
        let p = tracker(TestSource())
        p.restore(saved, paused: true)
        XCTAssertEqual(p.state, .paused)
        XCTAssertNotNil(p.stats.pausedAtNanos)
    }
}

/// Fails like the real source would, as scripted by the test. Port of the Kotlin `FlakySource`.
final class TestSource: SpeedSampleSource {
    var failure: SourceFailure?
    var enabled = true
    private(set) var subscriptions = 0
    private var sinks: [Int: (SourceEvent) -> Void] = [:]
    private var nextId = 0

    var isLocationEnabled: Bool { enabled }

    private final class Sub: Cancellable {
        let cancelBlock: () -> Void
        init(_ c: @escaping () -> Void) { cancelBlock = c }
        func cancel() { cancelBlock() }
    }

    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable {
        subscriptions += 1
        if let failure { onEvent(.failure(failure)); return Sub {} }
        nextId += 1
        let id = nextId
        sinks[id] = onEvent
        return Sub { [weak self] in self?.sinks[id] = nil }
    }

    func emit(_ fix: SpeedSample) { for s in sinks.values { s(.fix(fix)) } }
}

/// Port of Android `AutoTripTest`.
final class AutoTripTests: XCTestCase {
    private let min: Int64 = 60_000_000_000

    func testRunningWithoutMovementForTheLimitPauses() {
        let s = TripStats(startedAtNanos: 0, lastMovingAtNanos: 2 * min)
        XCTAssertNil(autoAction(.running, s, nowNanos: 7 * min - 1, pauseMin: 5, endMin: 30))
        XCTAssertEqual(autoAction(.running, s, nowNanos: 7 * min, pauseMin: 5, endMin: 30), .pause)
    }

    func testNoMovingFixSinceTheStartCountsFromTheStart() {
        let s = TripStats(startedAtNanos: 10 * min)
        XCTAssertNil(autoAction(.running, s, nowNanos: 14 * min, pauseMin: 5, endMin: 30))
        XCTAssertEqual(autoAction(.running, s, nowNanos: 15 * min, pauseMin: 5, endMin: 30), .pause)
    }

    func testPausedForTheLimitEnds() {
        let s = TripStats(startedAtNanos: 0, pausedAtNanos: 10 * min)
        XCTAssertNil(autoAction(.paused, s, nowNanos: 39 * min, pauseMin: 5, endMin: 30))
        XCTAssertEqual(autoAction(.paused, s, nowNanos: 40 * min, pauseMin: 5, endMin: 30), .end)
    }

    func testZeroTurnsEachRuleOff() {
        XCTAssertNil(autoAction(.running, TripStats(startedAtNanos: 0), nowNanos: 600 * min, pauseMin: 0, endMin: 30))
        XCTAssertNil(autoAction(.paused, TripStats(startedAtNanos: 0, pausedAtNanos: 0), nowNanos: 600 * min, pauseMin: 5, endMin: 0))
    }

    func testIdleAndEndedTripsAreLeftAlone() {
        let s = TripStats(startedAtNanos: 0, pausedAtNanos: 0)
        XCTAssertNil(autoAction(.idle, s, nowNanos: 600 * min, pauseMin: 5, endMin: 30))
        XCTAssertNil(autoAction(.ended, s, nowNanos: 600 * min, pauseMin: 5, endMin: 30))
    }

    /// The driver: a trip left standing pauses after 5 min and ends 30 min later, checked every 10 s.
    func testTheDriverPausesThenEndsATripLeftStanding() {
        let vt = VirtualScheduler()
        let source = TestSource()
        let t = TripTracker(source: source, clock: vt, scheduler: vt)
        let auto = AutoTrip(tracker: t, clock: vt, scheduler: vt) { (5, 30) }
        t.onChange = { auto.update() }
        t.start()
        vt.advance(by: 5 * 60_000 - 1)
        XCTAssertEqual(t.state, .running)
        vt.advance(by: 10_000)
        XCTAssertEqual(t.state, .paused)
        XCTAssertTrue(t.stats.autoPaused)
        vt.advance(by: 30 * 60_000 + 10_000)
        XCTAssertEqual(t.state, .ended)
    }
}
