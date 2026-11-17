import XCTest
@testable import NativeAuto

/// The shared location source: Fake GPS and the simulated drive reach every open reader, and a switch moves them
/// over mid-stream (Android `SwitchableSpeedSampleSource`).
final class SwitchableSourceTests: XCTestCase {
    private let v = VirtualScheduler()
    private lazy var source: SwitchableSource = {
        SwitchableSource(real: CoreLocationSource(clock: v), fake: FakeSpeedSampleSource(clock: v, scheduler: v))
    }()

    func testFakeGpsReachesASubscriber() {
        source.useFake = true
        var fixes: [SpeedSample] = []
        let sub = source.subscribe { if case .fix(let f) = $0 { fixes.append(f) } }
        v.advance(by: 3_000)
        XCTAssertEqual(fixes.count, 3)
        sub.cancel()
        v.advance(by: 3_000)
        XCTAssertEqual(fixes.count, 3, "nothing after cancel")
    }

    func testASimulatedRouteTakesOverMidStreamAndHandsBack() {
        source.useFake = true
        var fixes: [SpeedSample] = []
        let sub = source.subscribe { if case .fix(let f) = $0 { fixes.append(f) } }
        v.advance(by: 2_000)
        source.simulated = SimulatedDriveSource(route: [GeoPoint(0, 0), GeoPoint(0.01, 0)], clock: v, scheduler: v, speedMps: 10)
        v.advance(by: 2_000)
        XCTAssertEqual(fixes.count, 4)
        XCTAssertEqual(fixes[3].latitude, 20 / metersPerDegreeLat, accuracy: 1e-6, "on the simulated route, not the fake drive")
        source.simulated = nil
        v.advance(by: 1_000)
        XCTAssertEqual(fixes.count, 5)
        XCTAssertEqual(fixes[4].longitude, FakeDrive.startLon, "back on the fake drive")
        sub.cancel()
    }
}
