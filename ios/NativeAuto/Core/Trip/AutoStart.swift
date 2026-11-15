import Foundation

/// Port of Android `trip/AutoStart.kt` (13b). What the app does by itself when it is open and the
/// bike starts moving. Never in the background.
enum AutoStartMode { case off, trip }

enum AutoStartConfig {
    /// The speed that counts as "riding". Android is 15 km/h; the next update lowers it to ~10.
    static let kmh: Float = 15
    static var mps: Float { kmh / 3.6 }
    static let holdChoicesS = [20, 30, 45, 60]
    static let defaultHoldS = 30
    /// A gap in the fixes this long breaks the hold: the proof has to be continuous.
    static let maxGapNanos: Int64 = 10_000_000_000
}

struct AutoStartState: Equatable {
    var heldSinceNanos: Int64? = nil
    var lastFixNanos: Int64? = nil
    var stats = TripStats()
}

/// Folds one fix into the hold; `triggered` once the speed has been held for `holdNanos`.
/// Fixes without a speed or too inaccurate are skipped; a slower fix or a long gap starts over.
func stepAutoStart(_ state: AutoStartState, _ fix: SpeedSample, holdNanos: Int64) -> (state: AutoStartState, triggered: Bool) {
    if !fix.hasSpeed || fix.horizontalAccuracyM > TripReducer.maxAccuracyM { return (state, false) }
    let speedOk = fix.speedAccuracyMps.map { $0 < TripReducer.maxSpeedAccuracyMps } ?? true
    if !speedOk { return (state, false) }
    let gap = state.lastFixNanos.map { fix.timestampNanos - $0 } ?? 0
    let base = gap > AutoStartConfig.maxGapNanos ? AutoStartState() : state
    if fix.speedMps < AutoStartConfig.mps {
        return (AutoStartState(lastFixNanos: fix.timestampNanos), false)
    }
    let since = base.heldSinceNanos ?? fix.timestampNanos
    let next = AutoStartState(heldSinceNanos: since, lastFixNanos: fix.timestampNanos,
                              stats: TripReducer.reduce(base.stats, fix))
    return (next, fix.timestampNanos - since >= holdNanos)
}

/// Only from a clean state: opened with no trip, and none ended since.
func autoStartAllowed(_ mode: AutoStartMode, _ tripState: TripState, endedThisSession: Bool, cleanAtOpen: Bool) -> Bool {
    mode != .off && cleanAtOpen && !endedThisSession && tripState == .idle
}
