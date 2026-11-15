import Foundation

/// Port of Android `location/FakeSpeedSampleSource.kt`: the scripted drive (~90 s a loop) for the simulator and
/// tests. Stationary jitter → 0 to 100 km/h → cruise with two bad readings in a row → 8 s tunnel → two
/// poor-accuracy fixes → brake → 10 s stop.
enum FakeDrive {
    static let fixIntervalMs: Int64 = 1_000
    static let startLat = 51.4545
    static let startLon = -2.5879
    static let cruiseMps: Float = 27.78
    static let spikeMps: Float = 55
    static let spike2Mps: Float = 56.1
    static let tunnelSeconds = 8

    enum Step {
        case fix(reported: Float, trueSpeed: Float, hAcc: Float = 4, speedAcc: Float? = 0.4)
        case gap(seconds: Int)

        static func fix(_ v: Float, trueSpeed: Float? = nil, hAcc: Float = 4) -> Step {
            .fix(reported: v, trueSpeed: trueSpeed ?? v, hAcc: hAcc, speedAcc: 0.4)
        }
    }

    static let script: [Step] = {
        var s: [Step] = []
        for v: Float in [0.3, 0.6, 0.2, 0.5, 0.4] { s.append(.fix(v, trueSpeed: 0)) }
        for i in 1...15 { s.append(.fix(cruiseMps * Float(i) / 15)) }
        for _ in 0..<15 { s.append(.fix(cruiseMps)) }
        s.append(.fix(spikeMps, trueSpeed: cruiseMps))
        s.append(.fix(spike2Mps, trueSpeed: cruiseMps))
        for _ in 0..<10 { s.append(.fix(cruiseMps)) }
        s.append(.gap(seconds: tunnelSeconds))
        for _ in 0..<8 { s.append(.fix(cruiseMps)) }
        for _ in 0..<2 { s.append(.fix(cruiseMps, hAcc: 40)) }
        for _ in 0..<5 { s.append(.fix(cruiseMps)) }
        for i in stride(from: 9, through: 0, by: -1) { s.append(.fix(cruiseMps * Float(i) / 10)) }
        for v: Float in [0.1, 0.4, 0.0, 0.3, 0.5, 0.2, 0.0, 0.6, 0.1, 0.3] { s.append(.fix(v, trueSpeed: 0)) }
        return s
    }()

    /// Seconds per loop, gaps included.
    static let loopSeconds: Int = script.reduce(0) { n, step in
        if case .gap(let s) = step { return n + s } else { return n + 1 }
    }
}

/// Plays `FakeDrive.script` at 1 Hz, forever, due north from the start point.
final class FakeSpeedSampleSource: SpeedSampleSource {
    private let clock: MonotonicClock
    private let scheduler: TaskScheduler
    var isLocationEnabled: Bool { true }

    init(clock: MonotonicClock, scheduler: TaskScheduler) {
        self.clock = clock
        self.scheduler = scheduler
    }

    private final class Run: Cancellable {
        var pending: Cancellable?
        var cancelled = false
        func cancel() { cancelled = true; pending?.cancel() }
    }

    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable {
        let run = Run()
        var latitude = FakeDrive.startLat
        var prevTrue: Float = 0
        var index = 0
        func next() {
            guard !run.cancelled else { return }
            let step = FakeDrive.script[index]
            index = (index + 1) % FakeDrive.script.count
            switch step {
            case let .fix(reported, trueSpeed, hAcc, speedAcc):
                run.pending = scheduler.schedule(afterMs: FakeDrive.fixIntervalMs) { [clock] in
                    guard !run.cancelled else { return }
                    latitude += Double(prevTrue + trueSpeed) / 2.0 * 1.0 / metersPerDegreeLat
                    prevTrue = trueSpeed
                    onEvent(.fix(SpeedSample(timestampNanos: clock.nowNanos(), speedMps: reported, speedAccuracyMps: speedAcc,
                                             horizontalAccuracyM: hAcc, latitude: latitude, longitude: FakeDrive.startLon,
                                             bearingDeg: 0)))
                    next()
                }
            case let .gap(seconds):
                run.pending = scheduler.schedule(afterMs: Int64(seconds) * 1_000) {
                    guard !run.cancelled else { return }
                    latitude += Double(prevTrue) * Double(seconds) / metersPerDegreeLat
                    next()
                }
            }
        }
        next()
        return run
    }
}
