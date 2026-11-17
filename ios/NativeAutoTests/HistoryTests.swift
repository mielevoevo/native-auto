import XCTest
@testable import NativeAuto

/// Port of Android `GpxBackupTest` and `GarageBackupTest`, plus the ZIP itself (an Android-style file with data
/// descriptors, as `ZipOutputStream` writes it).
final class GpxBackupTests: XCTestCase {
    private let trip = TripRecord(
        id: 7, startedAtMs: 1_762_650_000_000, endedAtMs: 1_762_650_600_000, durationMs: 600_000, movingMs: 500_000, distanceM: 5_000,
        maxSpeedMps: 22, startLat: -7.8, startLon: 110.37, endLat: -7.75, endLon: 110.4, startAddress: "Jalan Malioboro & <Co>",
        endAddress: nil, track: Polyline.encode([GeoPoint(-7.8, 110.37), GeoPoint(-7.75, 110.4)]), stoppedMs: 100_000, bikeId: "Gen 1")
    private let points = [
        TrackPointRecord(tripId: 7, timeMs: 1_762_650_000_000, latitude: -7.8, longitude: 110.37, speedMps: 10.5, bearingDeg: 45, accuracyM: 4),
        TrackPointRecord(tripId: 7, timeMs: 1_762_650_001_000, latitude: -7.79991, longitude: 110.37008, speedMps: 11, bearingDeg: nil, accuracyM: 5),
    ]

    private func count(_ pattern: String, _ s: String) -> Int {
        try! NSRegularExpression(pattern: pattern).numberOfMatches(in: s, range: NSRange(s.startIndex..., in: s))
    }

    func testGpxCarriesTimeSpeedAndCoursePerPointAndEscapesNames() {
        let gpx = buildGpx(trip, points)
        XCTAssertTrue(gpx.hasPrefix("<?xml"))
        XCTAssertTrue(gpx.contains(#"<trkpt lat="-7.8000000" lon="110.3700000"><time>2025-11-09T01:00:00Z</time>"#))
        XCTAssertTrue(gpx.contains("<speed>10.50</speed><course>45.00</course>"))
        XCTAssertTrue(gpx.contains("<speed>11.00</speed></extensions>"))
        XCTAssertTrue(gpx.contains("Jalan Malioboro &amp; &lt;Co&gt;"))
        XCTAssertFalse(gpx.contains("<Co>"))
        XCTAssertEqual(count("<trkpt ", gpx), 2)
    }

    func testAnOldTripWithoutAFullTrackExportsItsPathWithoutTimes() {
        let gpx = buildGpx(trip, [])
        XCTAssertEqual(count("<trkpt [^>]*/>", gpx), 2)
        XCTAssertFalse(gpx.contains(#"<trkpt lat="-7.8000000" lon="110.3700000"><time>"#))
    }

    func testIsoTimeIsJavasIsoInstant() {
        XCTAssertEqual(isoTime(1_762_650_000_000), "2025-11-09T01:00:00Z")
        XCTAssertEqual(isoTime(1_762_650_000_250), "2025-11-09T01:00:00.250Z")
        XCTAssertEqual(gpxFileName(trip), "ride-2025-11-09-0100-7.gpx")
    }

    func testABackupZipHoldsTheJsonAndOneGpxPerTripAndReadsBackTheSame() throws {
        let zip = Backup.write([Backup.TripWithTrack(trip: trip, points: points)])
        XCTAssertEqual(try Zip.read(zip).map(\.name), [Backup.jsonEntry, "gpx/" + gpxFileName(trip)])
        let back = try Backup.read(zip)[0]
        var expected = trip; expected.id = 0
        XCTAssertEqual(back.trip, expected)
        XCTAssertEqual(back.points, points.map { var p = $0; p.tripId = 0; return p })
        XCTAssertNil(back.points[1].bearingDeg)
    }

    func testANewerBackupFormatIsRefused() {
        XCTAssertThrowsError(try Backup.decodeAll(#"{"format": 99, "trips": []}"#))
    }

    func testAZipWithoutBackupJsonIsRefused() {
        let zip = Zip.write([Zip.Entry(name: "other.txt", data: Data([1]))])
        XCTAssertThrowsError(try Backup.read(zip))
        XCTAssertThrowsError(try Backup.read(Data("not a zip".utf8)))
    }

    func testRestoreAddsOnlyTripsThatArentThereYet() {
        let dao = InMemoryStore()
        var nextDay = trip; nextDay.startedAtMs += 86_400_000
        let backup = [Backup.TripWithTrack(trip: trip, points: points), Backup.TripWithTrack(trip: nextDay, points: [])]
        XCTAssertEqual(Backup.restore(dao, backup), 2)
        XCTAssertEqual(dao.points(1).count, 2)
        XCTAssertEqual(Backup.restore(dao, backup), 0)
        XCTAssertEqual(dao.trips.count, 2)
    }

    /// Written by Python's `zipfile` to an unseekable stream: deflated entries with data descriptors and zero sizes in
    /// the local headers, like Java's `ZipOutputStream`. The JSON is kotlinx-style (5000.0, 22.0, nulls).
    func testAnAndroidStyleBackupReads() throws {
        let fixture = "UEsDBBQACAAIALptcV0AAAAAAAAAAAAAAAALAAAAYmFja3VwLmpzb259U12P2jAQ/Cton9MoARJK3iJIT1cInIBWlU7oZGJDLRI7Mg7tCfHfu5sP2jvR4wFld2bXO7P2BfbaFMxC1HeAlSVEsGBWnkUvrqwGBw7MsIOA6AI7eRQniJ4vIDnSdj6iihWIwYNQPQrta0nherJcbpLVy+T7BpOF5iKnvmn84+UhWdREpo5zaQ01DN3QAfwS5iy6nO8OHNhXIt80HZ+S1SbGeiw9WWbsrHgSZg7RIHQ9BzTXX1hmtaFCjPEbFUBkTSUcyNnJfjsJHtuUOo/Cfhh4zc+BzAhm72LXLU3FuFSHRjXpf7wpt7IQd/odC0z18ZPmOEpF/HVCNrBf7HWGaI1YI8vlfn8SNq0zzWEkmM7CoDQya92+b0NNaEzwg3FQd61K/oEYMvjWlFaIFr/V1G4zUQepRE/LHG4apo/rTbyYJJiRymInlpOYTmmXS7WyP/EEVeW5A7wSUxwovSVoFVOt7hnXQbN/Dfx4O62glci04bVv18bZRmJ9Uf6zdqH4OyRsEV4ZvP9aEdTlCn3GW0CZoM2crC5Lwev6rlDigSoTacOi8Qv2e10KpNFE/b7rtbd3Tu/t08j93MVaYR/fcwejerQOHwVN2MHDlh9zjq8Ee8JXljPVS1ku9U4beq/Iv8GN6daw7IhceL/uUuPayKvnt+7Uk7Xj4H/gDAMcfeh6W+cv02+Zo/F47Ldsz6M65NYHY9F2e91e/wBQSwcIcUKl8hwCAABjBAAAUEsDBBQACAAIALptcV0AAAAAAAAAAAAAAAAMAAAAZ3B4L3JpZGUuZ3B4s0kvqNC3AwBQSwcImnHICwgAAAAGAAAAUEsBAhQDFAAIAAgAum1xXXFCpfIcAgAAYwQAAAsAAAAAAAAAAAAAAIABAAAAAGJhY2t1cC5qc29uUEsBAhQDFAAIAAgAum1xXZpxyAsIAAAABgAAAAwAAAAAAAAAAAAAAIABVQIAAGdweC9yaWRlLmdweFBLBQYAAAAAAgACAHMAAACXAgAAAAA="
        let c = try Backup.readAll(Data(base64Encoded: fixture)!)
        XCTAssertEqual(c.trips.count, 1)
        let t = c.trips[0]
        XCTAssertEqual(t.trip.startedAtMs, 1_762_650_000_000)
        XCTAssertEqual(t.trip.distanceM, 5_000)
        XCTAssertEqual(t.trip.maxSpeedMps, 22)
        XCTAssertEqual(t.trip.startAddress, "Jalan Malioboro")
        XCTAssertNil(t.trip.endAddress)
        XCTAssertEqual(t.trip.bikeId, "b1")
        XCTAssertEqual(t.points.count, 2)
        XCTAssertEqual(t.points[0].bearingDeg, 45)
        XCTAssertNil(t.points[1].bearingDeg)
        let g = try XCTUnwrap(c.garage)
        XCTAssertEqual(g.bikes.map(\.name), ["Gen 1"])
        XCTAssertTrue(g.bikes[0].active)
        XCTAssertEqual(g.readings[0].km, 12_000)
        XCTAssertEqual(g.prices[0].pricePerL, 15_950)
        XCTAssertNil(g.services[0].intervalMonths)
        XCTAssertEqual(g.services[0].intervalKm, 2_000)
    }

    func testAZipRoundTripsBinaryAndEmptyEntries() throws {
        let big = Data((0..<50_000).map { UInt8(truncatingIfNeeded: $0 * 7) })
        let entries = [Zip.Entry(name: "a.bin", data: big), Zip.Entry(name: "empty", data: Data()), Zip.Entry(name: "ü/ñ.txt", data: Data("hi".utf8))]
        XCTAssertEqual(try Zip.read(Zip.write(entries)), entries)
        XCTAssertEqual(Zip.crc32(Data("123456789".utf8)), 0xCBF4_3926)
    }

    // MARK: Garage in the backup (Android `GarageBackupTest`)

    private func bike(_ id: String, _ name: String, _ active: Bool) -> BikeRecord {
        BikeRecord(id: id, name: name, model: BikeModel.nmaxGen1.rawValue, tankLitres: 6.6, reserveLitres: 1.3, fuelType: FuelType.pertamax.rawValue,
                   startKmPerL: 36, odoFactor: 1.02, active: active, lastUsedAtMs: 5, createdAtMs: 1)
    }

    private var data: GarageData {
        GarageData(
            bikes: [bike("b1", "Gen 1", true), bike("b2", "Gen 2", false)],
            readings: [OdometerReading(id: 7, bikeId: "b1", timeMs: 10, km: 12_000, kind: ReadingKind.set.rawValue),
                       OdometerReading(id: 8, bikeId: "b1", timeMs: 20, km: 12_100, kind: ReadingKind.untracked.rawValue, awayKm: 42, tripOffsetM: 150)],
            refuels: [RefuelRecord(id: 3, bikeId: "b1", timeMs: 15, odometerKm: 12_050, litres: 4, pricePerL: 15_900, totalRp: 63_600, full: true, fuelType: "PERTAMAX")],
            prices: [FuelPrice("PERTAMAX", 15_900, 15)],
            services: [ServiceItem(id: 40, bikeId: "b1", name: "Engine oil", kind: "DISTANCE", intervalKm: 2_000, intervalMonths: 3, lastDoneMs: 10, lastDoneKm: 12_000, createdAtMs: 9),
                       ServiceItem(id: 41, bikeId: "b2", name: "Vehicle tax", kind: "DATE", intervalMonths: 12, dueDateMs: 99, lastDoneMs: 0, createdAtMs: 9)],
            serviceRecords: [ServiceRecord(id: 1, itemId: 40, bikeId: "b1", itemName: "Engine oil", timeMs: 10, odometerKm: 12_000, costRp: 55_000, note: "Yamalube")])
    }

    func testTheGarageSurvivesARoundTripThroughTheZip() throws {
        let back = try XCTUnwrap(Backup.readAll(Backup.write([], garage: data)).garage)
        XCTAssertEqual(back.bikes, data.bikes)
        XCTAssertEqual(back.readings, data.readings.map { var r = $0; r.id = 0; return r })
        XCTAssertEqual(back.refuels, data.refuels.map { var r = $0; r.id = 0; return r })
        XCTAssertEqual(back.prices, data.prices)
        XCTAssertEqual(back.services, data.services)
        XCTAssertEqual(back.serviceRecords, data.serviceRecords.map { var r = $0; r.id = 0; return r })
    }

    func testAFormat1BackupHasNoGarageAndStillReads() throws {
        let c = try Backup.decodeAll(#"{"format": 1, "trips": []}"#)
        XCTAssertTrue(c.trips.isEmpty)
        XCTAssertNil(c.garage)
    }

    func testRestoringMergesKeepsOneActiveBikeAndTwiceChangesNothing() {
        let dao = InMemoryStore()
        dao.insertBike(bike("b2", "Gen 2", true))
        dao.upsertPrice(FuelPrice("PERTAMAX", 16_000, 99))
        XCTAssertEqual(GarageBackup.restore(dao, data), 7)
        XCTAssertEqual(dao.bikes.filter(\.active).map(\.id), ["b2"])
        XCTAssertEqual(dao.bike("b1")!.odoFactor, 1.02)
        XCTAssertEqual(dao.allPrices()[0].pricePerL, 16_000)
        let oil = dao.allServices().first { $0.name == "Engine oil" }!
        XCTAssertEqual(dao.allServiceRecords()[0].itemId, oil.id)
        XCTAssertEqual(GarageBackup.restore(dao, data), 0)
        XCTAssertEqual(dao.allBikes().count, 2)
    }

    func testRestoringIntoAnEmptyGarageKeepsTheBackupsActiveBike() {
        let dao = InMemoryStore()
        GarageBackup.restore(dao, data)
        XCTAssertEqual(dao.bikes.filter(\.active).map(\.id), ["b1"])
        XCTAssertEqual(dao.allPrices()[0].pricePerL, 15_900)
    }
}

/// Port of Android `PeriodTotalsTest`, `ShortAddressTest` and `DayGroupsTest`.
final class HistoryHelpersTests: XCTestCase {
    private let zone = TimeZone(identifier: "Asia/Jakarta")!
    private func ms(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 12) -> Int64 {
        msOf(calendar(zone).date(from: DateComponents(year: y, month: mo, day: d, hour: h))!)
    }
    private func trip(_ start: Int64, _ m: Double, id: Int64 = 0) -> TripRecord {
        TripRecord(id: id, startedAtMs: start, endedAtMs: start + 600_000, durationMs: 600_000, movingMs: 600_000, distanceM: m, maxSpeedMps: 10,
                   startLat: 0, startLon: 0, endLat: 0, endLon: 0, track: "")
    }

    func testPeriodStarts() {
        let now = ms(2026, 11, 11, 18) // a Wednesday
        XCTAssertEqual(periodStartMs(.day, nowMs: now, zone: zone), ms(2026, 11, 11, 0))
        XCTAssertEqual(periodStartMs(.week, nowMs: now, zone: zone), ms(2026, 11, 9, 0))
        XCTAssertEqual(periodStartMs(.month, nowMs: now, zone: zone), ms(2026, 11, 1, 0))
        XCTAssertEqual(periodStartMs(.week, nowMs: ms(2026, 11, 9, 7), zone: zone), ms(2026, 11, 9, 0))
        // A Sunday belongs to the week that started the Monday before.
        XCTAssertEqual(periodStartMs(.week, nowMs: ms(2026, 11, 15, 7), zone: zone), ms(2026, 11, 9, 0))
    }

    func testTripsCountForThePeriodTheyStartedIn() {
        let now = ms(2026, 11, 11, 18)
        let trips = [trip(ms(2026, 11, 11, 8), 10_000), trip(ms(2026, 11, 10, 23), 5_000), trip(ms(2026, 11, 8), 7_000), trip(ms(2026, 10, 31), 3_000)]
        XCTAssertEqual(periodDistanceM(trips, .day, nowMs: now, zone: zone), 10_000)
        XCTAssertEqual(periodDistanceM(trips, .week, nowMs: now, zone: zone), 15_000)
        XCTAssertEqual(periodDistanceM(trips, .month, nowMs: now, zone: zone), 22_000)
    }

    func testShortAddresses() {
        XCTAssertEqual(shortAddress(street: "Jalan Malioboro", area: "Gedong Tengen", line: "ignored"), "Jalan Malioboro, Gedong Tengen")
        XCTAssertEqual(shortAddress(street: "Jalan Malioboro", area: nil, line: nil), "Jalan Malioboro")
        XCTAssertEqual(shortAddress(street: "Jalan Malioboro", area: "  ", line: nil), "Jalan Malioboro")
        XCTAssertEqual(shortAddress(street: "Yogyakarta", area: "Yogyakarta", line: nil), "Yogyakarta")
        XCTAssertEqual(shortAddress(street: "  Jalan Kaliurang ", area: " Yogyakarta ", line: nil), "Jalan Kaliurang, Yogyakarta")
        let line = "Jl. Malioboro No.12, Sosromenduran, Gedong Tengen, Yogyakarta City, Special Region of Yogyakarta 55271, Indonesia"
        XCTAssertEqual(shortAddress(street: "QX2C+9F", area: "Sosromenduran", line: line), "Jl. Malioboro No.12, Sosromenduran")
        XCTAssertEqual(shortAddress(street: "12", area: "Sosromenduran", line: line), "Jl. Malioboro No.12, Sosromenduran")
        XCTAssertEqual(shortAddress(street: "12 14", area: "Sosromenduran", line: line), "Jl. Malioboro No.12, Sosromenduran")
        XCTAssertEqual(shortAddress(street: nil, area: nil, line: "QX2C+9F, Sosromenduran, Gedong Tengen, Yogyakarta"), "Sosromenduran, Gedong Tengen")
        XCTAssertEqual(shortAddress(street: nil, area: "Yogyakarta", line: nil), "Yogyakarta")
        XCTAssertEqual(shortAddress(street: "QX2C+9F", area: "Yogyakarta", line: ""), "Yogyakarta")
        XCTAssertNil(shortAddress(street: nil, area: nil, line: nil))
        XCTAssertNil(shortAddress(street: "", area: "", line: ""))
        XCTAssertNil(shortAddress(street: "QX2C+9F", area: nil, line: "QX2C+9F"))
    }

    func testTripsGroupByLocalDayWithLabelsAndTotalsNewestFirst() {
        let now = ms(2026, 11, 9, 10)
        let trips = [trip(ms(2026, 11, 9, 8), 10_000, id: 1), trip(ms(2026, 11, 9, 0), 2_500, id: 2), trip(ms(2026, 11, 8, 23), 4_000, id: 3),
                     trip(ms(2026, 11, 3, 7), 1_000, id: 4), trip(ms(2025, 12, 30, 7), 3_000, id: 5)]
        let groups = groupByDay(trips, nowMs: now, zone: zone)
        XCTAssertEqual(groups.map(\.label), ["Today", "Yesterday", "Tue 3 Nov", "Tue 30 Dec 2025"])
        XCTAssertEqual(groups[0].totalDistanceM, 12_500, accuracy: 1e-9)
        XCTAssertEqual(groups[0].trips.map(\.id), [1, 2])
        XCTAssertEqual(groups[1].day, LocalDay(year: 2026, month: 11, day: 8))
    }

    func testDurationAndDistanceFormats() {
        XCTAssertEqual(formatDuration(42_000), "42 s")
        XCTAssertEqual(formatDuration(42 * 60_000), "42 min")
        XCTAssertEqual(formatDuration(65 * 60_000), "1 h 05 min")
        XCTAssertEqual(formatDistance(12_345, .kmh), "12.3 km")
    }
}

final class TrackCodecTests: XCTestCase {
    func testTheTrackBlobRoundTrips() {
        let pts = [TrackPointRecord(tripId: 3, timeMs: 1_762_650_000_123, latitude: -7.123456789, longitude: 110.987654321, speedMps: 12.5, bearingDeg: 270, accuracyM: 3.5),
                   TrackPointRecord(tripId: 3, timeMs: 1_762_650_001_000, latitude: -7.2, longitude: 110.9, speedMps: 0, bearingDeg: nil, accuracyM: 12)]
        let d = TrackCodec.encode(pts)
        XCTAssertEqual(d.count, 72)
        XCTAssertEqual(TrackCodec.decode(d, tripId: 3), pts)
        XCTAssertTrue(TrackCodec.decode(Data(), tripId: 1).isEmpty)
    }
}
