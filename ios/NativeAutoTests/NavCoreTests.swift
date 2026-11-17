import XCTest
@testable import NativeAuto

/// Port of Android `ParsePhotonTest`.
final class ParsePhotonTests: XCTestCase {
    private func feature(_ lon: String?, _ lat: String?, _ props: (String, String)...) -> String {
        let coords = [lon, lat].compactMap { $0 }.joined(separator: ",")
        let p = props.map { "\"\($0.0)\":\"\($0.1)\"" }.joined(separator: ",")
        return #"{"type":"Feature","geometry":{"type":"Point","coordinates":[\#(coords)]},"properties":{\#(p)}}"#
    }

    private func collection(_ features: String...) -> String {
        #"{"type":"FeatureCollection","features":[\#(features.joined(separator: ","))]}"#
    }

    func testANamedPlaceWithItsArea() {
        let body = collection(feature("110.3655", "-7.7926", ("name", "Malioboro"), ("street", "Jalan Malioboro"),
                                      ("district", "Gedong Tengen"), ("city", "Yogyakarta"),
                                      ("state", "Special Region of Yogyakarta"), ("osm_key", "highway")))
        XCTAssertEqual(parsePhoton(body), [Place(name: "Malioboro",
                                                 detail: "Jalan Malioboro, Gedong Tengen, Yogyakarta, Special Region of Yogyakarta",
                                                 point: GeoPoint(-7.7926, 110.3655))])
    }

    func testCoordinatesAreLonLatInGeoJSON() {
        let place = parsePhoton(collection(feature("106.8272", "-6.1754", ("name", "Monas"))))
        XCTAssertEqual(place.count, 1)
        XCTAssertEqual(place[0].point, GeoPoint(-6.1754, 106.8272))
        XCTAssertNil(place[0].detail)
    }

    func testAnAddressWithoutANameIsNamedByStreetAndNumber() {
        let place = parsePhoton(collection(feature("110.0", "-7.0", ("street", "Jalan Kaliurang"), ("housenumber", "12"),
                                                   ("city", "Yogyakarta"))))[0]
        XCTAssertEqual(place.name, "Jalan Kaliurang 12")
        XCTAssertEqual(place.detail, "Yogyakarta") // the street isn't repeated when it's already the name
    }

    func testLocalityAndCountyStandInForDistrictAndCity() {
        let place = parsePhoton(collection(feature("110.0", "-7.0", ("name", "Warung"), ("locality", "Sosromenduran"),
                                                   ("county", "Yogyakarta"))))[0]
        XCTAssertEqual(place.detail, "Sosromenduran, Yogyakarta")
    }

    func testBlankAndRepeatedPartsAreLeftOut() {
        let place = parsePhoton(collection(feature("110.0", "-7.0", ("name", "Tugu"), ("district", " "), ("city", "Yogyakarta"),
                                                   ("state", "Yogyakarta"))))[0]
        XCTAssertEqual(place.detail, "Yogyakarta")
    }

    func testFeaturesWithoutAUsablePointOrNameAreSkipped() {
        let body = collection(
            feature(nil, nil, ("name", "No coordinates")),
            feature("110.0", nil, ("name", "Only one coordinate")),
            feature("\"x\"", "\"y\"", ("name", "Not numbers")),
            feature("110.0", "-7.0", ("city", "Nameless, streetless")),
            feature("110.0", "-7.0", ("name", "Kept"))
        )
        XCTAssertEqual(parsePhoton(body).map(\.name), ["Kept"])
    }

    func testAFeatureWithNoPropertiesIsSkipped() {
        XCTAssertEqual(parsePhoton(#"{"features":[{"geometry":{"coordinates":[110.0,-7.0]}}]}"#), [])
    }

    func testNoFeaturesGivesAnEmptyList() {
        XCTAssertEqual(parsePhoton(#"{"type":"FeatureCollection","features":[]}"#), [])
        XCTAssertEqual(parsePhoton("{}"), [])
        XCTAssertEqual(parsePhoton("not json"), [])
    }

    func testOrderIsKept() {
        let body = collection(feature("110.0", "-7.0", ("name", "First")), feature("111.0", "-7.0", ("name", "Second")))
        XCTAssertEqual(parsePhoton(body).map(\.name), ["First", "Second"])
    }
}

/// Port of Android `MapsLinkTest`.
final class MapsLinkTests: XCTestCase {
    private func at(_ t: LinkTarget?) -> (GeoPoint, String?)? { if case .at(let p, let n) = t { (p, n) } else { nil } }

    func testFindsTheMapsLinkInAGoogleMapsShare() {
        let text = "Malioboro Mall\nJl. Malioboro No.52-58, Yogyakarta\nhttps://maps.app.goo.gl/AbCdEf123"
        let url = findMapsUrl(text)!
        XCTAssertEqual(url, "https://maps.app.goo.gl/AbCdEf123")
        XCTAssertTrue(isShortMapsLink(url))
        XCTAssertEqual(sharedName(text, url: url), "Malioboro Mall")
        XCTAssertEqual(sharedDetail(text, url: url), "Jl. Malioboro No.52-58, Yogyakarta")
    }

    func testIgnoresLinksThatArentMaps() {
        XCTAssertNil(findMapsUrl("look https://example.com/maps/x"))
        XCTAssertNil(findMapsUrl("no link here"))
        XCTAssertFalse(isShortMapsLink("https://www.google.com/maps/place/X/@1,2,3z"))
    }

    func testThePlacePinBeatsTheCameraCentre() {
        let url = "https://www.google.com/maps/place/Stasiun+Yogyakarta/@-7.7891,110.3608,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d-7.7893711!4d110.3633527"
        let t = at(parseMapsLink(url))!
        XCTAssertEqual(t.0.latitude, -7.7893711, accuracy: 1e-9)
        XCTAssertEqual(t.0.longitude, 110.3633527, accuracy: 1e-9)
        XCTAssertEqual(t.1, "Stasiun Yogyakarta")
    }

    func testCameraCentreWhenThereIsNoPin() {
        let t = at(parseMapsLink("https://www.google.com/maps/@-6.1754,106.8272,15z"))!
        XCTAssertEqual(t.0.latitude, -6.1754, accuracy: 1e-9)
        XCTAssertNil(t.1)
    }

    func testQWithCoordinatesOrAName() {
        let a = at(parseMapsLink("https://maps.google.com/?q=-7.7956,110.3695",
                                 sharedText: "Kos Putri Melati\nhttps://maps.google.com/?q=-7.7956,110.3695"))!
        XCTAssertEqual(a.0.longitude, 110.3695, accuracy: 1e-9)
        XCTAssertEqual(a.1, "Kos Putri Melati")
        XCTAssertEqual(parseMapsLink("https://www.google.com/maps/search/?api=1&query=Bengkel+Motor+Jaya"), .query("Bengkel Motor Jaya"))
        let dest = at(parseMapsLink("https://www.google.com/maps/dir/?api=1&destination=-6.2088%2C106.8456"))!
        XCTAssertEqual(dest.0.latitude, -6.2088, accuracy: 1e-9)
    }

    func testGeoUris() {
        XCTAssertEqual(at(parseMapsLink("geo:-7.8,110.4"))?.0, GeoPoint(-7.8, 110.4))
        XCTAssertEqual(at(parseMapsLink("geo:0,0?q=-7.8,110.4(Warung Bu Ning)"))?.1, "Warung Bu Ning")
        XCTAssertEqual(parseMapsLink("geo:0,0?q=Jalan+Kaliurang+5"), .query("Jalan Kaliurang 5"))
        XCTAssertNil(parseMapsLink("geo:0,0"))
    }

    func testAGeoLabelSurvivesBeingFoundInText() {
        let text = "geo:0,0?q=-7.7829,110.3671(Tugu%20Yogyakarta)"
        let url = findMapsUrl(text)!
        XCTAssertEqual(url, text)
        XCTAssertEqual(at(parseMapsLink(url, sharedText: text))?.1, "Tugu Yogyakarta")
        // Punctuation after a link in a sentence still goes.
        XCTAssertEqual(findMapsUrl("see (https://maps.app.goo.gl/AbC)."), "https://maps.app.goo.gl/AbC")
    }

    func testOnlyAPlaceSegmentSearchesByName() {
        XCTAssertEqual(parseMapsLink("https://www.google.com/maps/place/Gudeg+Yu+Djum"), .query("Gudeg Yu Djum"))
    }
}

/// Port of Android `NavFormatTest`.
final class NavFormatTests: XCTestCase {
    private func km(_ m: Double) -> String { formatNavDistance(m, .kmh) }
    private func mi(_ m: Double) -> String { formatNavDistance(m, .mph) }

    func testMetricDistancesRoundToGlanceableSteps() {
        XCTAssertEqual(km(0), "0 m")
        XCTAssertEqual(km(-3), "0 m") // the router can overshoot a maneuver slightly
        XCTAssertEqual(km(7), "10 m")
        XCTAssertEqual(km(94), "90 m")
        XCTAssertEqual(km(100), "100 m")
        XCTAssertEqual(km(349), "350 m")
        XCTAssertEqual(km(960), "950 m")
        XCTAssertEqual(km(980), "1 km") // not "1000 m"
        XCTAssertEqual(km(1_000), "1.0 km")
        XCTAssertEqual(km(1_234), "1.2 km")
        XCTAssertEqual(km(9_940), "9.9 km")
        XCTAssertEqual(km(9_960), "10 km") // not "10.0 km"
        XCTAssertEqual(km(10_000), "10 km")
        XCTAssertEqual(km(123_600), "124 km")
    }

    func testImperialDistancesUseFeetUpCloseThenMiles() {
        XCTAssertEqual(mi(0), "0 ft")
        XCTAssertEqual(mi(30), "100 ft")
        XCTAssertEqual(mi(152), "500 ft")
        XCTAssertEqual(mi(161), "0.1 mi")
        XCTAssertEqual(mi(1_609.344), "1.0 mi")
        XCTAssertEqual(mi(9.9 * 1_609.344), "9.9 mi")
        XCTAssertEqual(mi(9.96 * 1_609.344), "10 mi")
        XCTAssertEqual(mi(25 * 1_609.344), "25 mi")
    }

    func testDurationsRoundUpToTheMinute() {
        XCTAssertEqual(formatNavDuration(0), "0 min")
        XCTAssertEqual(formatNavDuration(1), "1 min")
        XCTAssertEqual(formatNavDuration(60), "1 min")
        XCTAssertEqual(formatNavDuration(61), "2 min")
        XCTAssertEqual(formatNavDuration(59 * 60), "59 min")
        XCTAssertEqual(formatNavDuration(59 * 60 + 1), "1 h 00 min")
        XCTAssertEqual(formatNavDuration(65 * 60), "1 h 05 min")
        XCTAssertEqual(formatNavDuration(750 * 60), "12 h 30 min")
    }

    func testEtaIsTheArrivalClockTimeInTheGivenZone() {
        let utc = TimeZone(identifier: "UTC")!, jakarta = TimeZone(identifier: "Asia/Jakarta")!
        XCTAssertEqual(formatEta(nowMs: 0, remainingS: 0, zone: utc), "00:00")
        XCTAssertEqual(formatEta(nowMs: 0, remainingS: 90 * 60, zone: utc), "01:30")
        XCTAssertEqual(formatEta(nowMs: 0, remainingS: 90 * 60, zone: jakarta), "08:30")
        XCTAssertEqual(formatEta(nowMs: 0, remainingS: 60, zone: utc), "00:01")
        XCTAssertEqual(formatEta(nowMs: 0, remainingS: 59.4, zone: utc), "00:00")
        XCTAssertEqual(formatEta(nowMs: 23 * 3_600_000 + 45 * 60_000, remainingS: 30 * 60, zone: utc), "00:15")
    }

    private func instr(_ text: String, _ kind: ManeuverKind = .turn, _ dir: TurnDirection? = .left) -> Instruction {
        Instruction(text: text, kind: kind, direction: dir)
    }

    func testTheBannerShowsTheRoadName() {
        XCTAssertEqual(instr("Jalan Malioboro").bannerText, "Jalan Malioboro")
        XCTAssertEqual(instr("  Jalan Malioboro ").bannerText, "Jalan Malioboro")
    }

    func testARoadKnownByItsNumberBecomesRouteN() {
        XCTAssertEqual(instr("7").bannerText, "Route 7")
        XCTAssertEqual(instr("12A").bannerText, "Route 12A")
        XCTAssertEqual(instr("A12 Ring Road").bannerText, "A12 Ring Road")
    }

    func testTheRoutersSentenceLosesItsFullStop() { XCTAssertEqual(instr("Turn left.").bannerText, "Turn left") }

    func testAnEmptyBannerFallsBackToTheManeuverInWords() {
        XCTAssertEqual(instr("").bannerText, "Turn left")
        XCTAssertEqual(instr(" ", .arrive, nil).bannerText, "Arrive")
    }

    func testManeuversInWords() {
        func w(_ k: ManeuverKind, _ d: TurnDirection?) -> String { instr("", k, d).maneuverWords }
        XCTAssertEqual(w(.arrive, .left), "Arrive")
        XCTAssertEqual(w(.depart, nil), "Head out")
        XCTAssertEqual(w(.roundabout, .right), "Roundabout")
        XCTAssertEqual(w(.merge, .slightLeft), "Merge")
        XCTAssertEqual(w(.fork, .slightLeft), "Keep left")
        XCTAssertEqual(w(.fork, .sharpLeft), "Keep left")
        XCTAssertEqual(w(.fork, .slightRight), "Keep right")
        XCTAssertEqual(w(.fork, nil), "Keep right")
        XCTAssertEqual(w(.ramp, .left), "Take the ramp left")
        XCTAssertEqual(w(.ramp, .right), "Take the ramp right")
        XCTAssertEqual(w(.turn, .left), "Turn left")
        XCTAssertEqual(w(.turn, .right), "Turn right")
        XCTAssertEqual(w(.turn, .slightLeft), "Bear left")
        XCTAssertEqual(w(.continue, .slightRight), "Bear right")
        XCTAssertEqual(w(.turn, .sharpLeft), "Sharp left")
        XCTAssertEqual(w(.turn, .sharpRight), "Sharp right")
        XCTAssertEqual(w(.turn, .uTurn), "Make a U-turn")
        XCTAssertEqual(w(.continue, .straight), "Continue")
        XCTAssertEqual(w(.turn, nil), "Continue")
    }
}

/// Port of Android `SimulatedDriveTest` (stepped directly, and once through the source in virtual time).
final class SimulatedDriveTests: XCTestCase {
    /// A point `north` and `east` metres from (0, 0); near the equator a degree is the same both ways.
    private func at(_ north: Double, _ east: Double = 0) -> GeoPoint { GeoPoint(north / metersPerDegreeLat, east / metersPerDegreeLat) }
    private func dist(_ a: GeoPoint, _ b: GeoPoint) -> Double { haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }

    private func steps(_ route: [GeoPoint], speed: Float = 11, _ n: Int) -> [(point: GeoPoint, speedMps: Float, bearingDeg: Float)] {
        var d = SimulatedDrive(route: route, speedMps: speed)
        return (0..<n).compactMap { _ in d.step() }
    }

    func testAnEmptyRouteEmitsNothing() {
        XCTAssertTrue(steps([], 3).isEmpty)
        let v = VirtualScheduler()
        var n = 0
        let sub = SimulatedDriveSource(route: [], clock: v, scheduler: v).subscribe { _ in n += 1 }
        v.advance(by: 5_000)
        sub.cancel()
        XCTAssertEqual(n, 0)
    }

    func testDrivesAStraightRouteAtTheSetSpeedOneFixASecond() {
        let v = VirtualScheduler()
        var fixes: [SpeedSample] = []
        let sub = SimulatedDriveSource(route: [at(0), at(1_000)], clock: v, scheduler: v, speedMps: 10).subscribe {
            if case .fix(let f) = $0 { fixes.append(f) }
        }
        v.advance(by: 5_000)
        sub.cancel()
        XCTAssertEqual(fixes.count, 5)
        for (k, f) in fixes.enumerated() {
            XCTAssertEqual(f.timestampNanos, Int64(k + 1) * 1_000_000_000)
            XCTAssertEqual(f.speedMps, 10)
            XCTAssertEqual(f.bearingDeg!, 0, accuracy: 0.01)
            XCTAssertTrue(f.hasSpeed)
            XCTAssertEqual(dist(at(0), GeoPoint(f.latitude, f.longitude)), Double(k + 1) * 10, accuracy: 0.01)
        }
    }

    func testCarriesOverVerticesAndStopsOnTheLastPoint() {
        let route = [at(0), at(0, 15), at(0, 30), at(0, 45)]
        let fixes = steps(route, speed: 10, 7)
        let along = fixes.map { dist(route[0], $0.point) }
        for (k, want) in [10.0, 20, 30, 40].enumerated() { XCTAssertEqual(along[k], want, accuracy: 0.01) }
        XCTAssertEqual(fixes[0].bearingDeg, 90, accuracy: 0.01)
        for f in fixes.dropFirst(4) { XCTAssertEqual(dist(route[0], f.point), 45, accuracy: 0.01) }
        XCTAssertEqual(fixes[5].speedMps, 0)
        XCTAssertEqual(fixes[6].speedMps, 0)
    }

    func testSlowsForASharpBendAhead() {
        XCTAssertEqual(steps([at(0), at(100), at(100, 100)], speed: 10, 1)[0].speedMps, 4.5, accuracy: 1e-4)
        XCTAssertEqual(steps([at(0), at(100), at(100 + 86.6, 50)], speed: 10, 1)[0].speedMps, 10)
    }

    func testAOnePointRouteStandsStillThere() {
        let p = at(10, 10)
        for f in steps([p], 2) { XCTAssertEqual(f.speedMps, 0); XCTAssertEqual(f.point, p) }
    }

    func testBearingsPointTheCompassWay() {
        XCTAssertEqual(bearingDeg(at(0), at(10)), 0, accuracy: 0.01)
        XCTAssertEqual(bearingDeg(at(0), at(0, 10)), 90, accuracy: 0.01)
        XCTAssertEqual(bearingDeg(at(10), at(0)), 180, accuracy: 0.01)
        XCTAssertEqual(bearingDeg(at(0, 10), at(0)), 270, accuracy: 0.01)
        XCTAssertEqual(bearingDeg(at(0), at(10, 10)), 45, accuracy: 0.01)
    }
}

/// Port of Android `FollowCameraTest`.
final class FollowCameraTests: XCTestCase {
    private func target(_ speed: Float, _ course: Float?, zoom: Float = 16, bearing: Float = 30) -> CameraTarget {
        FollowCamera.target(latitude: 51, longitude: -2.5, speedMps: speed, courseDeg: course, currentZoom: zoom, currentBearing: bearing)
    }

    func testCentresOnTheFixTilted() {
        let t = target(20, 90)
        XCTAssertEqual(t.latitude, 51); XCTAssertEqual(t.longitude, -2.5); XCTAssertEqual(t.tilt, FollowCamera.tilt)
    }

    func testHeadingUpWhileMoving() {
        XCTAssertEqual(target(20, 90).bearing, 90)
        XCTAssertEqual(target(20, -10).bearing, 350, accuracy: 1e-4)
        XCTAssertEqual(target(20, 370).bearing, 10, accuracy: 1e-4)
    }

    func testParkedOrWithoutACourseTheBearingHoldsStill() {
        XCTAssertEqual(target(1, 200).bearing, 30)
        XCTAssertEqual(target(20, nil).bearing, 30)
        XCTAssertEqual(target(FollowCamera.minHeadingSpeedMps, 90).bearing, 90)
    }

    func testKeepsTheRidersZoomUnlessZoomedOutTooFar() {
        XCTAssertEqual(target(20, 0, zoom: 15).zoom, 15)
        XCTAssertEqual(target(20, 0, zoom: 4).zoom, FollowCamera.zoom)
    }
}

/// Recent places (Android `RoomRecentPlaces`: upsert by `placeKey`, newest first, 8 kept) and `NavState` helpers.
final class NavStateTests: XCTestCase {
    private func place(_ name: String, _ lat: Double) -> Place { Place(name: name, detail: nil, point: GeoPoint(lat, 110)) }

    func testRecentPlacesKeepEightNewestFirstOnePerSpot() {
        var r: [Place] = []
        for i in 0..<10 { r = rememberPlace(place("P\(i)", Double(i)), in: r) }
        XCTAssertEqual(r.map(\.name), ["P9", "P8", "P7", "P6", "P5", "P4", "P3", "P2"])
        // The same spot (within ~10 m) moves to the top under its new name.
        r = rememberPlace(Place(name: "Again", detail: nil, point: GeoPoint(5.00003, 110.00002)), in: r)
        XCTAssertEqual(r.map(\.name), ["Again", "P9", "P8", "P7", "P6", "P4", "P3", "P2"])
    }

    func testARenameOnlyLandsOnTheSameDestination() {
        let a = place("Pin", -7), named = Place(name: "Jalan Kaliurang", detail: nil, point: a.point)
        XCTAssertEqual(NavState.routing(a).renamed(named), .routing(named))
        XCTAssertEqual(NavState.routing(a).renamed(place("Other", 1)), .routing(a))
        XCTAssertEqual(NavState.idle.renamed(named), .idle)
    }
}
