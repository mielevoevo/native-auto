import Foundation

// Port of Android `trip/TripState.kt`, `SpeedSample.kt`, `Geo.kt`, `TripStats.kt`.
// Pure Swift (Foundation only): no CoreLocation, no UI. Keep it in step with the Kotlin.

/// Where the trip is in its life cycle.
enum TripState { case idle, running, paused, ended }

enum GpsStatus { case searching, fix, stale, disabled, permissionDenied }

/// One GPS fix, in SI units. `timestampNanos` is a monotonic-ish clock in nanoseconds.
struct SpeedSample: Equatable {
    var timestampNanos: Int64
    var speedMps: Float
    var speedAccuracyMps: Float?
    var horizontalAccuracyM: Float
    var latitude: Double
    var longitude: Double
    /// Course over ground, degrees clockwise from north; nil when the fix has none. Map only.
    var bearingDeg: Float? = nil
    /// False for a position-only fix: `speedMps` is then meaningless and the trip ignores it.
    var hasSpeed: Bool = true
}

/// Mean Earth radius (IUGG), metres.
let earthRadiusM = 6_371_008.8

/// Metres per degree of latitude on the mean sphere.
let metersPerDegreeLat = earthRadiusM * Double.pi / 180.0

/// Great-circle distance in metres (haversine).
func haversineMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
    let rad = Double.pi / 180
    let phi1 = lat1 * rad, phi2 = lat2 * rad
    let dPhi = (lat2 - lat1) * rad, dLambda = (lon2 - lon1) * rad
    let a = pow(sin(dPhi / 2), 2) + cos(phi1) * cos(phi2) * pow(sin(dLambda / 2), 2)
    return 2 * earthRadiusM * asin(min(1.0, a.squareRoot()))
}

func haversineMeters(_ a: SpeedSample, _ b: SpeedSample) -> Double {
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}

/// Accumulated trip state, all SI.
struct TripStats: Equatable {
    var startedAtNanos: Int64? = nil
    var lastSample: SpeedSample? = nil
    var currentSpeedMps: Float = 0
    var maxSpeedMps: Float = 0
    var distanceM: Double = 0
    var movingNanos: Int64 = 0
    var gpsStatus: GpsStatus = .searching
    /// Total time spent paused so far; excluded from elapsed (and so from the average).
    var pausedNanos: Int64 = 0
    /// Set while paused: the trip clock is frozen at this instant.
    var pausedAtNanos: Int64? = nil
    /// Set once the trip has ended: every figure is frozen from here on.
    var endedAtNanos: Int64? = nil
    /// The last accepted fix above walking pace; auto-pause counts from here.
    var lastMovingAtNanos: Int64? = nil
    /// True while the trip was paused by auto-pause rather than by hand.
    var autoPaused: Bool = false

    /// Trip time: from the start to now (or to the pause / end), minus time spent paused.
    func elapsedNanos(now: Int64) -> Int64 {
        guard let start = startedAtNanos else { return 0 }
        let until = endedAtNanos ?? pausedAtNanos ?? now
        return until - start - pausedNanos
    }

    /// Distance ÷ elapsed time: never an average of speed samples.
    func averageSpeedMps(now: Int64) -> Float {
        let sec = Double(elapsedNanos(now: now)) / 1e9
        return sec < 1 ? 0 : Float(distanceM / sec)
    }

    /// Distance ÷ time spent moving, so stops don't drag the figure down.
    var movingAverageMps: Float {
        let sec = Double(movingNanos) / 1e9
        return sec < 1 ? 0 : Float(distanceM / sec)
    }
}
