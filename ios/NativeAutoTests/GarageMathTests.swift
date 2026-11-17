import XCTest
@testable import NativeAuto

/// Port of Android `OdometerTest`.
final class OdometerTests: XCTestCase {
    private func reading(_ km: Double, timeMs: Int64 = 1_000, offsetM: Double = 0) -> OdometerReading {
        OdometerReading(bikeId: "b", timeMs: timeMs, km: km, kind: ReadingKind.set.rawValue, tripOffsetM: offsetM)
    }

    func testNoReadingMeansNoEstimate() { XCTAssertNil(estimateOdometerKm(nil, factor: 1, gpsMetersSince: 5_000)) }

    func testEstimateIsTheReadingPlusGpsKmTimesTheFactor() {
        XCTAssertEqual(estimateOdometerKm(reading(12_000), factor: 1, gpsMetersSince: 10_000)!, 12_010, accuracy: 1e-9)
        XCTAssertEqual(estimateOdometerKm(reading(12_000), factor: 1.03, gpsMetersSince: 10_000)!, 12_010.3, accuracy: 1e-9)
    }

    func testALiveTripThatStartedAfterTheReadingCountsInFull() {
        XCTAssertEqual(gpsMetersSince(reading(100, timeMs: 1_000), savedM: 5_000, live: LiveTrip(startedAtMs: 2_000, distanceM: 2_000)), 7_000, accuracy: 1e-9)
    }

    func testALiveTripRunningAtTheReadingCountsOnlyPastItsOffset() {
        let r = reading(100, timeMs: 5_000, offsetM: 1_500)
        XCTAssertEqual(gpsMetersSince(r, savedM: 0, live: LiveTrip(startedAtMs: 1_000, distanceM: 2_000)), 500, accuracy: 1e-9)
        XCTAssertEqual(gpsMetersSince(r, savedM: 0, live: LiveTrip(startedAtMs: 1_000, distanceM: 1_000)), 0, accuracy: 1e-9)
        XCTAssertEqual(gpsMetersSince(r, savedM: 300, live: nil), 300, accuracy: 1e-9)
    }

    func testACorrectionMovesTheFactorHalfwayToTheMeasuredRatio() {
        XCTAssertEqual(learnFactor(1.0, prevKm: 1_000, newKm: 1_103, gpsKm: 100), 1.015, accuracy: 1e-9)
        XCTAssertEqual(learnFactor(1.015, prevKm: 1_103, newKm: 1_206, gpsKm: 100), 1.0225, accuracy: 1e-9)
    }

    func testShortSpansAndImplausibleRatiosTeachNothing() {
        XCTAssertEqual(learnFactor(1.0, prevKm: 1_000, newKm: 1_030, gpsKm: 20), 1.0)
        XCTAssertEqual(learnFactor(1.02, prevKm: 1_000, newKm: 1_150, gpsKm: 100), 1.02)
        XCTAssertEqual(learnFactor(1.02, prevKm: 1_000, newKm: 900, gpsKm: 100), 1.02)
    }

    func testAnUnloggedRideWithoutTheAppIsntLearnedAsDrift() {
        XCTAssertEqual(learnFactor(1.0, prevKm: 1_000, newKm: 1_115, gpsKm: 100), 1.0)
        XCTAssertEqual(learnFactor(1.0, prevKm: 1_000, newKm: 1_088, gpsKm: 100), 1.0)
        XCTAssertEqual(learnFactor(1.0, prevKm: 1_000, newKm: 1_108, gpsKm: 100), 1.04, accuracy: 1e-9)
    }

    func testTheFirstReadingIsASetLaterOnesCorrections() {
        XCTAssertEqual(decideReading(12_000, estimate: nil, away: false), .store(.set))
        XCTAssertEqual(decideReading(12_000, estimate: nil, away: true), .store(.set))
        XCTAssertEqual(decideReading(12_000, estimate: 12_000, away: false), .store(.correction))
        XCTAssertEqual(decideReading(11_900, estimate: 12_000, away: false), .store(.correction))
    }

    func testAwayKmIsTheRiseOverTheEstimate() {
        XCTAssertEqual(decideReading(12_230, estimate: 12_000, away: true), .store(.away, awayKm: 230))
        XCTAssertEqual(decideReading(12_000.4, estimate: 12_000, away: true), .unchanged)
        XCTAssertEqual(decideReading(11_950, estimate: 12_000, away: true), .store(.correction))
    }

    func testAwayCheckAfterThreeDaysUnused() {
        let day: Int64 = 24 * 60 * 60 * 1000
        XCTAssertFalse(needsAwayCheck(lastUsedAtMs: 0, nowMs: 3 * day - 1))
        XCTAssertTrue(needsAwayCheck(lastUsedAtMs: 0, nowMs: 3 * day))
    }

    func testWhichBikeAsksOnlyWithTwoBikesAndAFarStart() {
        let yk = GeoPoint(-7.7956, 110.3695), far = GeoPoint(-6.2088, 106.8456), near = GeoPoint(-7.80, 110.40)
        XCTAssertNil(whichBikeDistanceKm(bikeCount: 1, lastRideEnd: yk, here: far))
        XCTAssertNil(whichBikeDistanceKm(bikeCount: 2, lastRideEnd: nil, here: far))
        XCTAssertNil(whichBikeDistanceKm(bikeCount: 2, lastRideEnd: yk, here: near))
        let km = whichBikeDistanceKm(bikeCount: 2, lastRideEnd: yk, here: far)!
        XCTAssertTrue((400.0...470.0).contains(km), "was \(km)")
    }

    func testNumbersTypedEitherWay() {
        XCTAssertEqual(parseNumber("6.6"), 6.6)
        XCTAssertEqual(parseNumber("6,6"), 6.6)
        XCTAssertEqual(parseNumber("12,345", thousands: true), 12_345)
        XCTAssertEqual(parseNumber("12,345")!, 12.345, accuracy: 1e-9)
        XCTAssertEqual(parseNumber("12.345,6")!, 12_345.6, accuracy: 1e-9)
        XCTAssertEqual(parseNumber("12,345.6")!, 12_345.6, accuracy: 1e-9)
        XCTAssertEqual(parseNumber("1.234.567"), 1_234_567)
        XCTAssertEqual(parseNumber("12.345")!, 12.345, accuracy: 1e-9)
        XCTAssertEqual(parseNumber("12.345", thousands: true), 12_345)
        XCTAssertEqual(parseNumber(" 12345 ", thousands: true), 12_345)
        XCTAssertNil(parseNumber(""))
        XCTAssertNil(parseNumber("abc"))
        XCTAssertNil(parseNumber("-5"))
    }

    func testOdometerFormat() {
        XCTAssertEqual(formatOdometer(12_345.6), "12,346 km")
        XCTAssertEqual(formatOdometer(0.2), "0 km")
    }
}

/// Port of Android `FuelTest` and `FuelLevel12bTest`.
final class FuelTests: XCTestCase {
    private var nextId: Int64 = 1

    private func fill(_ odo: Double, _ litres: Double, full: Bool = true, timeMs: Int64? = nil, rp: Double? = nil) -> RefuelRecord {
        defer { nextId += 1 }
        return RefuelRecord(id: nextId, bikeId: "b", timeMs: timeMs ?? Int64(odo) * 1_000, odometerKm: odo, litres: litres, pricePerL: 12_500,
                            totalRp: rp ?? litres * 12_500, full: full, fuelType: FuelType.pertamax.rawValue)
    }

    private func mark(_ odo: Double, _ bars: Int, _ of: Int, tank: Double = 6.6) -> FuelMark {
        defer { nextId += 1 }
        return FuelMark(id: nextId, bikeId: "b", timeMs: Int64(odo) * 1_000, odometerKm: odo, bars: bars, ofBars: of,
                        litres: gaugeLitres(bars, of, tankLitres: tank))
    }

    private func dash(_ kmpl: Double, _ timeMs: Int64) -> DashKmPerL {
        defer { nextId += 1 }
        return DashKmPerL(id: nextId, bikeId: "b", timeMs: timeMs, kmPerL: kmpl, tripKm: 20)
    }

    private func learn(_ start: Double, _ fills: [RefuelRecord], _ away: [Int64] = [], _ dash: [DashKmPerL] = []) -> Consumption {
        learnConsumption(startKmPerL: start, refuels: fills, awayTimesMs: away, dash: dash)
    }

    private func level(_ tank: Double, _ kmpl: Double, _ fills: [RefuelRecord], _ away: [Int64], _ odo: Double?, _ marks: [FuelMark] = []) -> FuelLevel {
        fuelLevel(tankLitres: tank, kmPerL: kmpl, refuels: fills, awayTimesMs: away, odometerNowKm: odo, marks: marks)
    }

    func testNoFullToFullSpanKeepsTheStartingValue() {
        let c = learn(36, [fill(1_000, 5), fill(1_100, 2, full: false)])
        XCTAssertEqual(c.kmPerL, 36)
        XCTAssertFalse(c.learned)
        XCTAssertTrue(c.measures.isEmpty)
    }

    func testAFullToFullSpanCountsThePartialsInBetween() {
        let c = learn(36, [fill(1_000, 5), fill(1_100, 1, full: false), fill(1_240, 5)])
        XCTAssertEqual(c.measures.count, 1)
        XCTAssertEqual(c.measures[0].kmPerL, 40, accuracy: 1e-9)
        XCTAssertEqual(c.kmPerL, 38, accuracy: 1e-9)
        XCTAssertTrue(c.learned)
    }

    func testLaterSpansMoveTheEstimateLessAndOrderComesFromTime() {
        let fills = [fill(1_000, 5), fill(1_200, 5), fill(1_350, 5)]
        let c = learn(36, fills.reversed())
        XCTAssertEqual(c.kmPerL, 35.6, accuracy: 1e-9)
        XCTAssertEqual(c.measures.map(\.kmPerL), [40, 30])
    }

    func testOutliersAndImpossibleValuesAreIgnored() {
        let c = learn(36, [fill(1_000, 5), fill(1_180, 5), fill(1_480, 5), fill(1_485, 5), fill(1_665, 5)])
        XCTAssertEqual(c.measures.map(\.used), [true, false, false, true])
        XCTAssertEqual(c.kmPerL, 36, accuracy: 1e-9)
    }

    func testTheFirstMeasureMayBeFarFromAWrongGuess() {
        XCTAssertEqual(learn(36, [fill(1_000, 5), fill(1_110, 5)]).kmPerL, 29, accuracy: 1e-9)
    }

    func testASpanWithAwayKmTeachesNothing() {
        let c = learn(36, [fill(1_000, 5, timeMs: 1_000), fill(1_400, 5, timeMs: 3_000)], [2_000])
        XCTAssertEqual(c.kmPerL, 36)
        XCTAssertFalse(c.measures[0].used)
    }

    func testUnknownBeforeAnyFullFill() {
        let l = level(6.6, 36, [fill(1_000, 2, full: false)], [], 1_050)
        XCTAssertNil(l.litres)
        XCTAssertNil(l.rangeKm)
        XCTAssertEqual(l.unknownBecause, .noFullFill)
        XCTAssertEqual(l.sinceRefuelKm!, 50, accuracy: 1e-9)
    }

    func testFullAtTheFillMinusTheKmSince() {
        let l = level(6.6, 36, [fill(1_000, 5)], [], 1_072)
        XCTAssertEqual(l.litres!, 4.6, accuracy: 1e-9)
        XCTAssertEqual(l.rangeKm!, 4.6 * 36, accuracy: 1e-9)
        XCTAssertEqual(l.toFillLitres!, 2.0, accuracy: 1e-9)
        XCTAssertEqual(l.fraction!, 4.6 / 6.6, accuracy: 1e-9)
    }

    func testAPartialAddsItsLitresCappedAtTheTank() {
        XCTAssertEqual(level(6.6, 36, [fill(1_000, 5), fill(1_108, 1, full: false)], [], 1_144).litres!, 3.6, accuracy: 1e-9)
        XCTAssertEqual(level(6.6, 36, [fill(1_000, 5), fill(1_036, 4, full: false)], [], 1_036).litres!, 6.6, accuracy: 1e-9)
    }

    func testNeverBelowEmpty() { XCTAssertEqual(level(6.6, 36, [fill(1_000, 5)], [], 2_000).litres, 0) }

    func testAwayKmAfterTheLastFullFillMakeItUnknownAFullFillAfterThemRestoresIt() {
        let fills = [fill(1_000, 5, timeMs: 1_000)]
        XCTAssertEqual(level(6.6, 36, fills, [2_000], 1_200).unknownBecause, .away)
        XCTAssertEqual(level(6.6, 36, fills + [fill(1_200, 5, timeMs: 3_000)], [2_000], 1_200).litres!, 6.6, accuracy: 1e-9)
    }

    func testAmountsFromRupiahOrLitres() {
        let a = refuelAmounts(.rupiah, amount: 50_000, pricePerL: 12_500)!
        XCTAssertEqual(a.litres, 4, accuracy: 1e-9)
        XCTAssertEqual(a.rp, 50_000)
        let b = refuelAmounts(.litres, amount: 4.5, pricePerL: 10_000)!
        XCTAssertEqual(b.litres, 4.5)
        XCTAssertEqual(b.rp, 45_000, accuracy: 1e-9)
        XCTAssertNil(refuelAmounts(.rupiah, amount: nil, pricePerL: 12_500))
        XCTAssertNil(refuelAmounts(.rupiah, amount: 50_000, pricePerL: 0))
    }

    func testPricesFallBackToTheDefault() {
        XCTAssertEqual(priceOf(.pertalite, []), FuelType.pertalite.defaultPricePerL)
        XCTAssertEqual(FuelType.allCases.map(\.defaultPricePerL), [10_000, 15_950, 19_150, 19_600])
        XCTAssertEqual(priceOf(.pertalite, [FuelPrice("PERTALITE", 10_200, 1)]), 10_200)
    }

    func testCostPerKmSpansFirstToLastFullFill() {
        let fills = [fill(1_000, 5, rp: 60_000), fill(1_100, 1, full: false, rp: 12_000), fill(1_200, 4, rp: 48_000)]
        XCTAssertEqual(costPerKm(fills)!, 300, accuracy: 1e-9)
        XCTAssertNil(costPerKm(Array(fills.prefix(1))))
    }

    func testSpendPerPeriod() {
        let zone = TimeZone(identifier: "Asia/Jakarta")!
        func at(_ y: Int, _ m: Int, _ d: Int) -> Int64 { msOf(calendar(zone).date(from: DateComponents(year: y, month: m, day: d, hour: 12))!) }
        let fills = [fill(1, 1, timeMs: at(2026, 10, 20), rp: 10_000), fill(2, 1, timeMs: at(2026, 11, 1), rp: 20_000),
                     fill(3, 1, timeMs: at(2026, 11, 8), rp: 40_000)]
        let s = spend(fills, nowMs: at(2026, 11, 9), zone: zone)
        XCTAssertEqual(s.last7Days, 40_000)
        XCTAssertEqual(s.thisMonth, 60_000)
        XCTAssertEqual(s.lastMonth, 10_000)
        XCTAssertEqual(spend([], nowMs: at(2026, 11, 9), zone: TimeZone(identifier: "UTC")!).thisMonth, 0)
    }

    func testFormatting() {
        XCTAssertEqual(formatRupiah(98_000.4), "Rp 98.000")
        XCTAssertEqual(formatRupiah(1_250_000), "Rp 1.250.000")
        XCTAssertEqual(formatLitres(4.96), "5.0 L")
        XCTAssertEqual(formatKmPerL(36.44), "36.4 km/L")
    }

    func testLitresRunFromZeroToTheTankSize() {
        XCTAssertTrue(refuelLitresFit(0.1, tankLitres: 6.6))
        XCTAssertTrue(refuelLitresFit(6.6, tankLitres: 6.6))
        XCTAssertFalse(refuelLitresFit(0, tankLitres: 6.6))
        XCTAssertFalse(refuelLitresFit(6.7, tankLitres: 6.6))
        XCTAssertFalse(refuelLitresFit(-1, tankLitres: 6.6))
    }

    func testTheSwitchMakesAFullAnchorASmallerEntryStaysPartial() {
        XCTAssertTrue(countsAsFull(filledToTop: true, litres: 2, spaceLeftL: 4))
        XCTAssertFalse(countsAsFull(filledToTop: false, litres: 2, spaceLeftL: 4))
    }

    func testMoreThanTheRoomLeftCountsAsFullWithinTheToleranceItDoesNot() {
        XCTAssertTrue(countsAsFull(filledToTop: false, litres: 4.5, spaceLeftL: 3))
        XCTAssertFalse(countsAsFull(filledToTop: false, litres: 3.1, spaceLeftL: 3))
        XCTAssertFalse(countsAsFull(filledToTop: false, litres: 5, spaceLeftL: nil))
    }

    func testAnOverflowFillCapsTheLevelAtAFullTank() {
        let fills = [fill(1_000, 5), fill(1_040, 4, full: countsAsFull(filledToTop: false, litres: 4, spaceLeftL: 1.1))]
        XCTAssertEqual(level(6.6, 40, fills, [], 1_040).litres!, 6.6, accuracy: 1e-9)
    }

    func testAnOverflowSpanTeachesKmPerLitreOnlyIfTheOutlierRuleAllowsIt() {
        let base = [fill(1_000, 5), fill(1_200, 5), fill(1_400, 5)]
        let c = learn(40, base + [fill(1_450, 3.3, full: countsAsFull(filledToTop: false, litres: 3.3, spaceLeftL: 0.5))])
        XCTAssertEqual(c.tanksUsed, 2)
        XCTAssertFalse(c.measures.last!.used)
        let ok = learn(40, base + [fill(1_600, 5, full: countsAsFull(filledToTop: false, litres: 5, spaceLeftL: 0.5))])
        XCTAssertEqual(ok.tanksUsed, 3)
    }

    // 12b

    func testGaugeBarsByModel() {
        XCTAssertEqual(gaugeBars(.nmaxGen1), 6)
        XCTAssertEqual(gaugeBars(.nmaxGen2), 8)
    }

    func testTheLastLitBarCountsAsHalfUsed() {
        XCTAssertEqual(gaugeLitres(0, 6, tankLitres: 6.6), 0)
        XCTAssertEqual(gaugeLitres(3, 6, tankLitres: 6.6), 2.5 / 6 * 6.6, accuracy: 1e-9)
        XCTAssertEqual(gaugeLitres(8, 8, tankLitres: 7.1), 7.5 / 8 * 7.1, accuracy: 1e-9)
    }

    func testAGaugeReadingGivesALevelWithoutAnyFullFill() {
        let l = level(6.6, 36, [], [], 1_036, [mark(1_000, 3, 6)])
        XCTAssertTrue(l.fromGauge)
        XCTAssertEqual(l.litres!, 2.75 - 1.0, accuracy: 1e-9)
        XCTAssertNil(l.unknownBecause)
    }

    func testALaterFullFillReplacesTheGaugeReadingALaterReadingReplacesTheFill() {
        let fills = [fill(1_100, 5)]
        let older = level(6.6, 36, fills, [], 1_100, [mark(1_000, 3, 6)])
        XCTAssertFalse(older.fromGauge)
        XCTAssertEqual(older.litres!, 6.6, accuracy: 1e-9)
        let newer = level(6.6, 36, fills, [], 1_236, [mark(1_200, 2, 6)])
        XCTAssertTrue(newer.fromGauge)
        XCTAssertEqual(newer.litres!, 1.5 / 6 * 6.6 - 1.0, accuracy: 1e-9)
    }

    func testPartialsAfterAGaugeReadingAddUpAwayKmAfterItMakeItUnknown() {
        let m = mark(1_000, 2, 6)
        XCTAssertEqual(level(6.6, 36, [fill(1_036, 2, full: false)], [], 1_036, [m]).litres!, 1.65 - 1.0 + 2.0, accuracy: 1e-9)
        XCTAssertEqual(level(6.6, 36, [], [m.timeMs + 1], 1_036, [m]).unknownBecause, .away)
    }

    func testRangeRoundsDownTo5Km() {
        var l = FuelLevel(litres: 3.9, tankLitres: 6.6, kmPerL: 36, sinceRefuelKm: nil)
        XCTAssertEqual(l.rangeKm!, 140.4, accuracy: 1e-9)
        XCTAssertEqual(l.cautiousRangeKm, 140)
        l.litres = 3.88
        XCTAssertEqual(l.cautiousRangeKm, 135)
    }

    func testDashFiguresNudgeTheEstimateImplausibleOnesAreIgnored() {
        let c = learn(36, [], [], [dash(46, 1), dash(5, 2)])
        XCTAssertEqual(c.kmPerL, 36 + dashWeight * 10, accuracy: 1e-9)
        XCTAssertEqual(c.dashUsed, 1)
        XCTAssertTrue(c.learned)
        XCTAssertEqual(learn(36, [], [], [dash(40, 1), dash(80, 2)]).dashUsed, 1)
    }

    func testTheCautiousFigureIsTheLowerOfTheLongRunAndTheRecentTanks() {
        let c = learn(36, [fill(1_000, 5), fill(1_200, 5), fill(1_350, 5)])
        XCTAssertEqual(c.cautiousKmPerL, min(c.kmPerL, 35), accuracy: 1e-9)
        XCTAssertLessThanOrEqual(c.cautiousKmPerL, c.kmPerL)
        XCTAssertEqual(learn(36, []).cautiousKmPerL, 36)
    }

    // 11c: the Garage card's 5-segment tank (Android `TankSegmentsTest`)

    func testEachSegmentIsAFifthOfTheTank() {
        XCTAssertEqual(tankSegments(1.0, previous: nil), 5)
        XCTAssertEqual(tankSegments(0.81, previous: nil), 5)
        XCTAssertEqual(tankSegments(0.8, previous: nil), 4)
        XCTAssertEqual(tankSegments(0.2, previous: nil), 1)
        XCTAssertEqual(tankSegments(0.01, previous: nil), 1)
        XCTAssertEqual(tankSegments(0.0, previous: nil), 0)
        XCTAssertEqual(tankSegments(1.3, previous: nil), 5)
        XCTAssertEqual(tankSegments(-0.1, previous: nil), 0)
    }

    func testABoundaryMustBeCrossedByTheHysteresisBeforeTheCountChanges() {
        XCTAssertEqual(tankSegments(0.39, previous: 3), 3)
        XCTAssertEqual(tankSegments(0.379, previous: 3), 2)
        XCTAssertEqual(tankSegments(0.41, previous: 2), 2)
        XCTAssertEqual(tankSegments(0.43, previous: 2), 3)
    }

    func testAJumpOfMoreThanOneSegmentIsTakenAtOnce() {
        XCTAssertEqual(tankSegments(1.0, previous: 1), 5)
        XCTAssertEqual(tankSegments(0.1, previous: 4), 1)
    }
}

/// Port of Android `ServiceTest`.
final class ServiceTests: XCTestCase {
    private let zone = TimeZone(identifier: "Asia/Jakarta")!
    private func day(_ y: Int, _ m: Int, _ d: Int) -> Int64 {
        msOf(calendar(zone).date(from: DateComponents(year: y, month: m, day: d))!) + 12 * 3_600_000
    }
    private var now: Int64 { day(2026, 11, 9) }

    private func oil(km: Double? = 2_000, months: Int? = 3, lastKm: Double? = 10_000, lastMs: Int64? = nil) -> ServiceItem {
        ServiceItem(id: 1, bikeId: "b", name: "Engine oil", kind: ServiceKind.distance.rawValue, intervalKm: km, intervalMonths: months,
                    lastDoneMs: lastMs ?? day(2026, 10, 1), lastDoneKm: lastKm, createdAtMs: 0)
    }

    private func tax(_ due: Int64) -> ServiceItem {
        ServiceItem(id: 2, bikeId: "b", name: "Vehicle tax", kind: ServiceKind.date.rawValue, intervalMonths: 12, dueDateMs: due, lastDoneMs: 0, createdAtMs: 0)
    }

    private func due(_ item: ServiceItem, _ odo: Double?) -> ServiceDue { serviceDue(item, odometerNowKm: odo, nowMs: now, zone: zone) }

    func testKmAndMonthsWhicheverComesFirst() {
        let d = due(oil(), 11_000)
        XCTAssertEqual(d.dueInKm!, 1_000, accuracy: 1e-9)
        XCTAssertEqual(d.dueInDays, 53)
        XCTAssertEqual(d.level, .ok)
        XCTAssertEqual(dueText(d), "due in 1,000 km")
        let byTime = due(oil(lastMs: day(2026, 8, 20)), 10_200)
        XCTAssertEqual(byTime.level, .soon)
        XCTAssertEqual(dueText(byTime), "due in 11 days")
    }

    func testAmberWithinATenthOfTheKmIntervalRedPastIt() {
        XCTAssertEqual(due(oil(months: nil), 11_850).level, .soon)
        let over = due(oil(months: nil), 12_050)
        XCTAssertEqual(over.level, .overdue)
        XCTAssertEqual(dueText(over), "overdue by 50 km")
        XCTAssertEqual(reminderText(over), "Engine oil overdue by 50 km")
        XCTAssertEqual(due(oil(km: 500, months: nil), 10_420).level, .soon)
    }

    func testNoIntervalOrNoOdometer() {
        XCTAssertEqual(due(oil(km: nil, months: nil), 11_000).level, .noInterval)
        let noOdo = due(oil(lastKm: nil), nil)
        XCTAssertNil(noOdo.dueInKm)
        XCTAssertEqual(noOdo.dueInDays, 53)
    }

    func testDateItems() {
        XCTAssertEqual(due(tax(day(2027, 3, 1)), nil).level, .ok)
        let soon = due(tax(day(2026, 11, 21)), nil)
        XCTAssertEqual(soon.level, .soon)
        XCTAssertEqual(dueText(soon), "due in 12 days")
        XCTAssertEqual(dueText(due(tax(day(2026, 11, 9)), nil)), "due today")
        let late = due(tax(day(2026, 11, 8)), nil)
        XCTAssertEqual(late.level, .overdue)
        XCTAssertEqual(dueText(late), "overdue by 1 day")
    }

    func testTheReminderIsTheMostUrgentItemDueSoonOrOverdue() {
        let ok = due(oil(), 11_000)
        let soonTax = due(tax(day(2026, 11, 30)), nil)
        var belt = oil(months: nil); belt.id = 3; belt.name = "CVT belt"
        let overdue = due(belt, 12_100)
        XCTAssertEqual(byUrgency([ok, soonTax, overdue]), [overdue, soonTax, ok])
        XCTAssertEqual(reminderOf([ok, soonTax, overdue]), overdue)
        XCTAssertNil(reminderOf([ok]))
    }

    func testAPaidDateItemMovesOnByItsIntervalPastToday() {
        let cal = calendar(zone)
        let next = nextDueDate(day(2026, 11, 20), months: 12, nowMs: now, zone: zone)
        XCTAssertEqual(cal.dateComponents([.year, .month, .day], from: dateOf(next)), DateComponents(year: 2027, month: 11, day: 20))
        let late = nextDueDate(day(2024, 6, 1), months: 12, nowMs: now, zone: zone)
        XCTAssertEqual(cal.dateComponents([.year, .month, .day], from: dateOf(late)), DateComponents(year: 2027, month: 6, day: 1))
    }

    func testDatesAreTypedDayFirst() {
        let ms = parseDate("31/12/2026", zone: zone)!
        XCTAssertEqual(formatDate(ms, zone: zone), "31/12/2026")
        XCTAssertEqual(parseDate(" 31-12-2026 ", zone: zone), ms)
        XCTAssertEqual(parseDate("31.12.2026", zone: zone), ms)
        XCTAssertNil(parseDate("31/02/2026", zone: zone))
        XCTAssertNil(parseDate("2026-12-31", zone: zone))
    }

    func testIntervalText() {
        XCTAssertEqual(intervalText(oil(), zone: zone), "every 2,000 km · 3 months")
        XCTAssertEqual(intervalText(oil(months: nil), zone: zone), "every 2,000 km")
        XCTAssertEqual(intervalText(oil(km: nil, months: nil), zone: zone), "no interval set")
        XCTAssertEqual(intervalText(tax(day(2027, 3, 1)), zone: zone), "yearly · due 1/3/2027")
    }
}
