import XCTest
@testable import NativeAuto

/// Port of a core subset of Android `TripReducerTest`. Same inputs, same expected numbers.
final class TripReducerTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000

    private func fix(_ t: Double, _ speed: Float, lat: Double = 51.0, lon: Double = 0.0,
                     hAcc: Float = 5, speedAcc: Float? = 0.3) -> SpeedSample {
        SpeedSample(timestampNanos: Int64(t * Double(sec)), speedMps: speed, speedAccuracyMps: speedAcc,
                    horizontalAccuracyM: hAcc, latitude: lat, longitude: lon)
    }

    private func fold(_ fixes: SpeedSample..., from: TripStats = TripStats()) -> TripStats {
        fixes.reduce(from, TripReducer.reduce)
    }

    func testStationaryJitterReadsAsZero() {
        let s = fold(fix(0, 0.5), fix(1, 0.7), fix(2, 0.3), fix(3, 0.79))
        XCTAssertEqual(s.currentSpeedMps, 0)
        XCTAssertEqual(s.distanceM, 0)
        XCTAssertEqual(s.movingNanos, 0)
    }

    func testSpikeNeverReachesCurrentOrMax() {
        let s = fold(fix(0, 10), fix(1, 50))
        XCTAssertEqual(s.currentSpeedMps, 10)
        XCTAssertEqual(s.maxSpeedMps, 10)
    }

    func testTwoSpikesInARowCannotSetAFalseMax() {
        let s = fold(fix(0, 10), fix(1, 50), fix(2, 50))
        XCTAssertEqual(s.maxSpeedMps, 10)
    }

    func testPoorSpeedAccuracyDoesNotRaiseMax() {
        let s = fold(fix(0, 10), fix(1, 15, speedAcc: 3))
        XCTAssertEqual(s.currentSpeedMps, 15)
        XCTAssertEqual(s.maxSpeedMps, 10)
    }

    func testPoorHorizontalAccuracyIsRejected() {
        let s = fold(fix(0, 10), fix(1, 12, hAcc: 30))
        XCTAssertEqual(s.currentSpeedMps, 10)
        XCTAssertEqual(s.gpsStatus, .searching)
    }

    func testPositionOnlyFixNeverTouchesTheTrip() {
        var f = fix(0, 10); f.hasSpeed = false
        XCTAssertNil(fold(f).startedAtNanos)
    }

    func testDistanceUsesTheTrapezoidRule() {
        let s = fold(fix(0, 10), fix(1, 12))
        XCTAssertEqual(s.distanceM, 11, accuracy: 1e-9)
    }

    func testGapOverFiveSecondsUsesHaversine() {
        let s = fold(fix(0, 10, lat: 51.0), fix(6, 10, lat: 51.001))
        XCTAssertEqual(s.distanceM, haversineMeters(51.0, 0, 51.001, 0), accuracy: 1e-6)
    }

    func testDuplicateAndOutOfOrderFixesAreIgnored() {
        let a = fold(fix(0, 10), fix(1, 10))
        XCTAssertEqual(fold(fix(1, 10), from: a), a)
        XCTAssertEqual(fold(fix(0.5, 10), from: a), a)
    }

    func testAverageIsDistanceOverElapsed() {
        let s = fold(fix(0, 10), fix(1, 10), fix(2, 10))
        XCTAssertEqual(s.averageSpeedMps(now: 4 * sec), 5, accuracy: 1e-6)
        XCTAssertEqual(s.movingAverageMps, 10, accuracy: 1e-6)
    }

    func testFirstFixSetsTheMax() {
        XCTAssertEqual(fold(fix(0, 10)).maxSpeedMps, 10)
    }
}
