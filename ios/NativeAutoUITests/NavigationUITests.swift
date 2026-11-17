import CoreLocation
import XCTest

/// Navigation on the simulator against the real services (FOSSGIS Valhalla, Photon), like Android's network-tolerant
/// device tests: a shared link → preview → Start → guidance on the simulated drive → End (two taps), and the search
/// sheet. Each test skips itself when the services can't be reached.
final class NavigationUITests: XCTestCase {
    private var app: XCUIApplication!
    /// Near Malioboro, Yogyakarta; the destination is the Tugu monument about 1.5 km north.
    private let origin = CLLocation(latitude: -7.7956, longitude: 110.3657)
    private let tugu = "geo:0,0?q=-7.7829,110.3671(Tugu%20Yogyakarta)"

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .landscapeLeft
        XCUIDevice.shared.location = XCUILocation(location: origin)
        app = XCUIApplication()
        app.launchArguments = ["-use_fake_gps", "0", "-orientation_mode", "LANDSCAPE", "-layout_preset", "DUO",
                               "-layout_slots", "DUO=MAP,MEDIA,SPEED", "-layout_big_on_left", "1", "-layout_split", "0.5",
                               "-simulate_navigation", "1", "-voice_guidance", "0", "-auto_layout", "1"]
    }

    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id] }

    /// A fresh install asks for location first; the system alert lives in SpringBoard.
    private func launch() {
        app.launch()
        let allow = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Allow While Using App"]
        if allow.waitForExistence(timeout: 4) { allow.tap() }
    }

    /// The route preview, or a skip when the route server can't be reached from here.
    private func waitForPreview() throws {
        let preview = el("nav_preview"), status = el("nav_status")
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            if preview.exists { return }
            if status.exists, app.staticTexts["Can't reach the route server"].exists { throw XCTSkip("Valhalla unreachable (offline?)") }
            if status.exists, app.staticTexts["No route found"].exists { XCTFail("no route to Tugu"); return }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("no route preview within 30 s")
    }

    func testASharedLinkPreviewsGuidesAndEnds() throws {
        app.launchArguments += ["-debug_open_link", tugu]
        launch()
        try waitForPreview()
        XCTAssertTrue(app.staticTexts["Tugu Yogyakarta"].exists, "the link's label names the place")
        el("nav_start").tap()

        // Duo's tall map is Expanded: the banner and the ETA card, no compact chip.
        XCTAssertTrue(el("nav_banner").waitForExistence(timeout: 15))
        XCTAssertTrue(el("nav_summary").exists)
        XCTAssertFalse(el("nav_chip").exists)
        XCTAssertTrue(el("nav_distance").exists)

        // End needs two taps.
        el("nav_end").tap()
        XCTAssertTrue(app.buttons["Tap again to end navigation"].waitForExistence(timeout: 2))
        el("nav_end").tap()
        XCTAssertFalse(el("nav_banner").waitForExistence(timeout: 3))
        XCTAssertTrue(el("map_search").waitForExistence(timeout: 3), "back to idle: the search button returns")
    }

    func testTheNavigationPaneTakesOverTheTurnFromASmallMap() throws {
        // Quad with the map and the Navigation pane: the map is Compact and leaves the turn to the pane.
        app.launchArguments += ["-layout_preset", "QUAD", "-layout_slots", "QUAD=MAP,NAV,SPEED,CLOCK", "-debug_open_link", tugu]
        launch()
        try waitForPreview()
        el("nav_start").tap()
        XCTAssertTrue(el("next_turn_distance").waitForExistence(timeout: 15))
        XCTAssertTrue(el("next_turn_eta").exists)
        XCTAssertFalse(el("nav_chip").exists, "the map hides its chip while the Navigation pane shows the turn")
        XCTAssertFalse(el("nav_banner").exists)
        // The compact map's header: mute and End instead of maximize / fullscreen (13b).
        XCTAssertTrue(el("nav_voice").exists)
        XCTAssertTrue(el("nav_end").exists)
    }

    func testSearchFindsAPlaceAndPreviewsTheRoute() throws {
        launch()
        let search = el("map_search")
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        let field = app.textFields["search_field"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("Tugu Yogyakarta")
        let first = el("result_0")
        if !first.waitForExistence(timeout: 20) {
            if app.staticTexts["Search needs a connection. Try again in a moment."].exists { throw XCTSkip("Photon unreachable") }
            XCTFail("no search results")
        }
        first.tap()
        try waitForPreview()
    }
}
