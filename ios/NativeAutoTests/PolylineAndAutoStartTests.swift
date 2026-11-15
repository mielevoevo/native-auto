import XCTest
@testable import NativeAuto

final class PolylineTests: XCTestCase {
    /// Google's reference example: the Android app writes exactly this text too.
    func testGoogleReferenceExample() {
        let pts = [GeoPoint(latitude: 38.5, longitude: -120.2),
                   GeoPoint(latitude: 40.7, longitude: -120.95),
                   GeoPoint(latitude: 43.252, longitude: -126.453)]
        XCTAssertEqual(Polyline.encode(pts), "_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        XCTAssertEqual(Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@"), pts)
    }

    func testSimplifyDropsAStraightLineMiddle() {
        let pts = (0...10).map { GeoPoint(latitude: 0, longitude: Double($0) * 0.001) }
        XCTAssertEqual(simplify(pts, toleranceM: 4), [pts.first!, pts.last!])
    }
}

final class AutoStartTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000
    private func fix(_ t: Int64, _ kmh: Float) -> SpeedSample {
        SpeedSample(timestampNanos: t * sec, speedMps: kmh / 3.6, speedAccuracyMps: 0.3,
                    horizontalAccuracyM: 5, latitude: 0, longitude: 0)
    }

    func testHoldTriggersAfterTheHoldTime() {
        var st = AutoStartState(), fired = false
        for t in Int64(0)...20 {
            let r = stepAutoStart(st, fix(t, 30), holdNanos: 20 * sec)
            st = r.state; fired = r.triggered
        }
        XCTAssertTrue(fired)
    }

    func testASlowFixStartsTheHoldOver() {
        var st = AutoStartState()
        for t in Int64(0)...10 { st = stepAutoStart(st, fix(t, 30), holdNanos: 20 * sec).state }
        st = stepAutoStart(st, fix(11, 5), holdNanos: 20 * sec).state
        XCTAssertNil(st.heldSinceNanos)
    }

    func testOnlyFromACleanIdleState() {
        XCTAssertTrue(autoStartAllowed(.trip, .idle, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.trip, .paused, endedThisSession: false, cleanAtOpen: true))
        XCTAssertFalse(autoStartAllowed(.off, .idle, endedThisSession: false, cleanAtOpen: true))
    }
}
