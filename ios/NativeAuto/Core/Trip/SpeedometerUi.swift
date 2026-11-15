import Foundation

/// Display unit only. All maths stays in SI; conversion happens at display time. Port of `settings/SpeedUnit.kt`.
enum SpeedUnit: String, CaseIterable {
    case kmh = "KMH", mph = "MPH"

    var speedLabel: String { self == .kmh ? "km/h" : "mph" }
    var distanceLabel: String { self == .kmh ? "km" : "mi" }
    /// Display units per metre-per-second.
    var perMps: Float { self == .kmh ? 3.6 : 2.2369363 }
    var metersPerDistanceUnit: Double { self == .kmh ? 1_000 : 1_609.344 }
}

/// The trip figures a rider can show or hide (9c). Order is display order; `key` is what is stored.
enum TelemetryStat: String, CaseIterable {
    case distance, time, average, max, movingAvg, movingTime, stoppedTime

    var key: String {
        switch self {
        case .distance: "distance"; case .time: "time"; case .average: "avg"; case .max: "max"
        case .movingAvg: "moving_avg"; case .movingTime: "moving_time"; case .stoppedTime: "stopped_time"
        }
    }

    var label: String {
        switch self {
        case .distance: "Distance"; case .time: "Trip time"; case .average: "Average"; case .max: "Max speed"
        case .movingAvg: "Moving avg"; case .movingTime: "Moving time"; case .stoppedTime: "Stopped time"
        }
    }

    static let defaultHidden: Set<TelemetryStat> = [.movingTime, .stoppedTime]
    static func fromKeys(_ keys: Set<String>) -> Set<TelemetryStat> { Set(allCases.filter { keys.contains($0.key) }) }
}

/// Display-ready speedometer values, already converted to `unit`. Port of `SpeedometerUiState`.
/// Durations are whole seconds.
struct SpeedometerUiState: Equatable {
    var speed: Int?            // nil → "--"
    var maxSpeed: Int
    var avgSpeed: Int
    var movingAvgSpeed: Int
    var elapsedS: Int64
    var distance: Double       // km or mi
    var unit: SpeedUnit
    var gps: GpsStatus
    var tripState: TripState
    var autoPaused: Bool = false
    var movingTimeS: Int64 = 0
    var stoppedTimeS: Int64 = 0

    var isTracking: Bool { tripState == .running }

    static let empty = SpeedometerUiState(speed: nil, maxSpeed: 0, avgSpeed: 0, movingAvgSpeed: 0, elapsedS: 0, distance: 0,
                                          unit: .kmh, gps: .searching, tripState: .idle)

    /// The GPS tile: the trip's state when it isn't running, since "Lost" would mislead.
    var statusLabel: String {
        switch tripState {
        case .paused: "Paused"
        case .ended: "Ended"
        case .idle, .running: gps.label
        }
    }
}

extension GpsStatus: CaseIterable {
    static var allCases: [GpsStatus] { [.searching, .fix, .stale, .disabled, .permissionDenied] }

    /// Six characters or fewer: longer words clip in the narrow tiles.
    var label: String {
        switch self {
        case .searching: "No fix"
        case .fix: "Locked"
        case .stale: "Lost"
        case .disabled: "Off"
        case .permissionDenied: "Denied"
        }
    }
}

enum SpeedometerConfig {
    /// A fix older than this means the GPS has gone quiet: show "--".
    static let staleAfterNanos: Int64 = 3_000_000_000
}

private func toUnit(_ v: Float, _ unit: SpeedUnit) -> Int { Int((v * unit.perMps).rounded()) }

extension TripStats {
    /// The one SI → display conversion. "Can't track" states outrank the stale rule. With no trip running the hero
    /// shows the live fix (the map's GPS read), the figures stay frozen.
    func toUiState(nowNanos: Int64, unit: SpeedUnit, tripState: TripState, locationGranted: Bool = true,
                   liveFix: SpeedSample? = nil) -> SpeedometerUiState {
        let last = lastSample
        let stale = last.map { nowNanos - $0.timestampNanos > SpeedometerConfig.staleAfterNanos } ?? false
        let liveFresh = liveFix.map { nowNanos - $0.timestampNanos <= SpeedometerConfig.staleAfterNanos } ?? false
        let gps: GpsStatus
        if !locationGranted { gps = .permissionDenied }
        else if tripState != .running {
            if liveFix == nil { gps = gpsStatus == .disabled ? gpsStatus : .searching }
            else { gps = liveFresh ? .fix : .stale }
        } else if gpsStatus == .disabled || gpsStatus == .permissionDenied { gps = gpsStatus }
        else if stale { gps = .stale }
        else { gps = gpsStatus }
        let blind = gps == .disabled || gps == .permissionDenied
        let speedMps: Float?
        if tripState == .running {
            speedMps = (last == nil || stale) ? nil : currentSpeedMps
        } else if let f = liveFix, liveFresh, f.hasSpeed {
            speedMps = f.speedMps < TripReducer.stationaryMps ? 0 : f.speedMps
        } else {
            speedMps = nil
        }
        let elapsed = elapsedNanos(now: nowNanos)
        return SpeedometerUiState(
            speed: blind ? nil : speedMps.map { toUnit($0, unit) },
            maxSpeed: toUnit(maxSpeedMps, unit),
            avgSpeed: toUnit(averageSpeedMps(now: nowNanos), unit),
            movingAvgSpeed: toUnit(movingAverageMps, unit),
            elapsedS: max(0, elapsed) / 1_000_000_000,
            distance: distanceM / unit.metersPerDistanceUnit,
            unit: unit,
            gps: gps,
            tripState: tripState,
            autoPaused: tripState == .paused && autoPaused,
            movingTimeS: movingNanos / 1_000_000_000,
            stoppedTimeS: max(0, elapsed - movingNanos) / 1_000_000_000
        )
    }
}

/// One figure on a card: label, value, unit.
struct Stat: Equatable, Identifiable {
    var tag: String
    var label: String
    var value: String
    var unit: String?
    var id: String { tag }
}

/// `H:MM:SS` past an hour, otherwise `M:SS`.
func formatElapsed(_ seconds: Int64) -> String {
    let total = max(0, seconds)
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

extension SpeedometerUiState {
    /// The speed face's grid.
    func telemetry() -> [Stat] {
        [Stat(tag: "stat_max", label: "MAX", value: String(maxSpeed), unit: unit.speedLabel),
         Stat(tag: "stat_avg", label: "AVERAGE", value: String(avgSpeed), unit: unit.speedLabel),
         Stat(tag: "stat_moving_avg", label: "MOVING AVG", value: String(movingAvgSpeed), unit: unit.speedLabel),
         Stat(tag: "stat_elapsed", label: "ELAPSED", value: formatElapsed(elapsedS), unit: nil),
         Stat(tag: "stat_distance", label: "DISTANCE", value: String(format: "%.1f", distance), unit: unit.distanceLabel),
         Stat(tag: "stat_gps", label: "GPS", value: statusLabel, unit: nil)]
    }

    /// The log face / summary figures the rider chose (9c). `progress` scales them for a count-up.
    func tripStats(visible: Set<TelemetryStat>, prefix: String, progress: Double = 1) -> [Stat] {
        func speed(_ v: Int) -> String { String(Int((Double(v) * progress).rounded())) }
        func time(_ s: Int64) -> String { formatElapsed(Int64((Double(s) * progress).rounded())) }
        return TelemetryStat.allCases.filter { visible.contains($0) }.map { s in
            let (value, u): (String, String?) = switch s {
            case .distance: (String(format: "%.1f", distance * progress), unit.distanceLabel)
            case .time: (time(elapsedS), nil)
            case .average: (speed(avgSpeed), unit.speedLabel)
            case .max: (speed(maxSpeed), unit.speedLabel)
            case .movingAvg: (speed(movingAvgSpeed), unit.speedLabel)
            case .movingTime: (time(movingTimeS), nil)
            case .stoppedTime: (time(stoppedTimeS), nil)
            }
            return Stat(tag: "\(prefix)stat_\(s.key)", label: s.label.uppercased(), value: value, unit: u)
        }
    }
}
