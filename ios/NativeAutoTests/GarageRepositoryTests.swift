import XCTest
@testable import NativeAuto

/// Port of Android `GarageRepositoryTest`: the Garage against the in-memory store and a real `TripTracker`.
final class GarageRepositoryTests: XCTestCase {
    private static let wallBase: Int64 = 1_700_000_000_000

    final class Rig {
        let vt = VirtualScheduler()
        let source = TestSource()
        let dao = InMemoryStore()
        let tracker: TripTracker
        let wall: TestWall
        var garage: GarageRepository!
        let nmax = BikeDraft(name: "Gen 1", model: .nmaxGen1, tankLitres: 6.6, reserveLitres: 1.3, fuel: .pertamax, startKmPerL: defaultKmPerL)
        var north = 0.0
        private var moved = false

        init() {
            tracker = TripTracker(source: source, clock: vt, scheduler: vt)
            wall = TestWall(vt, base: GarageRepositoryTests.wallBase)
            garage = GarageRepository(dao: dao, wall: wall, zone: TimeZone(identifier: "Asia/Jakarta")!, live: { [unowned self] in
                liveTrip(state: self.tracker.state, stats: self.tracker.stats, nowNanos: self.vt.nowNanos(), nowMs: self.wall.nowMs())
            })
            tracker.onChange = { [unowned self] in
                self.garage.checkWhichBike(tripStartedAtNanos: self.tracker.stats.startedAtNanos, fix: self.tracker.stats.lastSample)
            }
        }

        func now() -> Int64 { wall.nowMs() }
        func advance(_ ms: Int64) { vt.advance(by: ms) }

        func draft(_ name: String, _ model: BikeModel = .nmaxGen1) -> BikeDraft { var d = nmax; d.name = name; d.model = model; return d }

        /// A saved trip of `km` for `bikeId`, ending now.
        func savedTrip(_ bikeId: String?, _ km: Double, startedAgoMs: Int64 = 600_000) {
            let end = now()
            dao.trips.append(TripRecord(id: Int64(dao.trips.count + 1), startedAtMs: end - startedAgoMs, endedAtMs: end, durationMs: startedAgoMs,
                                        movingMs: startedAgoMs, distanceM: km * 1000, maxSpeedMps: 20, startLat: 0, startLon: 0,
                                        endLat: -7.8, endLon: 110.37, track: "", bikeId: bikeId))
        }

        func fix() -> SpeedSample {
            SpeedSample(timestampNanos: vt.nowNanos(), speedMps: 20, speedAccuracyMps: 0.5, horizontalAccuracyM: 4,
                        latitude: north / metersPerDegreeLat, longitude: 0)
        }

        /// Drives the live trip `seconds` at 20 m/s (the first call starts with a fix where it stands).
        func drive(_ seconds: Int) {
            if !moved { moved = true; source.emit(fix()) }
            for _ in 0..<seconds {
                vt.advance(by: 1_000)
                north += 20
                source.emit(fix())
            }
        }

        func refuel(full: Bool, litres: Double, odometerKm: Double, price: Double = 12_500) -> RefuelDraft {
            RefuelDraft(full: full, litres: litres, pricePerL: price, totalRp: litres * price, fuel: .pertamax, odometerKm: odometerKm)
        }
    }

    func testTheFirstBikeIsActiveAndAdoptsTheOldTrips() {
        let rig = Rig()
        rig.savedTrip(nil, 5)
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2", .nmaxGen2), odometerKm: nil)
        XCTAssertEqual(rig.garage.activeId(), gen1)
        XCTAssertEqual(rig.dao.trips[0].bikeId, gen1)
        XCTAssertEqual(rig.dao.allBikes().map(\.active), [true, false])
        XCTAssertEqual(rig.garage.estimateNow(gen1), 12_000)
        XCTAssertNil(rig.garage.estimateNow(gen2))
    }

    func testSavedTripsOfTheBikeAdvanceItsOdometerTimesTheFactor() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        rig.advance(60_000)
        rig.savedTrip(gen1, 10, startedAgoMs: 30_000)
        rig.savedTrip("other", 99, startedAgoMs: 30_000)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 12_010, accuracy: 1e-6)
        XCTAssertEqual(rig.garage.odometer(gen1).estimateKm!, 12_010, accuracy: 1e-6)
    }

    func testACorrectionReAnchorsAndTeachesTheFactor() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 100, startedAgoMs: 3_000_000)
        XCTAssertEqual(rig.garage.enterReading(gen1, km: 1_104), .store(.correction))
        XCTAssertEqual(rig.dao.bike(gen1)!.odoFactor, 1.02, accuracy: 1e-9)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 1_104, accuracy: 1e-9)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 50, startedAgoMs: 3_000_000)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 1_104 + 51, accuracy: 1e-6)
    }

    func testAReadingTakenMidTripCountsOnlyTheRestOfThatTrip() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.tracker.start()
        rig.drive(50)
        rig.garage.enterReading(gen1, km: 2_000)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 2_000, accuracy: 1e-6)
        rig.drive(25)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 2_000.5, accuracy: 1e-6)
        XCTAssertEqual(rig.garage.odometer(gen1).estimateKm!, 2_000.5, accuracy: 0.011)
        let started = rig.now() - 76_000
        rig.tracker.end()
        rig.dao.trips.append(TripRecord(id: 1, startedAtMs: started, endedAtMs: rig.now(), durationMs: 76_000, movingMs: 76_000,
                                        distanceM: rig.tracker.stats.distanceM, maxSpeedMps: 20, startLat: 0, startLon: 0, endLat: 0, endLon: 0,
                                        track: "", bikeId: gen1))
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 2_000.5, accuracy: 1e-6)
    }

    func testSwitchingToABikeIdleForDaysAsksForItsOdometerAndAwayKmIsStored() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: 30_000)
        rig.garage.activate(gen2)
        XCTAssertNil(rig.garage.prompt)
        rig.garage.activate(gen1)
        rig.advance(awayAfterMs)
        rig.garage.activate(gen2)
        guard case .odometerNow(let bike, let estimate) = rig.garage.prompt else { return XCTFail("no prompt") }
        XCTAssertEqual(bike.id, gen2)
        XCTAssertEqual(estimate, 30_000)

        rig.garage.answerOdometerPrompt(30_230)
        XCTAssertNil(rig.garage.prompt)
        let r = rig.dao.lastReading(gen2)!
        XCTAssertEqual(r.readingKind, .away)
        XCTAssertEqual(r.awayKm, 230, accuracy: 1e-9)
        XCTAssertEqual(rig.garage.estimateNow(gen2), 30_230)
        XCTAssertEqual(rig.dao.bike(gen2)!.odoFactor, 1.0)
    }

    func testSkippingTheAwayPromptStoresNothing() {
        let rig = Rig()
        rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: 30_000)
        rig.advance(awayAfterMs)
        rig.garage.activate(gen2)
        rig.garage.answerOdometerPrompt(nil)
        XCTAssertNil(rig.garage.prompt)
        XCTAssertEqual(rig.dao.readings.filter { $0.bikeId == gen2 }.count, 1)
    }

    func testDeletingTheActiveBikeHandsOverToTheMostRecentlyUsedOne() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: nil)
        rig.savedTrip(gen1, 5)
        rig.garage.deleteBike(gen1)
        XCTAssertEqual(rig.garage.activeId(), gen2)
        XCTAssertNil(rig.dao.trips[0].bikeId)
        XCTAssertTrue(rig.dao.readings.allSatisfy { $0.bikeId != gen1 })
    }

    func testWhichBikeIsAskedOncePerTripWhenItStartsFarFromTheLastRide() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: 30_000)
        rig.savedTrip(gen1, 5)
        rig.tracker.start()
        rig.drive(2)
        guard case .whichBike(let bikes, let activeId, _) = rig.garage.prompt else { return XCTFail("no prompt") }
        XCTAssertEqual(activeId, gen1)
        XCTAssertEqual(bikes.map(\.id), [gen1, gen2])
        rig.garage.answerWhichBike(gen2)
        XCTAssertEqual(rig.garage.activeId(), gen2)
        rig.drive(2)
        XCTAssertNil(rig.garage.prompt)
    }

    // 11b

    func testUntrackedKmReAnchorTheOdometerAndTeachNothing() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 100, startedAgoMs: 3_000_000)
        XCTAssertTrue(rig.garage.addUntrackedKm(gen1, 42))
        let r = rig.dao.lastReading(gen1)!
        XCTAssertEqual(r.readingKind, .untracked)
        XCTAssertEqual(r.awayKm, 42)
        XCTAssertEqual(r.km, 1_142, accuracy: 1e-9)
        XCTAssertEqual(rig.garage.estimateNow(gen1)!, 1_142, accuracy: 1e-9)
        XCTAssertEqual(rig.dao.bike(gen1)!.odoFactor, 1.0)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: nil)
        XCTAssertFalse(rig.garage.addUntrackedKm(gen2, 10))
        XCTAssertFalse(rig.garage.addUntrackedKm(gen1, 0))
    }

    func testARefuelAtTheEstimateKeepsItExactlyADifferentReadingIsStoredAsACorrection() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 10.25, startedAgoMs: 3_000_000)
        rig.garage.logRefuel(gen1, rig.refuel(full: true, litres: 5, odometerKm: 1_010, price: 12_900))
        XCTAssertEqual(rig.dao.readings.count, 1)
        XCTAssertEqual(rig.dao.refuelRows[0].odometerKm, 1_010.25, accuracy: 1e-9)
        XCTAssertEqual(rig.dao.prices.single?.pricePerL, 12_900)
        rig.advance(60_000)
        rig.garage.logRefuel(gen1, rig.refuel(full: false, litres: 1, odometerKm: 1_050))
        XCTAssertEqual(rig.dao.lastReading(gen1)!.readingKind, .correction)
        XCTAssertEqual(rig.dao.refuelRows.last!.odometerKm, 1_050)
        XCTAssertEqual(rig.dao.prices.single?.pricePerL, 12_500)
    }

    func testAGaugeReadingSetsTheLevelUntilAFullFillDashKmPerLitreTrains() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.garage.setFuelLevel(gen1, bars: 3, ofBars: 6, odometerKm: 1_000.4)
        var f = rig.garage.fuel(gen1)
        XCTAssertEqual(f.level.litres!, gaugeLitres(3, 6, tankLitres: 6.6), accuracy: 1e-9)
        XCTAssertTrue(f.level.fromGauge)
        XCTAssertEqual(rig.dao.readings.count, 1)
        XCTAssertTrue(rig.garage.addDashKmPerL(gen1, kmPerL: 42, tripKm: 20))
        XCTAssertFalse(rig.garage.addDashKmPerL(gen1, kmPerL: 500, tripKm: 20))
        XCTAssertEqual(rig.garage.fuel(gen1).consumption.dashUsed, 1)
        rig.advance(60_000)
        rig.garage.logRefuel(gen1, rig.refuel(full: true, litres: 4, odometerKm: 1_000))
        f = rig.garage.fuel(gen1)
        XCTAssertFalse(f.level.fromGauge)
        XCTAssertEqual(f.level.litres!, 6.6, accuracy: 1e-9)
    }

    func testFuelStateFollowsFillUpsRidesAndUntrackedKm() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        XCTAssertNil(rig.garage.fuel(gen1).level.litres)
        rig.garage.logRefuel(gen1, rig.refuel(full: true, litres: 5, odometerKm: 1_000))
        var f = rig.garage.fuel(gen1)
        XCTAssertEqual(f.level.litres!, 6.6, accuracy: 1e-9)
        XCTAssertEqual(f.level.rangeKm!, 6.6 * defaultKmPerL, accuracy: 1e-9)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 72, startedAgoMs: 3_000_000)
        rig.garage.addUntrackedKm(gen1, 36)
        f = rig.garage.fuel(gen1)
        XCTAssertEqual(f.level.litres!, 6.6 - 3.0, accuracy: 1e-9)
        XCTAssertEqual(f.level.sinceRefuelKm!, 108, accuracy: 1e-9)
        rig.garage.logRefuel(gen1, rig.refuel(full: true, litres: 3, odometerKm: 1_108))
        f = rig.garage.fuel(gen1)
        XCTAssertEqual(f.consumption.kmPerL, 36, accuracy: 1e-9)
        XCTAssertEqual(f.level.litres!, 6.6, accuracy: 1e-9)
        XCTAssertEqual(f.refuels.count, 2)
        XCTAssertEqual(f.pricePerL, 12_500)
    }

    func testAwayKmMakeTheFuelUnknownUntilTheNextFullFill() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: 30_000)
        rig.garage.activate(gen2)
        rig.garage.logRefuel(gen2, rig.refuel(full: true, litres: 5, odometerKm: 30_000))
        rig.garage.activate(gen1)
        rig.advance(awayAfterMs)
        rig.garage.activate(gen2)
        rig.garage.answerOdometerPrompt(30_200)
        let f = rig.garage.fuel(gen2)
        XCTAssertNil(f.level.litres)
        XCTAssertEqual(f.level.unknownBecause, .away)
        rig.garage.logRefuel(gen2, rig.refuel(full: true, litres: 6, odometerKm: 30_200))
        XCTAssertEqual(rig.garage.fuel(gen2).level.litres!, rig.dao.bike(gen2)!.tankLitres, accuracy: 1e-9)
        XCTAssertFalse(rig.garage.fuel(gen2).consumption.learned)
    }

    func testDeletingABikeDropsItsFillUps() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 1_000)
        rig.garage.logRefuel(gen1, rig.refuel(full: true, litres: 5, odometerKm: 1_000))
        rig.garage.deleteBike(gen1)
        XCTAssertTrue(rig.dao.refuelRows.isEmpty)
    }

    // 11c

    func testAServiceItemCountsFromWhenItWasAddedAndMarkDoneRestartsIt() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let oil = rig.garage.addService(gen1, ServiceDraft(name: "Engine oil", kind: .distance, intervalKm: 2_000, intervalMonths: nil, dueDateMs: nil, lastDoneKm: nil))
        XCTAssertEqual(rig.garage.services(gen1)[0].dueInKm!, 2_000, accuracy: 1e-9)
        rig.advance(3_600_000)
        rig.savedTrip(gen1, 1_950, startedAgoMs: 3_000_000)
        let due = rig.garage.services(gen1)[0]
        XCTAssertEqual(due.level, .soon)
        XCTAssertEqual(rig.garage.activeCard()!.reminder, due)

        rig.garage.markServiceDone(oil, odometerKm: 13_960, costRp: 55_000, note: " Yamalube ")
        let after = rig.garage.services(gen1)[0]
        XCTAssertEqual(after.dueInKm!, 2_000, accuracy: 1e-9)
        XCTAssertEqual(after.level, .ok)
        XCTAssertEqual(rig.dao.lastReading(gen1)!.readingKind, .correction)
        let record = rig.garage.serviceHistory(gen1).single!
        XCTAssertEqual(record.odometerKm, 13_960)
        XCTAssertEqual(record.note, "Yamalube")
        XCTAssertEqual(record.itemName, "Engine oil")
        XCTAssertNil(rig.garage.activeCard()!.reminder)
    }

    func testADateItemMovesOnAYearWhenPaid() {
        let rig = Rig()
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: nil)
        let due = rig.now() + 5 * 86_400_000
        let tax = rig.garage.addService(gen1, ServiceDraft(name: "Vehicle tax", kind: .date, intervalKm: nil, intervalMonths: 12, dueDateMs: due, lastDoneKm: nil))
        XCTAssertEqual(rig.garage.services(gen1)[0].level, .soon)
        rig.garage.markServiceDone(tax, odometerKm: nil, costRp: nil, note: nil)
        XCTAssertGreaterThan(rig.dao.service(tax)!.dueDateMs!, rig.now() + 360 * 86_400_000)
        XCTAssertEqual(rig.garage.services(gen1)[0].level, .ok)
        XCTAssertNil(rig.garage.serviceHistory(gen1)[0].odometerKm)
        XCTAssertTrue(rig.dao.readings.isEmpty)
    }

    func testTheCardFollowsTheActiveBikeAndDeletingABikeTakesItsServices() {
        let rig = Rig()
        XCTAssertNil(rig.garage.activeCard())
        let gen1 = rig.garage.addBike(rig.nmax, odometerKm: 12_000)
        let gen2 = rig.garage.addBike(rig.draft("Gen 2"), odometerKm: 30_000)
        rig.garage.addService(gen2, ServiceDraft(name: "CVT belt", kind: .distance, intervalKm: 20_000, intervalMonths: nil, dueDateMs: nil, lastDoneKm: nil))
        let card = rig.garage.activeCard()!
        XCTAssertEqual(card.bike.id, gen1)
        XCTAssertEqual(card.odometer.estimateKm, 12_000)
        XCTAssertNil(card.nextService)
        rig.garage.activate(gen2)
        XCTAssertEqual(rig.garage.activeCard()!.nextService!.item.name, "CVT belt")
        rig.garage.deleteBike(gen2)
        XCTAssertTrue(rig.dao.serviceItems.isEmpty)
    }
}
