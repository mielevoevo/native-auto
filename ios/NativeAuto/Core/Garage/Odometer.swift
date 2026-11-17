import Foundation

// Port of Android `garage/Odometer.kt` (11a). The rider enters the bike's real reading once; after that the estimate
// is the last reading plus the GPS distance of the bike's trips since, times `odoFactor`. Each correction teaches it.

/// The trip in progress, for the active bike's live odometer.
struct LiveTrip: Equatable {
    var startedAtMs: Int64
    var distanceM: Double
}

/// Raw GPS metres since `reading`: `savedM` from the saved trips plus the `live` trip, which counts only past the
/// reading's offset if it was already running then.
func gpsMetersSince(_ reading: OdometerReading, savedM: Double, live: LiveTrip?) -> Double {
    let liveM: Double
    if let live {
        liveM = live.startedAtMs < reading.timeMs ? max(live.distanceM - reading.tripOffsetM, 0) : live.distanceM
    } else {
        liveM = 0
    }
    return savedM + liveM
}

/// The odometer now, or nil before the first reading.
func estimateOdometerKm(_ reading: OdometerReading?, factor: Double, gpsMetersSince: Double) -> Double? {
    reading.map { $0.km + factor * gpsMetersSince / 1000 }
}

/// A correction teaches the factor only over this much GPS distance (short spans are noise).
let minLearnKm = 30.0
/// Outside this band: a typo, a forgotten reading or an untracked ride, not GPS drift (11b narrowed it from 0.8–1.2).
let factorRange = 0.9...1.1
/// How far each correction moves the factor.
let learnWeight = 0.5

/// The factor after a correction to `newKm`, `gpsKm` GPS km after the reading of `prevKm`.
func learnFactor(_ old: Double, prevKm: Double, newKm: Double, gpsKm: Double) -> Double {
    if gpsKm < minLearnKm { return old }
    let measured = (newKm - prevKm) / gpsKm
    if !factorRange.contains(measured) { return old }
    return min(max(old + learnWeight * (measured - old), factorRange.lowerBound), factorRange.upperBound)
}

/// What an entered reading becomes.
enum ReadingDecision: Equatable {
    case store(ReadingKind, awayKm: Double = 0)
    /// The away check found the odometer unchanged: nobody rode it, nothing to store.
    case unchanged
}

/// Below this, an away reading is "the same" (the rider typed the prefilled value back).
let awayMinKm = 1.0

/// `entered` against the current `estimate` (nil: no reading yet). From the away prompt, a higher reading is away km;
/// a lower one is just a correction.
func decideReading(_ entered: Double, estimate: Double?, away: Bool) -> ReadingDecision {
    guard let estimate else { return .store(.set) }
    if away && abs(entered - estimate) < awayMinKm { return .unchanged }
    if away && entered > estimate { return .store(.away, awayKm: entered - estimate) }
    return .store(.correction)
}

/// A bike unused this long may have been ridden by someone else: ask for its odometer.
let awayAfterMs: Int64 = 3 * 24 * 60 * 60 * 1000

func needsAwayCheck(lastUsedAtMs: Int64, nowMs: Int64) -> Bool { nowMs - lastUsedAtMs >= awayAfterMs }

/// Parses a typed number, English or Indonesian style: "6.6", "6,6", "12,345", "12.345,6". With `thousands`
/// (odometers), one separator followed by exactly three digits groups thousands. Nil unless a non-negative number.
func parseNumber(_ text: String, thousands: Bool = false) -> Double? {
    let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "")
    if t.isEmpty { return nil }
    let chars = Array(t)
    let lastDot = chars.lastIndex(of: ".") ?? -1
    let lastComma = chars.lastIndex(of: ",") ?? -1
    func grouped(_ sep: Character, _ last: Int) -> Bool { chars.filter { $0 == sep }.count > 1 || (thousands && chars.count - last - 1 == 3) }
    let normal: String
    if lastDot >= 0 && lastComma >= 0 {
        normal = lastDot > lastComma ? t.replacingOccurrences(of: ",", with: "")
            : t.replacingOccurrences(of: ".", with: "").replacingOccurrences(of: ",", with: ".")
    } else if lastComma >= 0 {
        normal = grouped(",", lastComma) ? t.replacingOccurrences(of: ",", with: "") : t.replacingOccurrences(of: ",", with: ".")
    } else if lastDot >= 0 {
        normal = grouped(".", lastDot) ? t.replacingOccurrences(of: ".", with: "") : t
    } else {
        normal = t
    }
    // Plain decimal digits only (Kotlin's toDoubleOrNull would also take "1e3", which no rider types).
    guard normal.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }), let v = Double(normal), v.isFinite, v >= 0 else { return nil }
    return v
}

/// "12,345" (English grouping).
func groupedInt(_ v: Int64) -> String {
    let f = NumberFormatter()
    f.locale = Locale(identifier: "en_US")
    f.numberStyle = .decimal
    return f.string(from: NSNumber(value: v)) ?? String(v)
}

/// "12,345 km".
func formatOdometer(_ km: Double) -> String { "\(groupedInt(Int64(km.rounded(.toNearestOrAwayFromZero)))) km" }
