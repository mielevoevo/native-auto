import XCTest

/// 15d on the simulator: a bike added from the Garage card, a fill-up giving a range, and a short scripted trip saved
/// to the history, opened, and deleted with two taps. Each launch starts from an empty database (`-reset_data 1`).
final class GarageHistoryUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
        app.launchArguments = ["-reset_data", "1", "-use_fake_gps", "1", "-orientation_mode", "LANDSCAPE", "-layout_preset", "DUO",
                               "-layout_slots", "DUO=MAP,GARAGE,SPEED", "-layout_big_on_left", "1", "-layout_split", "0.5",
                               "-debug_min_trip_m", "10", "-debug_min_moving_ms", "1000"]
        app.launch()
    }

    private func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    private func type(_ field: String, _ text: String) {
        let f = app.textFields[field]
        XCTAssertTrue(f.waitForExistence(timeout: 5), field)
        f.tap()
        if let old = f.value as? String, !old.isEmpty {
            f.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count))
        }
        f.typeText(text)
    }

    func testABikeFromTheGarageCardGetsARangeAfterAFillUp() {
        let open = app.buttons["garage_card_open"]
        XCTAssertTrue(open.waitForExistence(timeout: 10))
        open.tap()
        XCTAssertTrue(any("garage_screen").waitForExistence(timeout: 5))
        app.buttons["garage_add"].firstMatch.tap()
        type("garage_name", "Gen 1")
        type("garage_odometer", "12000")
        app.buttons["garage_editor_save"].tap()
        let odo = any("garage_odometer_value")
        XCTAssertTrue(odo.waitForExistence(timeout: 5))
        XCTAssertEqual(odo.label, "12,000 km")
        XCTAssertTrue(any("garage_riding_Gen 1").exists)

        app.buttons["garage_refuel"].tap()
        type("garage_refuel_amount", "50000")
        app.buttons["garage_refuel_dialog_save"].tap()
        let range = any("garage_range")
        XCTAssertTrue(range.waitForExistence(timeout: 5))
        // A full refill fills the 6.6 L tank: 6.6 × 36 km/L, rounded down to 5 km.
        XCTAssertEqual(range.label, "235 km")

        app.buttons["garage_back"].tap()
        let value = any("garage_card_value")
        XCTAssertTrue(value.waitForExistence(timeout: 5))
        XCTAssertEqual(value.label, "235 km")
    }

    func testAShortScriptedTripIsSavedOpenedAndDeleted() {
        let speed = app.otherElements["slot_SPEED"]
        XCTAssertTrue(speed.waitForExistence(timeout: 10))
        speed.swipeLeft()
        app.buttons["log_start"].tap()
        XCTAssertTrue(app.buttons["log_pause"].waitForExistence(timeout: 5))
        sleep(8)   // the scripted drive covers well over 10 m
        app.buttons["log_end"].tap()
        app.buttons["log_end"].tap()
        let summary = any("trip_summary")
        XCTAssertTrue(summary.waitForExistence(timeout: 5))
        XCTAssertFalse(any("summary_too_short").exists, "the trip is long enough to keep")
        summary.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.1)).tap()

        app.buttons["Settings"].tap()
        let history = app.buttons["settings_history"]
        XCTAssertTrue(history.waitForExistence(timeout: 5))
        for _ in 0..<6 where !history.isHittable { app.swipeUp() }
        history.tap()
        let trip = app.buttons["history_trip"]
        XCTAssertTrue(trip.waitForExistence(timeout: 8))
        trip.tap()
        let delete = app.buttons["history_delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["history_export"].exists)
        delete.tap()
        XCTAssertTrue(app.buttons["Tap again to delete"].waitForExistence(timeout: 2))
        app.buttons["history_delete"].tap()
        XCTAssertTrue(app.staticTexts["No trips yet"].waitForExistence(timeout: 5))
    }
}
