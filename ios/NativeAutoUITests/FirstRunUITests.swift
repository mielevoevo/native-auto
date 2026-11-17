import XCTest

/// 15e on the simulator: the first-run pages, the changelog after an update, the settings pages, and the call card
/// (`-debug_call` stands in for CallKit, which the simulator can't ring).
final class FirstRunUITests: XCTestCase {
    private var app: XCUIApplication!

    private let base = ["-use_fake_gps", "1", "-orientation_mode", "LANDSCAPE", "-layout_preset", "DUO",
                        "-layout_slots", "DUO=MAP,MEDIA,SPEED", "-layout_big_on_left", "1", "-layout_split", "0.5"]

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        app = XCUIApplication()
    }

    private func any(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    func testTheFirstRunPagesLeadToTheDashboard() {
        app.launchArguments = ["-setup_done", "0", "-changelog_seen_code", "999"] + base
        app.launch()
        XCTAssertTrue(any("setup_page_0").waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["setup_back"].exists, "the welcome page has Next only")
        app.buttons["setup_next"].tap()
        XCTAssertTrue(any("setup_page_1").waitForExistence(timeout: 3))
        app.buttons["setup_back"].tap()
        XCTAssertTrue(any("setup_page_0").waitForExistence(timeout: 3))
        app.buttons["setup_next"].tap()
        app.buttons["setup_next"].tap()
        // Location page: Next without location asks first.
        XCTAssertTrue(any("setup_page_2").waitForExistence(timeout: 3))
        app.buttons["setup_next"].tap()
        let go = app.buttons["Continue"]
        if go.waitForExistence(timeout: 3) { go.tap() }
        for page in 3...5 {
            XCTAssertTrue(any("setup_page_\(page)").waitForExistence(timeout: 3), "page \(page)")
            XCTAssertTrue(app.buttons["setup_next"].isHittable, "Next in view on page \(page)")
            app.buttons["setup_next"].tap()
        }
        XCTAssertTrue(any("setup_page_6").waitForExistence(timeout: 3))
        app.buttons["setup_finish"].tap()
        XCTAssertTrue(app.otherElements["slot_SPEED"].waitForExistence(timeout: 5), "the dashboard after Start riding")
    }

    func testTheChangelogShowsOnceAfterAnUpdate() {
        app.launchArguments = ["-setup_done", "1", "-changelog_seen_code", "4"] + base
        app.launch()
        XCTAssertTrue(any("changelog_sheet").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["0.5 · 2026-11-17"].exists)
        XCTAssertFalse(app.staticTexts["0.4 · 2026-11-17"].exists, "only what's new since the last version seen")
        app.buttons["changelog_done"].tap()
        XCTAssertTrue(app.otherElements["slot_SPEED"].waitForExistence(timeout: 5))
        XCTAssertFalse(any("changelog_sheet").exists)
    }

    func testSettingsPages() {
        app.launchArguments = ["-setup_done", "1"] + base
        app.launch()
        app.buttons["Settings"].tap()
        // Opens on Trip and Garage.
        XCTAssertTrue(any("settings_page_TRIP_GARAGE").waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["settings_garage"].exists)
        // Display: the route line gets its own colour once "Follow the accent" is off.
        app.buttons["settings_cat_DISPLAY"].tap()
        XCTAssertTrue(any("settings_page_DISPLAY").waitForExistence(timeout: 3))
        let follow = app.switches["route_follow"]
        for _ in 0..<4 where !follow.isHittable { app.swipeUp() }
        // Settings persist in the simulator: start from "follow" whatever an earlier run left.
        if app.buttons["route_preset_green"].exists { follow.switches.firstMatch.tap() }
        XCTAssertTrue(app.buttons["route_preset_green"].waitForNonExistence(timeout: 3))
        follow.switches.firstMatch.tap()
        XCTAssertTrue(app.buttons["route_preset_green"].waitForExistence(timeout: 3))
        follow.switches.firstMatch.tap()   // back to following the accent
        // What's new: the whole file.
        for _ in 0..<4 where !app.buttons["settings_whats_new"].isHittable { app.swipeDown() }
        app.buttons["settings_whats_new"].tap()
        XCTAssertTrue(app.staticTexts["0.1 · 2026-11-16"].waitForExistence(timeout: 3))
        app.buttons["changelog_done"].tap()
        // Audio: the call card switch; Privacy: Reset app needs two taps.
        app.buttons["settings_cat_AUDIO"].tap()
        XCTAssertTrue(app.switches["settings_call_controls"].waitForExistence(timeout: 3))
        app.buttons["settings_cat_PRIVACY"].tap()
        let reset = app.buttons["settings_reset_app"]
        XCTAssertTrue(reset.waitForExistence(timeout: 3))
        reset.tap()
        XCTAssertTrue(app.buttons["Tap again: delete everything"].waitForExistence(timeout: 2))
        // Not the second tap: it would delete the simulator's data and close the app.
    }

    func testACallTakesTheMediaSlotAndShowsAsAPillOverFullscreen() {
        app.launchArguments = ["-setup_done", "1", "-debug_call", "ACTIVE"] + base
        app.launch()
        let call = app.otherElements["slot_CALL"]
        XCTAssertTrue(call.waitForExistence(timeout: 10), "the call card takes a slot")
        XCTAssertFalse(app.otherElements["slot_MEDIA"].isHittable, "in media's place")
        XCTAssertTrue(app.staticTexts["call_status"].exists)
        XCTAssertTrue(any("call_duration").exists)
        // The call never covers a fullscreen card: the speed card goes fullscreen, the call becomes the pill.
        app.otherElements["slot_SPEED"].buttons["Fullscreen"].tap()
        XCTAssertTrue(any("call_pill").waitForExistence(timeout: 5))
    }
}
