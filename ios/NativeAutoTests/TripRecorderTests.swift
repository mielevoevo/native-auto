import XCTest
@testable import NativeAuto

/// Port of Android `TripRecorderTest`: a real `TripTracker` in virtual time, the recorder watching it, in-memory
/// history, addresses and checkpoint.
final class TripRecorderTests: XCTestCase {
    private static let speed: Float = 20
    private static let wallBase: Int64 = 1_762_000_000_000

    /// Names a point by its rounded northing; `online` off answers nil, like a failed lookup.
    final class FakeAddresses: AddressResolver {
        var online = true
        var asked: [GeoPoint] = []
        func addressOf(_ point: GeoPoint, _ done: @escaping (String?) -> Void) {
            asked.append(point)
            done(online ? "N \(Int((point.latitude * metersPerDegreeLat).rounded())) m" : nil)
        }
    }

    /// The trip checkpoint, kept across a simulated process death.
    final class FakeCheckpoints: TripCheckpoints {
        var trip: LiveTripRecord?
        var fixes: [SpeedSample] = []
        var writes = 0
        func write(_ trip: LiveTripRecord, newFixes: [SpeedSample], replace: Bool) {
            writes += 1
            if replace { fixes = [] }
            self.trip = trip
            fixes += newFixes
        }
        func read() -> Checkpoint? { trip.map { Checkpoint(trip: $0, fixes: fixes) } }
        func clear() { trip = nil; fixes = [] }
    }

    /// One app process. `after` shares the previous process's storage and GPS (a relaunch after a kill).
    final class Rig {
        let vt: VirtualScheduler
        let source: TestSource
        let dao: InMemoryStore
        let addresses: FakeAddresses
        let checkpoints: FakeCheckpoints
        var bikeId: String?
        let tracker: TripTracker
        var recorder: TripRecorder!
        var north = 0.0

        init(_ vt: VirtualScheduler, after: Rig? = nil) {
            self.vt = vt
            source = after?.source ?? TestSource()
            dao = after?.dao ?? InMemoryStore()
            addresses = after?.addresses ?? FakeAddresses()
            checkpoints = after?.checkpoints ?? FakeCheckpoints()
            tracker = TripTracker(source: source, clock: vt, scheduler: vt)
            recorder = TripRecorder(tracker: tracker, dao: dao, addresses: addresses, bike: { [unowned self] in self.bikeId },
                                    checkpoints: checkpoints, clock: vt, wall: TestWall(vt, base: TripRecorderTests.wallBase))
            // The geometry tests drive a few hundred metres; the real limits have their own tests.
            recorder.minDistanceM = 100
            recorder.minMovingMs = 0
            tracker.onChange = { [unowned self] in self.recorder.observe() }
        }

        func realLimits() -> Rig {
            recorder.minDistanceM = TripRecorder.minDistanceMDefault
            recorder.minMovingMs = TripRecorder.minMovingMsDefault
            return self
        }

        func fix(speed: Float = TripRecorderTests.speed, accuracy: Float = 4, east: Double = 0) -> SpeedSample {
            SpeedSample(timestampNanos: vt.nowNanos(), speedMps: speed, speedAccuracyMps: 0.3, horizontalAccuracyM: accuracy,
                        latitude: north / metersPerDegreeLat, longitude: east / metersPerDegreeLat)
        }

        func emit(_ s: SpeedSample) { source.emit(s) }

        /// A first fix here, then `seconds` of 1 Hz fixes at `speed` due north.
        func drive(_ seconds: Int) {
            emit(fix())
            for _ in 0..<seconds {
                vt.advance(by: 1_000)
                north += Double(TripRecorderTests.speed)
                emit(fix())
            }
        }

        func startTrip() { tracker.start() }

        /// The process dies: nothing it ran sees anything again.
        func kill() {
            tracker.onChange = nil
            tracker.discard()
        }
    }

    private var vt: VirtualScheduler!
    override func setUp() { vt = VirtualScheduler() }

    func testAnEndedTripIsSavedOnceWithItsFiguresPathAndAddresses() {
        let rig = Rig(vt)
        vt.advance(by: 5_000)
        rig.startTrip()
        rig.drive(30)
        rig.tracker.end()
        vt.advance(by: 10_000)
        rig.tracker.discard()

        XCTAssertEqual(rig.dao.trips.count, 1)
        let t = rig.dao.trips[0]
        XCTAssertEqual(t.startedAtMs, Self.wallBase + 5_000)
        XCTAssertEqual(t.endedAtMs, Self.wallBase + 35_000)
        XCTAssertEqual(t.durationMs, 30_000)
        XCTAssertEqual(t.movingMs, 30_000)
        XCTAssertEqual(t.distanceM, 600, accuracy: 1e-6)
        XCTAssertEqual(t.maxSpeedMps, Self.speed)
        XCTAssertEqual(t.startLat, 0, accuracy: 1e-9)
        XCTAssertEqual(t.endLat * metersPerDegreeLat, 600, accuracy: 1e-6)
        let track = Polyline.decode(t.track)
        XCTAssertEqual(track.count, 2)
        XCTAssertEqual(track.last!.latitude * metersPerDegreeLat, 600, accuracy: 1.2)
        XCTAssertEqual(t.startAddress, "N 0 m")
        XCTAssertEqual(t.endAddress, "N 600 m")
    }

    func testATripUnder100mIsntKept() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(4)
        rig.tracker.end()
        XCTAssertTrue(rig.dao.trips.isEmpty)
    }

    func testAFastTripUnder2MinOfMovingIsntKept() {
        let rig = Rig(vt).realLimits()
        rig.startTrip()
        rig.drive(100)
        rig.tracker.end()
        XCTAssertTrue(rig.dao.trips.isEmpty)
    }

    func testALongSlowTripUnder300mIsntKept() {
        let rig = Rig(vt).realLimits()
        rig.startTrip()
        rig.emit(rig.fix(speed: 1))
        for _ in 0..<200 {
            vt.advance(by: 1_000)
            rig.north += 1
            rig.emit(rig.fix(speed: 1))
        }
        rig.tracker.end()
        XCTAssertTrue(rig.dao.trips.isEmpty)
    }

    func testATripOverBothLimitsIsKept() {
        let rig = Rig(vt).realLimits()
        rig.startTrip()
        rig.drive(121)
        rig.tracker.end()
        XCTAssertEqual(rig.dao.trips.single?.distanceM ?? 0, 2_420, accuracy: 1e-6)
    }

    func testTheFullTrackKeepsEveryAcceptedFixWithItsTimeSpeedAndAccuracy() {
        let rig = Rig(vt)
        vt.advance(by: 5_000)
        rig.startTrip()
        rig.drive(30)
        rig.tracker.end()
        let t = rig.dao.trips[0]
        let points = rig.dao.points(t.id)
        XCTAssertEqual(points.count, 31)
        XCTAssertEqual(points.first!.timeMs, Self.wallBase + 5_000)
        XCTAssertEqual(points.last!.timeMs, Self.wallBase + 35_000)
        XCTAssertTrue(points.allSatisfy { $0.speedMps == Self.speed && $0.accuracyM == 4 })
        XCTAssertEqual(points.last!.latitude * metersPerDegreeLat, 600, accuracy: 1e-6)
        XCTAssertEqual(t.stoppedMs, 0)
        XCTAssertNil(t.bikeId)
        rig.dao.delete(t.id)
        XCTAssertTrue(rig.dao.points(t.id).isEmpty)
    }

    func testATripIsTaggedWithTheBikeActiveWhenItIsSaved() {
        let rig = Rig(vt)
        rig.bikeId = "Gen 1"
        rig.startTrip()
        rig.drive(30)
        rig.bikeId = "Gen 2"
        rig.tracker.end()
        XCTAssertEqual(rig.dao.trips[0].bikeId, "Gen 2")
    }

    func testStoppedTimeIsTripTimeBelowWalkingPacePausesExcluded() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(10)
        for _ in 0..<20 {
            vt.advance(by: 1_000)
            rig.emit(rig.fix(speed: 0))
        }
        rig.tracker.pause()
        vt.advance(by: 60_000)
        rig.tracker.end()
        let t = rig.dao.trips[0]
        XCTAssertEqual(t.durationMs, 30_000)
        XCTAssertEqual(t.movingMs, 10_000)
        XCTAssertEqual(t.stoppedMs, 20_000)
    }

    func testTheOutcomeSaysSavedOrTooShortAndClearsOnTheNextStart() {
        let rig = Rig(vt).realLimits()
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end()
        XCTAssertEqual(rig.recorder.lastOutcome, .tooShort)

        rig.tracker.discard()
        rig.startTrip()
        vt.advance(by: 1_000); rig.north += Double(Self.speed)
        rig.emit(rig.fix())
        XCTAssertNil(rig.recorder.lastOutcome)
        rig.drive(130)
        rig.tracker.end()
        guard case .saved(let saved) = rig.recorder.lastOutcome else { return XCTFail("not saved") }
        XCTAssertEqual(saved.id, rig.dao.trips[0].id)
    }

    func testATripThatNeverGotAFixIsntKept() {
        let rig = Rig(vt)
        rig.startTrip()
        vt.advance(by: 60_000)
        rig.tracker.end()
        XCTAssertTrue(rig.dao.trips.isEmpty)
    }

    func testResetDiscardsTheTripAndTheNextStartRecordsANewOne() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(10)
        rig.tracker.discard()
        XCTAssertTrue(rig.dao.trips.isEmpty)

        vt.advance(by: 1_000)
        rig.north += Double(Self.speed)
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end()
        let second = rig.dao.trips[0]
        XCTAssertEqual(rig.dao.trips.count, 1)
        XCTAssertEqual(second.startLat * metersPerDegreeLat, 220, accuracy: 1e-6)
        XCTAssertEqual(second.endLat * metersPerDegreeLat, 420, accuracy: 1e-6)
        XCTAssertEqual(second.distanceM, 200, accuracy: 1e-6)
    }

    func testRejectedFixesNeverReachThePath() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(5)
        vt.advance(by: 500); rig.emit(rig.fix(accuracy: 60, east: 500))
        vt.advance(by: 250); rig.emit(rig.fix(speed: 90, east: 300))
        vt.advance(by: 250)
        rig.north += Double(Self.speed)
        rig.emit(rig.fix())
        rig.drive(5)
        rig.tracker.end()
        let t = rig.dao.trips[0]
        for p in Polyline.decode(t.track) { XCTAssertEqual(p.longitude, 0, accuracy: 1e-9) }
        XCTAssertEqual(t.endLon, 0, accuracy: 1e-9)
    }

    func testACornerIsKeptInTheSimplifiedPath() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(10)
        var east = 0.0
        for _ in 0..<10 {
            vt.advance(by: 1_000)
            east += Double(Self.speed)
            rig.emit(rig.fix(east: east))
        }
        rig.tracker.end()
        let track = Polyline.decode(rig.dao.trips[0].track)
        XCTAssertEqual(track.count, 3)
        XCTAssertEqual(track[1].latitude * metersPerDegreeLat, 200, accuracy: 1.2)
        XCTAssertEqual(track[1].longitude * metersPerDegreeLat, 0, accuracy: 1.2)
        XCTAssertEqual(track[2].longitude * metersPerDegreeLat, 200, accuracy: 1.2)
    }

    func testAddressesMissedOfflineAreFilledInLater() {
        let rig = Rig(vt)
        rig.addresses.online = false
        rig.startTrip()
        rig.drive(10)
        rig.tracker.end()
        XCTAssertNil(rig.dao.trips[0].startAddress)

        rig.addresses.online = true
        rig.recorder.fillMissingAddresses()
        XCTAssertEqual(rig.dao.trips[0].startAddress, "N 0 m")
        XCTAssertEqual(rig.dao.trips[0].endAddress, "N 200 m")

        let asked = rig.addresses.asked.count
        rig.recorder.fillMissingAddresses()
        XCTAssertEqual(rig.addresses.asked.count, asked)
    }

    func testAPausedTripEndedLaterIsSavedWithThePauseExcluded() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(10)
        rig.tracker.pause()
        vt.advance(by: 60_000)
        rig.tracker.end()
        let t = rig.dao.trips[0]
        XCTAssertEqual(t.durationMs, 10_000)
        XCTAssertEqual(t.endedAtMs, Self.wallBase + 10_000)
        XCTAssertGreaterThan(t.averageSpeedMps, 19)
    }

    // MARK: 11b: checkpoints and recovery

    func testARunningTripIsCheckpointedAboutOnceAMinuteWithOnlyTheNewFixesAppended() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(59)
        XCTAssertNil(rig.checkpoints.trip)
        rig.drive(5)
        let first = rig.checkpoints.trip!
        XCTAssertEqual(rig.checkpoints.fixes.count, Set(rig.checkpoints.fixes.map(\.timestampNanos)).count)
        XCTAssertGreaterThan(first.distanceM, 1_000)
        rig.drive(61)
        XCTAssertEqual(rig.checkpoints.writes, 2)
        XCTAssertEqual(rig.checkpoints.fixes.sorted { $0.timestampNanos < $1.timestampNanos }, rig.checkpoints.fixes)
        rig.tracker.pause()
        XCTAssertTrue(rig.checkpoints.trip!.paused)
        rig.tracker.end()
        XCTAssertNil(rig.checkpoints.trip)
        XCTAssertEqual(rig.dao.trips.count, 1)
    }

    func testADiscardedTripClearsItsCheckpoint() {
        let rig = Rig(vt)
        rig.startTrip()
        rig.drive(70)
        XCTAssertNotNil(rig.checkpoints.trip)
        rig.tracker.discard()
        XCTAssertNil(rig.checkpoints.trip)
        XCTAssertTrue(rig.dao.trips.isEmpty)
    }

    func testATripKilledMidRideResumesWithinTenMinutesJoiningTheGapInAStraightLine() {
        let first = Rig(vt)
        first.startTrip()
        first.drive(70)
        let saved = first.checkpoints.trip!
        let gapStart = first.checkpoints.fixes.last!.latitude * metersPerDegreeLat
        first.kill()

        vt.advance(by: 3 * 60_000)
        let second = Rig(vt, after: first)
        second.north = first.north + 2_000
        XCTAssertEqual(second.recorder.recover(), .resumed)
        XCTAssertEqual(second.tracker.state, .running)
        XCTAssertEqual(second.tracker.stats.distanceM, saved.distanceM, accuracy: 1e-6)
        XCTAssertEqual(second.recorder.recover(), .none)

        second.emit(second.fix())
        let joined = second.tracker.stats.distanceM
        XCTAssertEqual(joined, saved.distanceM + (second.north - gapStart), accuracy: 1)
        second.drive(10)
        second.tracker.end()
        let t = second.dao.trips.single!
        XCTAssertEqual(t.startedAtMs, Self.wallBase)
        XCTAssertEqual(t.distanceM, joined + 10 * Double(Self.speed), accuracy: 1)
        XCTAssertEqual(t.startLat, 0, accuracy: 1e-9)
        XCTAssertEqual(t.endLat * metersPerDegreeLat, second.north, accuracy: 1e-6)
        XCTAssertNil(second.checkpoints.trip)
    }

    func testATripLostLongerAgoIsSavedAsEndedAtItsLastCheckpoint() {
        let first = Rig(vt)
        vt.advance(by: 1_000)
        first.startTrip()
        first.drive(70)
        let saved = first.checkpoints.trip!
        let lastFix = first.checkpoints.fixes.last!
        first.kill()

        vt.advance(by: TripRecorder.resumeWithinMs)
        let second = Rig(vt, after: first)
        XCTAssertEqual(second.recorder.recover(), .savedEnded)
        XCTAssertEqual(second.tracker.state, .idle)
        let t = second.dao.trips.single!
        XCTAssertEqual(t.startedAtMs, Self.wallBase + 1_000)
        XCTAssertEqual(t.endedAtMs, Self.wallBase + lastFix.timestampNanos / 1_000_000)
        XCTAssertEqual(t.distanceM, saved.distanceM, accuracy: 1e-6)
        XCTAssertNil(second.checkpoints.trip)
        XCTAssertNil(second.recorder.lastOutcome)
    }

    func testNoCheckpointMeansNothingToRecover() {
        let rig = Rig(vt)
        XCTAssertEqual(rig.recorder.recover(), .none)
        XCTAssertEqual(rig.tracker.state, .idle)
    }
}

extension Array {
    /// The only element, or nil when there are none or several.
    var single: Element? { count == 1 ? first : nil }
}
