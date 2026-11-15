import XCTest
@testable import NativeAuto

/// Port of Android `TripReducerTest`: same inputs, same expected numbers.
final class TripReducerTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000

    private func fix(_ t: Double, _ speed: Float, lat: Double = 51.0, lon: Double = 0.0, hAcc: Float = 5,
                     speedAcc: Float? = 0.3) -> SpeedSample {
        SpeedSample(timestampNanos: Int64(t * Double(sec)), speedMps: speed, speedAccuracyMps: speedAcc,
                    horizontalAccuracyM: hAcc, latitude: lat, longitude: lon)
    }

    private func fold(_ fixes: [SpeedSample], from: TripStats = TripStats()) -> TripStats { fixes.reduce(from, TripReducer.reduce) }
    private func fold(_ fixes: SpeedSample..., from: TripStats = TripStats()) -> TripStats { fold(fixes, from: from) }

    func testStationaryJitterReadsAsZeroAndAddsNothing() {
        let s = fold(fix(0, 0.5), fix(1, 0.7), fix(2, 0.3), fix(3, 0.79))
        XCTAssertEqual(s.currentSpeedMps, 0); XCTAssertEqual(s.distanceM, 0)
        XCTAssertEqual(s.movingNanos, 0); XCTAssertEqual(s.maxSpeedMps, 0)
    }

    func testSpeedAtTheStationaryThresholdCountsAsMoving() {
        let s = fold(fix(0, 0.8), fix(1, 0.8))
        XCTAssertEqual(s.currentSpeedMps, 0.8); XCTAssertEqual(s.movingNanos, sec)
    }

    func testASpeedSpikeDoesNotChangeMax() {
        XCTAssertEqual(fold(fix(0, 20), fix(1, 20), fix(2, 50), fix(3, 20)).maxSpeedMps, 20)
    }

    func testASpeedSpikeNeverReachesTheCurrentSpeed() {
        let before = fold(fix(0, 20), fix(1, 20))
        let after = TripReducer.reduce(before, fix(2, 50))
        XCTAssertEqual(before, after); XCTAssertEqual(after.currentSpeedMps, 20)
    }

    func testASpikeAddsNoDistanceAndTheNextGoodFixCatchesUp() {
        let s = fold(fix(0, 20), fix(1, 20), fix(2, 50), fix(3, 20))
        XCTAssertEqual(s.distanceM, 60, accuracy: 1e-9)
        XCTAssertEqual(s.movingNanos, 3 * sec)
        XCTAssertEqual(s.lastSample!.timestampNanos, 3 * sec)
    }

    func testTwoSpikesInARowCannotSetAFalseMax() {
        let s = fold(fix(0, 27.78), fix(1, 27.78), fix(2, 55), fix(3, 56), fix(4, 27.78))
        XCTAssertEqual(s.maxSpeedMps, 27.78); XCTAssertEqual(s.currentSpeedMps, 27.78)
        XCTAssertEqual(s.distanceM, 27.78 * 4, accuracy: 1e-3)
    }

    func testHardButRealBrakingIsNeverRejected() {
        let speeds: [Float] = [27.78, 18.78, 9.78, 0.78]
        let s = fold(speeds.enumerated().map { fix(Double($0.offset), $0.element) })
        XCTAssertEqual(s.currentSpeedMps, 0)
        XCTAssertEqual(s.lastSample!.timestampNanos, 3 * sec)
    }

    func testTheFilterCannotLockOntoABadValue() {
        let start = fold(fix(0, 27.78), fix(1, 27.78))
        let t2 = TripReducer.reduce(start, fix(2, 0))
        let t3 = TripReducer.reduce(t2, fix(3, 0))
        let t4 = TripReducer.reduce(t3, fix(4, 0))
        XCTAssertEqual(t2.currentSpeedMps, 27.78); XCTAssertEqual(t3.currentSpeedMps, 27.78)
        XCTAssertEqual(t4.currentSpeedMps, 0); XCTAssertEqual(t4.lastSample!.timestampNanos, 4 * sec)
    }

    func testRejectedFixesLeaveTheLastGoodFixInPlace() {
        let good = fold(fix(0, 20), fix(1, 20))
        let s = fold(fix(2, 60), fix(3, 60), fix(4, 62), from: good)
        XCTAssertEqual(good.lastSample, s.lastSample); XCTAssertEqual(s.currentSpeedMps, 20)
    }

    func testAPlausibleAccelerationRaisesMax() { XCTAssertEqual(fold(fix(0, 20), fix(1, 20), fix(2, 31)).maxSpeedMps, 31) }

    func testPoorSpeedAccuracyDoesNotRaiseMax() {
        let s = fold(fix(0, 20), fix(1, 20), fix(2, 25, speedAcc: 2))
        XCTAssertEqual(s.maxSpeedMps, 20); XCTAssertEqual(s.currentSpeedMps, 25)
    }

    func testNoSpeedAccuracyIsTrustedForMax() { XCTAssertEqual(fold(fix(0, 20), fix(1, 22, speedAcc: nil)).maxSpeedMps, 22) }

    func testPoorHorizontalAccuracyIsRejected() {
        let before = fold(fix(0, 10), fix(1, 10))
        let after = TripReducer.reduce(before, fix(2, 30, hAcc: 40))
        var expected = before; expected.gpsStatus = .searching
        XCTAssertEqual(after, expected)
    }

    func testAPositionOnlyFixNeverTouchesTheTrip() {
        let before = fold(fix(0, 10), fix(1, 10))
        var f = fix(2, 0); f.hasSpeed = false
        var expected = before; expected.gpsStatus = .searching
        XCTAssertEqual(TripReducer.reduce(before, f), expected)
    }

    func testATripCannotStartOnAPositionOnlyFix() {
        var f = fix(0, 0); f.hasSpeed = false
        let after = fold(f)
        XCTAssertNil(after.lastSample); XCTAssertNil(after.startedAtNanos)
    }

    func testAccuracyExactlyAtTheLimitIsAccepted() {
        let s = fold(fix(0, 10), fix(1, 10, hAcc: TripReducer.maxAccuracyM))
        XCTAssertEqual(s.gpsStatus, .fix); XCTAssertEqual(s.distanceM, 10, accuracy: 1e-9)
    }

    func testDistanceUsesTheTrapezoidRule() { XCTAssertEqual(fold(fix(0, 10), fix(2, 20)).distanceM, 30, accuracy: 1e-9) }

    func testAGapOverFiveSecondsUsesHaversine() {
        let a = fix(0, 20, lat: 51.0), b = fix(10, 20, lat: 51.001)
        let s = fold(a, b)
        let expected = haversineMeters(a, b)
        XCTAssertEqual(s.distanceM, expected, accuracy: 1e-9)
        XCTAssertEqual(expected, 111.2, accuracy: 0.1)
    }

    func testAGapOfExactlyFiveSecondsStillUsesTheTrapezoid() {
        XCTAssertEqual(fold(fix(0, 20, lat: 51.0), fix(5, 20, lat: 51.5)).distanceM, 100, accuracy: 1e-9)
    }

    func testDuplicateAndOutOfOrderFixesAreIgnored() {
        let a = fold(fix(0, 10), fix(1, 12))
        XCTAssertEqual(TripReducer.reduce(a, fix(1, 30)), a)
        let b = fold(fix(0, 10), fix(2, 12))
        XCTAssertEqual(TripReducer.reduce(b, fix(1, 30)), b)
    }

    private func cruiseThenPark() -> [SpeedSample] {
        (0...10).map { fix(Double($0), 20) } + [fix(11, 10), fix(12, 0), fix(60, 0)]
    }

    func testAverageIsDistanceOverElapsedNotAMeanOfSamples() {
        let fixes = cruiseThenPark()
        let s = fold(fixes)
        XCTAssertEqual(s.distanceM, 220, accuracy: 1e-9)
        XCTAssertEqual(s.averageSpeedMps(now: 60 * sec), Float(220.0 / 60.0), accuracy: 1e-5)
        let mean = fixes.map(\.speedMps).reduce(0, +) / Float(fixes.count)
        XCTAssertGreaterThan(mean, 15)
    }

    func testAverageKeepsFallingWhileParked() {
        let s = fold(fix(0, 10), fix(5, 10))
        XCTAssertEqual(s.averageSpeedMps(now: 5 * sec), 10, accuracy: 1e-6)
        XCTAssertEqual(s.averageSpeedMps(now: 10 * sec), 5, accuracy: 1e-6)
    }

    func testMovingAverageExcludesStoppedTime() {
        let s = fold(cruiseThenPark())
        XCTAssertEqual(s.movingNanos, 11 * sec)
        XCTAssertEqual(s.movingAverageMps, 20, accuracy: 1e-5)
        XCTAssertGreaterThan(s.movingAverageMps, s.averageSpeedMps(now: 60 * sec))
    }

    func testFirstFixSetsStartSpeedAndStatusButNoDistance() {
        let s = TripReducer.reduce(TripStats(), fix(5, 15))
        XCTAssertEqual(s.startedAtNanos, 5 * sec); XCTAssertEqual(s.currentSpeedMps, 15)
        XCTAssertEqual(s.gpsStatus, .fix); XCTAssertEqual(s.distanceM, 0)
    }

    func testAnExistingStartTimeIsKept() { XCTAssertEqual(TripReducer.reduce(TripStats(startedAtNanos: 1), fix(5, 15)).startedAtNanos, 1) }

    func testAveragesAreZeroBeforeOneSecond() {
        let s = fold(fix(0, 10), fix(0.5, 10))
        XCTAssertEqual(s.averageSpeedMps(now: sec / 2), 0); XCTAssertEqual(s.movingAverageMps, 0)
    }

    func testTheFirstFixSetsTheMaxToo() { XCTAssertEqual(TripReducer.reduce(TripStats(), fix(5, 20)).maxSpeedMps, 20) }

    func testAFirstFixWithPoorSpeedAccuracyLeavesTheMaxAlone() {
        let s = TripReducer.reduce(TripStats(), fix(5, 20, speedAcc: 3))
        XCTAssertEqual(s.maxSpeedMps, 0); XCTAssertEqual(s.currentSpeedMps, 20)
    }

    func testAResumeKeepsTheEarlierMaxAndTakesAFasterFirstFix() {
        let resumed = TripStats(startedAtNanos: 1, maxSpeedMps: 15)
        XCTAssertEqual(TripReducer.reduce(resumed, fix(9, 10)).maxSpeedMps, 15)
        XCTAssertEqual(TripReducer.reduce(resumed, fix(9, 25)).maxSpeedMps, 25)
    }
}

final class PolylineTests: XCTestCase {
    func testGoogleReferenceExample() {
        let pts = [GeoPoint(latitude: 38.5, longitude: -120.2), GeoPoint(latitude: 40.7, longitude: -120.95),
                   GeoPoint(latitude: 43.252, longitude: -126.453)]
        XCTAssertEqual(Polyline.encode(pts), "_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        XCTAssertEqual(Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"), pts)
    }

    func testSimplifyDropsAStraightLineMiddle() {
        let pts = (0...10).map { GeoPoint(latitude: 0, longitude: Double($0) * 0.001) }
        XCTAssertEqual(simplify(pts, toleranceM: 4), [pts.first!, pts.last!])
    }
}

/// Port of Android `AutoStartTest`.
final class AutoStartTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000
    private var hold: Int64 { 30 * sec }
    private let ride: Float = 5.5

    private func fix(_ t: Int, _ speed: Float, hAcc: Float = 5, speedAcc: Float? = 0.3, hasSpeed: Bool = true) -> SpeedSample {
        SpeedSample(timestampNanos: Int64(t) * sec, speedMps: speed, speedAccuracyMps: speedAcc, horizontalAccuracyM: hAcc,
                    latitude: -7.8, longitude: 110.0 + Double(t) * 0.00005, hasSpeed: hasSpeed)
    }

    private func run(_ from: Int, _ to: Int, _ speed: Float, _ start: AutoStartState = AutoStartState()) -> (Int?, AutoStartState) {
        var s = start
        for t in from...to {
            let step = stepAutoStart(s, fix(t, speed), holdNanos: hold)
            s = step.state
            if step.triggered { return (t, s) }
        }
        return (nil, s)
    }

    func testThirtySecondsAtRidingSpeedTriggerNotBefore() { XCTAssertEqual(run(0, 60, ride).0, 30) }

    func testTheThresholdIs15KmPerHour() {
        XCTAssertEqual(AutoStartConfig.mps, 4.1667, accuracy: 1e-3)
        XCTAssertNil(run(0, 90, 4.1).0)
        XCTAssertEqual(run(0, 90, 4.2).0, 30)
    }

    func testDroppingBelowTheLineStartsTheHoldOver() {
        var s = run(0, 20, ride).1
        s = stepAutoStart(s, fix(21, 1), holdNanos: hold).state
        XCTAssertNil(s.heldSinceNanos)
        XCTAssertEqual(run(22, 80, ride, s).0, 52)
    }

    func testFixesWithoutSpeedOrWithPoorAccuracyNeitherHelpNorReset() {
        var s = run(0, 10, ride).1
        s = stepAutoStart(s, fix(11, 0, hasSpeed: false), holdNanos: hold).state
        s = stepAutoStart(s, fix(12, 0, hAcc: 80), holdNanos: hold).state
        s = stepAutoStart(s, fix(13, 0, speedAcc: 4), holdNanos: hold).state
        XCTAssertEqual(s.heldSinceNanos, 0)
        XCTAssertEqual(run(14, 60, ride, s).0, 30)
    }

    func testAGapInTheFixesBreaksTheHold() {
        let s = run(0, 20, ride).1
        XCTAssertEqual(run(36, 100, ride, s).0, 66)
    }

    func testTheKmRiddenDuringTheHoldAreCounted() {
        let (at, s) = run(0, 60, ride)
        XCTAssertEqual(at, 30)
        XCTAssertEqual(s.stats.distanceM, 30 * Double(ride), accuracy: 1)
    }

    func testAllowedOnlyFromACleanState() {
        XCTAssertTrue(autoStartAllowed(.trip, .idle, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.off, .idle, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.trip, .running, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.trip, .paused, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.trip, .idle, endedThisSession: true, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.trip, .idle, endedThisSession: false, cleanAtOpen: false))
    }

    func testHoldChoicesAndTheDefault() {
        XCTAssertEqual(AutoStartConfig.holdChoicesS, [20, 30, 45, 60])
        XCTAssertTrue(AutoStartConfig.holdChoicesS.contains(AutoStartConfig.defaultHoldS))
    }
}
