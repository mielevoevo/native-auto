import XCTest
@testable import NativeAuto

/// Port of Android `AccentColorTest` (the DataStore migration cases aren't ported: the iOS app has no install from
/// before the accent setting, so it simply starts cyan).
final class AccentColorTests: XCTestCase {
    func testHexParsesWithOrWithoutTheHashShortOrLongAnyCase() {
        XCTAssertEqual(parseHexColor("#3DDC84"), RGB(hex: 0x3DDC84))
        XCTAssertEqual(parseHexColor("3ddc84"), RGB(hex: 0x3DDC84))
        XCTAssertEqual(parseHexColor(" #abc "), RGB(hex: 0xAABBCC))
        XCTAssertNil(parseHexColor(""))
        XCTAssertNil(parseHexColor("#12345"))
        XCTAssertNil(parseHexColor("#GG0000"))
        XCTAssertNil(parseHexColor("#1234567"))
    }

    func testHexRoundTrips() {
        XCTAssertEqual(RGB(hex: 0x3DDC84).hexString, "#3DDC84")
        XCTAssertEqual(RGB.black.hexString, "#000000")
        for p in accentPresets { XCTAssertEqual(parseHexColor(p.dark.hexString), p.dark) }
    }

    func testContrastRatioIsTheWcagOne() {
        XCTAssertEqual(contrastRatio(.black, .white), 21, accuracy: 0.01)
        XCTAssertEqual(contrastRatio(.white, .white), 1, accuracy: 0.001)
        XCTAssertEqual(contrastRatio(RGB(hex: 0x3DDC84), .black), contrastRatio(.black, RGB(hex: 0x3DDC84)), accuracy: 1e-9)
    }

    func testEveryPresetReadsOnBlackAndItsLightVariantOnWhite() {
        for p in accentPresets {
            XCTAssertGreaterThanOrEqual(contrastRatio(p.dark, .black), minAccentContrast, p.key)
            XCTAssertGreaterThanOrEqual(contrastRatio(p.light, .white), minAccentContrast, p.key)
        }
    }

    func testTheLightVariantDarkensAPaleColour() {
        let pale = RGB(hex: 0x8CC8FF)
        let light = lightVariant(pale)
        XCTAssertGreaterThanOrEqual(contrastRatio(light, .white), lightTargetContrast)
        XCTAssertLessThan(light.luminance, pale.luminance)
        XCTAssertEqual(lightVariant(RGB(hex: 0x0B7A3E)), RGB(hex: 0x0B7A3E))
    }

    func testTheDarkVariantLightensAColourThatVanishesOnBlack() {
        XCTAssertGreaterThanOrEqual(contrastRatio(darkVariant(RGB(hex: 0x0A1F44)), .black), minAccentContrast)
        XCTAssertEqual(darkVariant(RGB(hex: 0x3DDC84)), RGB(hex: 0x3DDC84))
    }

    func testAPresetKeepsItsHandPickedLightVariant() {
        let green = accentPresets.first { $0.key == "green" }!
        XCTAssertEqual(accentFor(green.dark, isDark: true), green.dark)
        XCTAssertEqual(accentFor(green.dark, isDark: false), green.light)
        let custom = RGB(hex: 0xFFE066)
        XCTAssertEqual(accentFor(custom, isDark: false), lightVariant(custom))
    }

    func testTheHexFieldWarnsAboutColoursHardToSee() {
        XCTAssertEqual(accentWarnings(RGB(hex: 0x3DDC84)).count, 1)
        XCTAssertTrue(accentWarnings(RGB(hex: 0x101030)).contains { $0.contains("black") })
        XCTAssertTrue(accentWarnings(RGB(hex: 0xFFFF00)).contains { $0.contains("white") })
    }

    func testEveryInstallStartsCyan() {
        XCTAssertEqual(defaultAccent.key, "cyan")
        XCTAssertEqual(defaultAccent.dark.argb, 0xFF3FC8E0)
    }
}

/// Port of Android `EdgeGeometryTest`.
final class EdgeGeometryTests: XCTestCase {
    private let halfW = 200.0, halfH = 100.0, r = 12.0, eps = 0.01
    private func at(_ f: Double) -> EdgeHit { edgeAt(f, halfW: halfW, halfH: halfH, radius: r) }
    private func near(_ x: Double, _ y: Double, _ ax: Double, _ ay: Double, line: UInt = #line) {
        XCTAssertEqual(ax, x, accuracy: eps, line: line); XCTAssertEqual(ay, y, accuracy: eps, line: line)
    }

    private func offOutline(_ x: Double, _ y: Double) -> Double {
        let qx = abs(x) - (halfW - r), qy = abs(y) - (halfH - r)
        let outside = (max(qx, 0) * max(qx, 0) + max(qy, 0) * max(qy, 0)).squareRoot()
        return abs(outside + min(max(qx, qy), 0) - r)
    }

    func testQuarterTurnsLandOnTheMiddleOfEachSide() {
        near(0, -halfH, at(0).x, at(0).y)
        near(halfW, 0, at(0.25).x, at(0.25).y)
        near(0, halfH, at(0.5).x, at(0.5).y)
        near(-halfW, 0, at(0.75).x, at(0.75).y)
        near(0, -1, at(0).nx, at(0).ny)
        near(1, 0, at(0.25).nx, at(0.25).ny)
    }

    func testAFullTurnWraps() {
        near(at(0.1).x, at(0.1).y, at(1.1).x, at(1.1).y)
        near(at(0.9).x, at(0.9).y, at(-0.1).x, at(-0.1).y)
    }

    func testEveryTickLiesOnTheOutlineWithAnOutwardUnitNormal() {
        for i in 0..<60 {
            let h = at(Double(i) / 60)
            XCTAssertEqual(offOutline(h.x, h.y), 0, accuracy: eps, "tick \(i)")
            XCTAssertEqual((h.nx * h.nx + h.ny * h.ny).squareRoot(), 1, accuracy: eps)
            XCTAssertGreaterThan(h.x * h.nx + h.y * h.ny, 0)
        }
    }

    func testTicksAreEvenlySpacedAlongTheEdge() {
        let gaps = (0..<60).map { i -> Double in
            let a = at(Double(i) / 60), b = at(Double(i + 1) / 60)
            return ((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).squareRoot()
        }
        XCTAssertLessThan(gaps.max()! - gaps.min()!, 1.5)
    }

    func testTheCornerNormalIsDiagonalHalfwayRoundIt() {
        let sx = halfW - r, sy = halfH - r, arc = Double.pi / 2 * r
        let h = at((sx + arc / 2) / (4 * sx + 4 * sy + 4 * arc))
        let d = 1 / 2.0.squareRoot()
        near(d, -d, h.nx, h.ny)
        near(sx + r * d, -sy - r * d, h.x, h.y)
    }

    func testASquareCornerStillWorks() {
        let h = edgeAt(0.125, halfW: 100, halfH: 100, radius: 0)
        near(100, -100, h.x, h.y)
    }

    func testHandTurns() {
        XCTAssertEqual(handTurns(hour: 0, minute: 0, second: 0), HandTurns(hour: 0, minute: 0, second: 0))
        XCTAssertEqual(handTurns(hour: 3, minute: 30, second: 45), HandTurns(hour: 0.25, minute: 0.5, second: 0.75))
        XCTAssertEqual(handTurns(hour: 12, minute: 60, second: 60), HandTurns(hour: 0, minute: 0, second: 0))
        XCTAssertEqual(handTurns(hour: 6.5, minute: 0, second: 0).hour, 0.5 + 1.0 / 24, accuracy: 1e-5)
    }
}

/// Port of Android `ClockFormatTest`.
final class ClockFormatTests: XCTestCase {
    private let jakarta = TimeZone(identifier: "Asia/Jakarta")!
    private func at(_ h: Int, _ m: Int) -> Date {
        var c = DateComponents(); c.year = 2026; c.month = 11; c.day = 8; c.hour = h; c.minute = m; c.second = 30
        var cal = Calendar(identifier: .gregorian); cal.timeZone = jakarta
        return cal.date(from: c)!
    }

    func test24Hour() {
        XCTAssertEqual(formatClockTime(at(0, 5), zone: jakarta, is24: true), ClockTime(digits: "00:05", suffix: nil))
        XCTAssertEqual(formatClockTime(at(14, 30), zone: jakarta, is24: true), ClockTime(digits: "14:30", suffix: nil))
    }

    func test12HourWithAmAndPm() {
        XCTAssertEqual(formatClockTime(at(0, 5), zone: jakarta, is24: false), ClockTime(digits: "12:05", suffix: "AM"))
        XCTAssertEqual(formatClockTime(at(9, 41), zone: jakarta, is24: false), ClockTime(digits: "9:41", suffix: "AM"))
        XCTAssertEqual(formatClockTime(at(12, 30), zone: jakarta, is24: false), ClockTime(digits: "12:30", suffix: "PM"))
        XCTAssertEqual(formatClockTime(at(23, 59), zone: jakarta, is24: false), ClockTime(digits: "11:59", suffix: "PM"))
    }

    func testTheZoneDecidesTheHour() {
        XCTAssertEqual(formatClockTime(at(14, 30), zone: TimeZone(identifier: "UTC")!, is24: true), ClockTime(digits: "07:30", suffix: nil))
    }

    func testShortDate() {
        XCTAssertEqual(formatClockDate(at(10, 0), zone: jakarta, locale: Locale(identifier: "en_GB")), "Sun 8 Nov")
    }
}

/// Port of Android `SpeedometerUiMappingTest` and `CardFormattingTest`.
final class SpeedometerUiTests: XCTestCase {
    private let sec: Int64 = 1_000_000_000
    private var trip: TripStats {
        TripStats(startedAtNanos: 0,
                  lastSample: SpeedSample(timestampNanos: 600 * sec, speedMps: 27.78, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 51, longitude: 0),
                  currentSpeedMps: 27.78, maxSpeedMps: 33.33, distanceM: 10_000, movingNanos: 450 * sec, gpsStatus: .fix)
    }

    func testLocationOffOutranksTheStaleRuleAndBlanksTheSpeed() {
        var off = trip; off.gpsStatus = .disabled
        XCTAssertNil(off.toUiState(nowNanos: 600 * sec, unit: .kmh, tripState: .running).speed)
        let stale = off.toUiState(nowNanos: 700 * sec, unit: .kmh, tripState: .running)
        XCTAssertEqual(stale.gps, .disabled)
        XCTAssertNil(stale.speed)
        XCTAssertEqual(stale.maxSpeed, 120)
    }

    func testMissingPermissionShowsDenied() {
        let ui = trip.toUiState(nowNanos: 600 * sec, unit: .kmh, tripState: .idle, locationGranted: false)
        XCTAssertEqual(ui.gps, .permissionDenied)
        XCTAssertNil(ui.speed)
    }

    func testPausedShowsNoLiveSpeedAFrozenClockAndPaused() {
        var paused = trip; paused.pausedAtNanos = 600 * sec
        let ui = paused.toUiState(nowNanos: 900 * sec, unit: .kmh, tripState: .paused)
        XCTAssertNil(ui.speed)
        XCTAssertEqual(ui.elapsedS, 600)
        XCTAssertEqual(ui.avgSpeed, 60)
        XCTAssertEqual(ui.statusLabel, "Paused")
        XCTAssertFalse(ui.isTracking)
    }

    func testPausedTimeIsExcludedOnceResumed() {
        var r = trip; r.pausedNanos = 120 * sec
        let ui = r.toUiState(nowNanos: 600 * sec, unit: .kmh, tripState: .running)
        XCTAssertEqual(ui.elapsedS, 480)
        XCTAssertEqual(ui.avgSpeed, 75)
    }

    func testAnEndedTripIsFrozenAtItsEnd() {
        var e = trip; e.endedAtNanos = 600 * sec
        let ui = e.toUiState(nowNanos: 3_600 * sec, unit: .kmh, tripState: .ended)
        XCTAssertNil(ui.speed)
        XCTAssertEqual(ui.elapsedS, 600)
        XCTAssertEqual(ui.avgSpeed, 60)
        XCTAssertEqual(ui.statusLabel, "Ended")
    }

    func testConvertsToKmPerHour() {
        let ui = trip.toUiState(nowNanos: 600 * sec, unit: .kmh, tripState: .running)
        XCTAssertEqual(ui.speed, 100); XCTAssertEqual(ui.maxSpeed, 120); XCTAssertEqual(ui.avgSpeed, 60)
        XCTAssertEqual(ui.movingAvgSpeed, 80); XCTAssertEqual(ui.distance, 10, accuracy: 1e-9)
        XCTAssertEqual(ui.elapsedS, 600); XCTAssertEqual(ui.gps, .fix); XCTAssertTrue(ui.isTracking)
    }

    func testConvertsToMilesPerHour() {
        let ui = trip.toUiState(nowNanos: 600 * sec, unit: .mph, tripState: .running)
        XCTAssertEqual(ui.speed, 62); XCTAssertEqual(ui.maxSpeed, 75); XCTAssertEqual(ui.avgSpeed, 37)
        XCTAssertEqual(ui.distance, 6.2137, accuracy: 1e-4)
    }

    func testAFixExactly3sOldIsStillLive() {
        let ui = trip.toUiState(nowNanos: 603 * sec, unit: .kmh, tripState: .running)
        XCTAssertEqual(ui.speed, 100); XCTAssertEqual(ui.gps, .fix)
    }

    func testAFixMoreThan3sOldIsStale() {
        let ui = trip.toUiState(nowNanos: 603 * sec + 1, unit: .kmh, tripState: .running)
        XCTAssertNil(ui.speed); XCTAssertEqual(ui.gps, .stale)
        XCTAssertEqual(ui.maxSpeed, 120); XCTAssertEqual(ui.distance, 10, accuracy: 1e-9)
    }

    func testNoFixYetShowsDashesAndKeepsTheSourceStatus() {
        let ui = TripStats(startedAtNanos: 0).toUiState(nowNanos: 5 * sec, unit: .kmh, tripState: .running)
        XCTAssertNil(ui.speed); XCTAssertEqual(ui.gps, .searching); XCTAssertEqual(ui.elapsedS, 5); XCTAssertEqual(ui.avgSpeed, 0)
    }

    func testElapsedIsZeroBeforeTheTripStarts() {
        let ui = TripStats().toUiState(nowNanos: 99 * sec, unit: .kmh, tripState: .idle)
        XCTAssertEqual(ui.elapsedS, 0); XCTAssertFalse(ui.isTracking)
    }

    func testWithNoTripTheHeroShowsTheLiveFix() {
        let live = SpeedSample(timestampNanos: 10 * sec, speedMps: 10, speedAccuracyMps: 0.3, horizontalAccuracyM: 4, latitude: 0, longitude: 0)
        let ui = TripStats().toUiState(nowNanos: 11 * sec, unit: .kmh, tripState: .idle, liveFix: live)
        XCTAssertEqual(ui.speed, 36); XCTAssertEqual(ui.gps, .fix)
        XCTAssertNil(TripStats().toUiState(nowNanos: 14 * sec, unit: .kmh, tripState: .idle, liveFix: live).speed)
    }

    func testElapsedFormats() {
        XCTAssertEqual(formatElapsed(0), "0:00")
        XCTAssertEqual(formatElapsed(9), "0:09")
        XCTAssertEqual(formatElapsed(42 * 60 + 17), "42:17")
        XCTAssertEqual(formatElapsed(3600), "1:00:00")
        XCTAssertEqual(formatElapsed(2 * 3600 + 3 * 60 + 4), "2:03:04")
        XCTAssertEqual(formatElapsed(-5), "0:00")
    }

    func testEveryGpsLabelFitsTheNarrowTile() {
        for g in GpsStatus.allCases { XCTAssertLessThanOrEqual(g.label.count, 6) }
    }

    func testTelemetryGridListsAllSixStatsInUnit() {
        var ui = SpeedometerUiState.empty
        ui.maxSpeed = 70; ui.unit = .mph; ui.gps = .stale
        let stats = ui.telemetry()
        XCTAssertEqual(stats.map(\.label), ["MAX", "AVERAGE", "MOVING AVG", "ELAPSED", "DISTANCE", "GPS"])
        XCTAssertEqual(stats[0].value, "70"); XCTAssertEqual(stats[0].unit, "mph")
        XCTAssertEqual(stats[4].unit, "mi"); XCTAssertEqual(stats[5].value, "Lost")
    }

    func testTripStatsFollowTheVisibleSet() {
        var ui = SpeedometerUiState.empty
        ui.distance = 12.34; ui.elapsedS = 90
        let all = Set(TelemetryStat.allCases).subtracting(TelemetryStat.defaultHidden)
        let stats = ui.tripStats(visible: all, prefix: "log_")
        XCTAssertEqual(stats.map(\.tag), ["log_stat_distance", "log_stat_time", "log_stat_avg", "log_stat_max", "log_stat_moving_avg"])
        XCTAssertEqual(stats[0].value, "12.3")
        XCTAssertEqual(stats[1].value, "1:30")
    }
}
