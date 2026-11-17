import Foundation

// Port of Android `history/Database.kt` (the records, not Room), `PeriodTotals.kt`, `ui/history/DayGroups.kt` and
// `shortAddress` from `AddressResolver.kt`. Pure Swift: the app stores these through SwiftData (`App/Stores/`), the
// tests through in-memory DAOs.

/// Wall-clock time in milliseconds since 1970. Display and storage only: trip maths runs on `MonotonicClock`.
protocol WallClock: AnyObject {
    func nowMs() -> Int64
}

final class SystemWallClock: WallClock {
    func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
}

/// One finished trip. Times are wall clock; average speed is derived (distance ÷ duration), not stored.
struct TripRecord: Equatable, Identifiable {
    var id: Int64 = 0
    var startedAtMs: Int64
    var endedAtMs: Int64
    /// Trip time excluding pauses, like the speed card's elapsed.
    var durationMs: Int64
    var movingMs: Int64
    var distanceM: Double
    var maxSpeedMps: Float
    var startLat: Double
    var startLon: Double
    var endLat: Double
    var endLon: Double
    /// Nil until reverse geocoding succeeds (retried when the history opens).
    var startAddress: String? = nil
    var endAddress: String? = nil
    /// The path, simplified, as a Google encoded polyline (precision 5). For drawing only.
    var track: String
    /// Time on the running trip below walking pace; pauses not included (9c).
    var stoppedMs: Int64 = 0
    /// The bike that was active when the trip was saved (11a).
    var bikeId: String? = nil

    var averageSpeedMps: Float { durationMs < 1_000 ? 0 : Float(distanceM / (Double(durationMs) / 1000)) }
    var movingAverageMps: Float { movingMs < 1_000 ? 0 : Float(distanceM / (Double(movingMs) / 1000)) }
}

/// One accepted fix of a saved trip (9c, the full track for export).
struct TrackPointRecord: Equatable {
    var tripId: Int64 = 0
    /// Wall clock, derived from the fix's monotonic time when the trip is saved.
    var timeMs: Int64
    var latitude: Double
    var longitude: Double
    var speedMps: Float
    var bearingDeg: Float?
    var accuracyM: Float
}

/// The history's storage. Synchronous: SwiftData runs on the main actor, and the tests use an array.
protocol TripDao: AnyObject {
    /// Newest first.
    func allTrips() -> [TripRecord]
    func trip(_ id: Int64) -> TripRecord?
    /// The trip and its full track together. Returns the trip's id.
    @discardableResult
    func insertWithTrack(_ trip: TripRecord, _ points: [TrackPointRecord]) -> Int64
    /// Oldest first.
    func points(_ tripId: Int64) -> [TrackPointRecord]
    func setAddresses(_ id: Int64, start: String?, end: String?)
    func delete(_ id: Int64)
}

extension TripDao {
    func missingAddresses() -> [TripRecord] { allTrips().filter { $0.startAddress == nil || $0.endAddress == nil } }
}

// MARK: - Period totals (12b, R2)

/// The log face's total: "Today 42.3 km", "This week …", "This month …".
enum TotalPeriod: String, CaseIterable, Codable {
    case day = "DAY", week = "WEEK", month = "MONTH"
    var label: String { switch self { case .day: "Day"; case .week: "Week"; case .month: "Month" } }
    var lead: String { switch self { case .day: "Today"; case .week: "This week"; case .month: "This month" } }
}

/// A Gregorian calendar in `zone` (weeks from Monday, as Android's `DayOfWeek.MONDAY`).
func calendar(_ zone: TimeZone) -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = zone
    c.firstWeekday = 2
    c.locale = Locale(identifier: "en_US_POSIX")
    return c
}

func dateOf(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
func msOf(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

/// Midnight starting the period that holds `nowMs`.
func periodStartMs(_ period: TotalPeriod, nowMs: Int64, zone: TimeZone) -> Int64 {
    let cal = calendar(zone)
    let today = cal.startOfDay(for: dateOf(nowMs))
    let start: Date
    switch period {
    case .day: start = today
    case .week:
        // Monday = 2 in Gregorian weekday numbering; go back to it (or stay).
        let back = (cal.component(.weekday, from: today) + 5) % 7
        start = cal.date(byAdding: .day, value: -back, to: today)!
    case .month: start = cal.date(from: cal.dateComponents([.year, .month], from: today))!
    }
    return msOf(start)
}

/// Metres of the trips that started in `period` up to `nowMs`. A trip counts for the period it started in.
func periodDistanceM(_ trips: [TripRecord], _ period: TotalPeriod, nowMs: Int64, zone: TimeZone) -> Double {
    let from = periodStartMs(period, nowMs: nowMs, zone: zone)
    return trips.filter { $0.startedAtMs >= from && $0.startedAtMs <= nowMs }.reduce(0) { $0 + $1.distanceM }
}

// MARK: - Day groups (9d)

/// A local calendar day.
struct LocalDay: Hashable, Comparable {
    var year: Int, month: Int, day: Int
    static func < (a: LocalDay, b: LocalDay) -> Bool { (a.year, a.month, a.day) < (b.year, b.month, b.day) }
}

func localDay(_ ms: Int64, _ zone: TimeZone) -> LocalDay {
    let c = calendar(zone).dateComponents([.year, .month, .day], from: dateOf(ms))
    return LocalDay(year: c.year!, month: c.month!, day: c.day!)
}

/// One day of history: its header and the day's total distance.
struct DayGroup: Equatable {
    var day: LocalDay
    var label: String
    var totalDistanceM: Double
    var trips: [TripRecord]
}

/// Trips grouped by the local day they started (newest first): "Today", "Yesterday", then "Mon 3 Nov" (with the year
/// once it isn't this year's).
func groupByDay(_ trips: [TripRecord], nowMs: Int64, zone: TimeZone = .current) -> [DayGroup] {
    let today = localDay(nowMs, zone)
    let groups = Dictionary(grouping: trips) { localDay($0.startedAtMs, zone) }
    return groups.keys.sorted(by: >).map { day in
        let list = groups[day]!
        return DayGroup(day: day, label: dayLabel(day, today: today, zone: zone), totalDistanceM: list.reduce(0) { $0 + $1.distanceM },
                        trips: list.sorted { $0.startedAtMs > $1.startedAtMs })
    }
}

func dayLabel(_ day: LocalDay, today: LocalDay, zone: TimeZone) -> String {
    let cal = calendar(zone)
    let date = cal.date(from: DateComponents(year: day.year, month: day.month, day: day.day, hour: 12))!
    let todayDate = cal.date(from: DateComponents(year: today.year, month: today.month, day: today.day, hour: 12))!
    if day == today { return "Today" }
    if localDay(msOf(cal.date(byAdding: .day, value: -1, to: todayDate)!), zone) == day { return "Yesterday" }
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = zone
    f.dateFormat = day.year == today.year ? "EEE d MMM" : "EEE d MMM yyyy"
    return f.string(from: date)
}

// MARK: - Addresses

/// "Street, Area" when known. Feature names that are just a house number or a plus code ("QX2C+9F") say nothing to a
/// rider and are skipped; then the first two parts of the full address line stand in.
func shortAddress(street: String?, area: String?, line: String?) -> String? {
    let ws = CharacterSet.whitespacesAndNewlines
    let s = street?.trimmingCharacters(in: ws).nonEmpty.flatMap { t in
        (t.contains("+") || t.allSatisfy { $0.isNumber || $0 == " " }) ? nil : t
    }
    let a = area?.trimmingCharacters(in: ws).nonEmpty.flatMap { $0 == s ? nil : $0 }
    if let s { return [s, a].compactMap { $0 }.joined(separator: ", ") }
    let fromLine = line?.split(separator: ",").map { $0.trimmingCharacters(in: ws) }.filter { !$0.isEmpty && !$0.contains("+") }.prefix(2)
    if let fromLine, !fromLine.isEmpty { return fromLine.joined(separator: ", ") }
    return a
}

extension String {
    /// Nil for an empty string.
    var nonEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Formatting (history, summary)

/// "12.3 km" in the speed unit's distance.
func formatDistance(_ meters: Double, _ unit: SpeedUnit) -> String {
    String(format: "%.1f %@", meters / unit.metersPerDistanceUnit, unit.distanceLabel)
}

func formatSpeed(_ mps: Float, _ unit: SpeedUnit) -> String { "\(Int((mps * unit.perMps).rounded())) \(unit.speedLabel)" }

/// "42 min", "1 h 05 min"; under a minute, seconds.
func formatDuration(_ ms: Int64) -> String {
    let s = max(0, ms) / 1000
    let minutes = s / 60
    if minutes < 1 { return "\(s) s" }
    if minutes < 60 { return "\(minutes) min" }
    return String(format: "%d h %02d min", minutes / 60, minutes % 60)
}

// MARK: - Track storage

/// The full track as one compact blob (36 bytes a point, little-endian): time ms, lat, lon, speed, bearing (NaN =
/// none), accuracy. One row per trip instead of ~10 000 (SwiftData is slow with that many objects).
enum TrackCodec {
    static let pointSize = 36

    static func encode(_ points: [TrackPointRecord]) -> Data {
        var d = Data(capacity: points.count * pointSize)
        func put<T>(_ v: T) { withUnsafeBytes(of: v) { d.append(contentsOf: $0) } }
        for p in points {
            put(p.timeMs.littleEndian)
            put(p.latitude.bitPattern.littleEndian)
            put(p.longitude.bitPattern.littleEndian)
            put(p.speedMps.bitPattern.littleEndian)
            put((p.bearingDeg ?? .nan).bitPattern.littleEndian)
            put(p.accuracyM.bitPattern.littleEndian)
        }
        return d
    }

    static func decode(_ data: Data, tripId: Int64) -> [TrackPointRecord] {
        let b = [UInt8](data)
        func u64(_ i: Int) -> UInt64 { (0..<8).reduce(UInt64(0)) { $0 | UInt64(b[i + $1]) << (8 * UInt64($1)) } }
        func u32(_ i: Int) -> UInt32 { (0..<4).reduce(UInt32(0)) { $0 | UInt32(b[i + $1]) << (8 * UInt32($1)) } }
        return stride(from: 0, to: b.count - pointSize + 1, by: pointSize).map { i in
            let bearing = Float(bitPattern: u32(i + 28))
            return TrackPointRecord(tripId: tripId, timeMs: Int64(bitPattern: u64(i)), latitude: Double(bitPattern: u64(i + 8)),
                                    longitude: Double(bitPattern: u64(i + 16)), speedMps: Float(bitPattern: u32(i + 24)),
                                    bearingDeg: bearing.isNaN ? nil : bearing, accuracyM: Float(bitPattern: u32(i + 32)))
        }
    }
}
