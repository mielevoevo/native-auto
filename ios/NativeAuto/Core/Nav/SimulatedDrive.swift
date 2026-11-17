import Foundation

// Port of Android `nav/RouteSimulation.kt`. Debug "Simulate navigation": the location source drives along the planned
// route instead of reading the GPS, so turn-by-turn can be tried at a desk.

/// One step a second along `route` at `speedMps`, slowing to 45 % before bends over 60°; at the end it stands still
/// on the last point. A value type, so the test steps it directly.
struct SimulatedDrive {
    let route: [GeoPoint]
    let speedMps: Float
    private var i = 0          // segment start index
    private var along = 0.0    // metres into segment i
    private var bearing: Float

    init(route: [GeoPoint], speedMps: Float = 11) {
        self.route = route
        self.speedMps = speedMps
        bearing = route.count > 1 ? bearingDeg(route[0], route[1]) : 0
    }

    /// The next second's fix (position, speed, bearing). Nil for an empty route.
    mutating func step() -> (point: GeoPoint, speedMps: Float, bearingDeg: Float)? {
        guard !route.isEmpty else { return nil }
        var speed: Float = 0
        if i < route.count - 1 {
            let nextBearing = i + 2 < route.count ? bearingDeg(route[i + 1], route[i + 2]) : bearing
            speed = angleBetween(bearing, nextBearing) > 60 ? speedMps * 0.45 : speedMps
            var left = Double(speed)
            while left > 0, i < route.count - 1 {
                let segment = haversineMeters(route[i].latitude, route[i].longitude, route[i + 1].latitude, route[i + 1].longitude)
                let move = min(left, segment - along)
                along += move
                left -= move
                if along >= segment - 1e-6 { i += 1; along = 0 }
            }
            if i < route.count - 1 { bearing = bearingDeg(route[i], route[i + 1]) }
        }
        let p = i >= route.count - 1 ? route[route.count - 1] : interpolate(route[i], route[i + 1], along)
        return (p, speed, bearing)
    }
}

/// `SimulatedDrive` as a location source: a fix every second until cancelled.
final class SimulatedDriveSource: SpeedSampleSource {
    private let route: [GeoPoint]
    private let speedMps: Float
    private let clock: MonotonicClock
    private let scheduler: TaskScheduler
    var isLocationEnabled: Bool { true }

    init(route: [GeoPoint], clock: MonotonicClock, scheduler: TaskScheduler, speedMps: Float = 11) {
        self.route = route; self.clock = clock; self.scheduler = scheduler; self.speedMps = speedMps
    }

    private final class Run: Cancellable {
        var pending: Cancellable?
        var cancelled = false
        func cancel() { cancelled = true; pending?.cancel() }
    }

    func subscribe(_ onEvent: @escaping (SourceEvent) -> Void) -> Cancellable {
        let run = Run()
        var drive = SimulatedDrive(route: route, speedMps: speedMps)
        func next() {
            run.pending = scheduler.schedule(afterMs: 1_000) { [clock] in
                guard !run.cancelled, let s = drive.step() else { return }
                onEvent(.fix(SpeedSample(timestampNanos: clock.nowNanos(), speedMps: s.speedMps, speedAccuracyMps: 0.4,
                                         horizontalAccuracyM: 4, latitude: s.point.latitude, longitude: s.point.longitude,
                                         bearingDeg: s.bearingDeg)))
                next()
            }
        }
        if !route.isEmpty { next() }
        return run
    }
}

private func interpolate(_ a: GeoPoint, _ b: GeoPoint, _ metres: Double) -> GeoPoint {
    let total = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    if total < 1e-6 { return a }
    let t = metres / total
    return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
}

/// Compass bearing from `a` to `b`, degrees clockwise from north (flat-earth, fine over a route's short segments).
func bearingDeg(_ a: GeoPoint, _ b: GeoPoint) -> Float {
    let rad = Double.pi / 180
    let dLon = (b.longitude - a.longitude) * rad * cos((a.latitude + b.latitude) / 2 * rad)
    let dLat = (b.latitude - a.latitude) * rad
    return Float((atan2(dLon, dLat) / rad + 360).truncatingRemainder(dividingBy: 360))
}

private func angleBetween(_ a: Float, _ b: Float) -> Float {
    let d = ((b - a).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    return d > 180 ? 360 - d : d
}
