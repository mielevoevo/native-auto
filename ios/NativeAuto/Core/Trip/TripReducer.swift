import Foundation

/// Port of Android `trip/TripReducer.kt`: pure fold of GPS fixes into `TripStats`.
///
/// An implausible fix (speed change over `maxAccelMps2`) is ignored completely, like a poor-accuracy
/// fix. Because a rejected fix doesn't move `lastSample`, the next fix is judged against the last
/// good one over the longer interval, so the filter can't lock onto a bad value.
enum TripReducer {
    static let stationaryMps: Float = 0.8          // ~3 km/h; below = jitter → 0
    static let maxAccuracyM: Float = 25
    static let maxAccelMps2: Float = 12            // ~1.2 g
    static let maxGapNanos: Int64 = 5_000_000_000  // tunnel → straight-line distance
    static let maxSpeedAccuracyMps: Float = 1.5

    static func reduce(_ s: TripStats, _ fix: SpeedSample) -> TripStats {
        var out = s
        // Position-only fixes feed the map and navigation, never the trip.
        if !fix.hasSpeed || fix.horizontalAccuracyM > maxAccuracyM {
            out.gpsStatus = .searching
            return out
        }
        let speed: Float = fix.speedMps < stationaryMps ? 0 : fix.speedMps
        let accurate = fix.speedAccuracyMps.map { $0 < maxSpeedAccuracyMps } ?? true

        guard let prev = s.lastSample else {
            out.startedAtNanos = s.startedAtNanos ?? fix.timestampNanos
            // The first fix after a start or resume sets the max too.
            if accurate { out.maxSpeedMps = max(s.maxSpeedMps, speed) }
            out.lastSample = fix
            out.currentSpeedMps = speed
            out.gpsStatus = .fix
            if speed > 0 { out.lastMovingAtNanos = fix.timestampNanos }
            return out
        }

        let dtNanos = fix.timestampNanos - prev.timestampNanos
        if dtNanos <= 0 { return s }                 // duplicate or out-of-order fix
        let dtSec = Double(dtNanos) / 1e9

        // Spike: ignore the fix entirely.
        let plausible = Double(abs(speed - s.currentSpeedMps)) / dtSec <= Double(maxAccelMps2)
        if !plausible { return s }

        // Short gaps: trapezoid of the two speeds. Long gaps (tunnels): straight-line distance.
        let dDist = dtNanos > maxGapNanos
            ? haversineMeters(prev, fix)
            : Double(s.currentSpeedMps + speed) / 2.0 * dtSec

        out.lastSample = fix
        out.currentSpeedMps = speed
        if accurate { out.maxSpeedMps = max(s.maxSpeedMps, speed) }
        out.distanceM = s.distanceM + dDist
        out.movingNanos = s.movingNanos + (speed > 0 ? dtNanos : 0)
        out.gpsStatus = .fix
        if speed > 0 { out.lastMovingAtNanos = fix.timestampNanos }
        return out
    }
}
