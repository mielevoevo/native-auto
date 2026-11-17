import XCTest

/// Black-box checks on the simulator (the counterpart of Android's device tests): the trip life cycle through the log
/// face, drag to swap, the picker after a still hold, and the layout switcher. Each launch starts from a known layout
/// (launch arguments win over saved settings) on the scripted drive.
final class DashboardUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        // The app asks for landscape; the device must agree, or frames and swipes come back rotated.
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
        app.launchArguments = ["-use_fake_gps", "1", "-orientation_mode", "LANDSCAPE", "-layout_preset", "DUO",
                               "-layout_slots", "DUO=MAP,MEDIA,SPEED", "-layout_big_on_left", "1", "-layout_split", "0.5",
                               "-drag_hold_ms", "1000"]
        app.launch()
    }

    private func slot(_ pane: String) -> XCUIElement { app.otherElements["slot_\(pane)"] }

    func testTheTripLifeCycleThroughTheLogFace() {
        let speed = slot("SPEED")
        XCTAssertTrue(speed.waitForExistence(timeout: 10))
        speed.swipeLeft()
        let start = app.buttons["log_start"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        let pause = app.buttons["log_pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 5))
        pause.tap()
        XCTAssertTrue(app.buttons["log_resume"].waitForExistence(timeout: 5))
        app.buttons["log_resume"].tap()
        let end = app.buttons["log_end"]
        XCTAssertTrue(end.waitForExistence(timeout: 5))
        end.tap()
        XCTAssertTrue(app.buttons["Tap again to end"].waitForExistence(timeout: 2), "End needs a second tap")
        app.buttons["log_end"].tap()
        let summary = app.descendants(matching: .any)["trip_summary"]
        XCTAssertTrue(summary.waitForExistence(timeout: 5))
        summary.tap()
        XCTAssertFalse(summary.waitForExistence(timeout: 2))
    }

    func testDraggingTheSpeedCardOntoTheMapSwapsThem() {
        let map = slot("MAP"), speed = slot("SPEED")
        XCTAssertTrue(speed.waitForExistence(timeout: 10))
        let mapFrame = map.frame, speedFrame = speed.frame
        XCTAssertLessThan(mapFrame.minX, speedFrame.minX)
        // Hold past the 1 s lift, then drag onto the map card.
        speed.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6))
            .press(forDuration: 1.4, thenDragTo: map.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
        let moved = NSPredicate { _, _ in abs(self.slot("SPEED").frame.minX - mapFrame.minX) < 2 }
        expectation(for: moved, evaluatedWith: nil)
        waitForExpectations(timeout: 5)
        XCTAssertEqual(slot("MAP").frame.minX, speedFrame.minX, accuracy: 2)
    }

    func testHoldingACardStillOpensThePicker() {
        let media = slot("MEDIA")
        XCTAssertTrue(media.waitForExistence(timeout: 10))
        // Hold 1 s to lift + 0.7 s still to arm, then let go.
        media.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.7)).press(forDuration: 2.2)
        let clock = app.buttons["pick_CLOCK"]
        XCTAssertTrue(clock.waitForExistence(timeout: 5))
        clock.tap()
        XCTAssertTrue(slot("CLOCK").waitForExistence(timeout: 5))
        let shown = NSPredicate { _, _ in self.slot("CLOCK").frame.width > 10 && self.slot("MEDIA").frame.width < 2 }
        expectation(for: shown, evaluatedWith: nil)
        waitForExpectations(timeout: 5)
    }

    func testTheLayoutSwitcherChangesToQuad() {
        let layouts = app.buttons["Layouts"]
        XCTAssertTrue(layouts.waitForExistence(timeout: 10))
        layouts.tap()
        let quad = app.buttons["layout_QUAD"]
        XCTAssertTrue(quad.waitForExistence(timeout: 5))
        quad.tap()
        let fourUp = NSPredicate { _, _ in
            ["MAP", "SPEED", "MEDIA", "CLOCK"].allSatisfy { self.slot($0).frame.width > 10 }
        }
        expectation(for: fourUp, evaluatedWith: nil)
        waitForExpectations(timeout: 5)
    }

    func testTurningThePhoneInAutoGivesAPortraitStackInTheSameOrder() {
        app.terminate()
        // Pin the portrait shape: the last one used wins (14b), and an earlier run may have saved another.
        app.launchArguments += ["-orientation_mode", "AUTO", "-layout_preset_portrait", "STACK3"]
        app.launch()
        XCTAssertTrue(slot("SPEED").waitForExistence(timeout: 10))
        XCUIDevice.shared.orientation = .portrait
        let stacked = NSPredicate { _, _ in
            let m = self.slot("MAP").frame, d = self.slot("MEDIA").frame, s = self.slot("SPEED").frame
            return m.width > d.height && m.maxY <= d.minY + 1 && d.maxY <= s.minY + 1 && abs(m.width - s.width) < 1
        }
        expectation(for: stacked, evaluatedWith: nil)
        waitForExpectations(timeout: 6)
        XCUIDevice.shared.orientation = .landscapeLeft
    }
}
