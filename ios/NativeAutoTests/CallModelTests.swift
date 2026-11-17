import XCTest
@testable import NativeAuto

final class CallModelTests: XCTestCase {
    func testTheDurationReadsLikeAndroid() {
        XCTAssertEqual(formatCallDuration(-5), "0:00")
        XCTAssertEqual(formatCallDuration(42_900), "0:42")
        XCTAssertEqual(formatCallDuration((12 * 60 + 7) * 1000), "12:07")
        XCTAssertEqual(formatCallDuration((3600 + 2 * 60 + 9) * 1000), "1:02:09")
    }

    func testPhasesFollowTheObserver() {
        XCTAssertEqual(callPhase(CallFacts(outgoing: false, connected: false, onHold: false, ended: false), connectedAtMs: nil), .ringing)
        XCTAssertEqual(callPhase(CallFacts(outgoing: true, connected: false, onHold: false, ended: false), connectedAtMs: nil), .dialling)
        XCTAssertEqual(callPhase(CallFacts(outgoing: false, connected: true, onHold: false, ended: false), connectedAtMs: 5), .active(connectedAtMs: 5))
        XCTAssertNil(callPhase(CallFacts(outgoing: false, connected: true, onHold: false, ended: true), connectedAtMs: 5))
    }

    func testARingingCallComesFirstThenTheOneNotOnHold() {
        let held = CallFacts(outgoing: false, connected: true, onHold: true, ended: false)
        let live = CallFacts(outgoing: true, connected: true, onHold: false, ended: false)
        let ringing = CallFacts(outgoing: false, connected: false, onHold: false, ended: false)
        XCTAssertEqual(pickCall([(held, .active(connectedAtMs: 1)), (ringing, .ringing)]), .ringing)
        XCTAssertEqual(pickCall([(held, .active(connectedAtMs: 1)), (live, .active(connectedAtMs: 2))]), .active(connectedAtMs: 2))
        XCTAssertEqual(pickCall([(held, .active(connectedAtMs: 1))]), .active(connectedAtMs: 1))
        XCTAssertNil(pickCall([]))
    }
}
