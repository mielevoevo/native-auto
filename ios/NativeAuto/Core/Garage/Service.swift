import Foundation

// Port of Android `garage/Service.kt` (11c). Intervals are the rider's own. An item is due by km and/or months,
// whichever comes first, counted from when it was last done (or added). Date-only items (vehicle tax, insurance,
// licence) have a due date instead, moved on by their interval when marked done.

/// Names offered when adding an item: the rider sets the intervals.
let serviceNames = ["Engine oil", "Gear oil", "CVT belt", "CVT rollers", "Tyre pressure", "Front tyre", "Rear tyre",
                    "Brake pads", "Brake fluid", "Air filter", "Spark plug", "Coolant"]

/// Date-only reminders, each with its usual interval in months.
let dateNames: [(name: String, months: Int)] = [("Vehicle tax", 12), ("Insurance", 12), ("Licence", 60)]

enum DueLevel { case ok, soon, overdue, noInterval }

/// An item's state now. Negative values are overdue.
struct ServiceDue: Equatable {
    var item: ServiceItem
    var dueInKm: Double?
    var dueInDays: Int64?
    var level: DueLevel
    /// 0 = due now, 1 = just done; how far through its interval (for "most urgent first").
    var remaining: Double
}

let soonFraction = 0.1
let soonMinKm = 100.0
let soonDays: Int64 = 14
let soonDateDays: Int64 = 30

/// Whole local days from `today` to the day holding `ms`.
private func daysBetween(_ from: Date, _ to: Date, _ cal: Calendar) -> Int64 {
    Int64(cal.dateComponents([.day], from: cal.startOfDay(for: from), to: cal.startOfDay(for: to)).day!)
}

func serviceDue(_ item: ServiceItem, odometerNowKm: Double?, nowMs: Int64, zone: TimeZone) -> ServiceDue {
    let cal = calendar(zone)
    let today = dateOf(nowMs)
    switch item.serviceKind {
    case .date:
        guard let due = item.dueDateMs else { return ServiceDue(item: item, dueInKm: nil, dueInDays: nil, level: .noInterval, remaining: 1) }
        let days = daysBetween(today, dateOf(due), cal)
        let level: DueLevel = days < 0 ? .overdue : days <= soonDateDays ? .soon : .ok
        let span = Double(item.intervalMonths ?? 12) * 30
        return ServiceDue(item: item, dueInKm: nil, dueInDays: days, level: level, remaining: min(max(Double(days) / span, -1), 1))
    case .distance:
        let km = item.intervalKm.flatMap { $0 > 0 ? $0 : nil }
        let months = item.intervalMonths.flatMap { $0 > 0 ? $0 : nil }
        if km == nil && months == nil { return ServiceDue(item: item, dueInKm: nil, dueInDays: nil, level: .noInterval, remaining: 1) }
        var dueKm: Double?
        if let km, let last = item.lastDoneKm, let odo = odometerNowKm { dueKm = last + km - odo }
        let dueDays: Int64? = months.map { m in
            let lastDay = cal.startOfDay(for: dateOf(item.lastDoneMs))
            return daysBetween(today, cal.date(byAdding: .month, value: m, to: lastDay)!, cal)
        }
        let overdue = (dueKm.map { $0 <= 0 } ?? false) || (dueDays.map { $0 < 0 } ?? false)
        let soon = (dueKm.flatMap { d in km.map { d <= max($0 * soonFraction, soonMinKm) } } ?? false) || (dueDays.map { $0 <= soonDays } ?? false)
        let level: DueLevel = overdue ? .overdue : soon ? .soon : .ok
        let byKm = (dueKm != nil && km != nil) ? dueKm! / km! : 1.0
        let byDays = (dueDays != nil && months != nil) ? Double(dueDays!) / (Double(months!) * 30) : 1.0
        return ServiceDue(item: item, dueInKm: dueKm, dueInDays: dueDays, level: level, remaining: min(max(min(byKm, byDays), -1), 1))
    }
}

/// Overdue first, then due soon, then the rest; within each, the nearest first. Items without an interval last.
func byUrgency(_ dues: [ServiceDue]) -> [ServiceDue] {
    func rank(_ l: DueLevel) -> Int { switch l { case .overdue: 0; case .soon: 1; case .ok: 2; case .noInterval: 3 } }
    // Stable, like Kotlin's sortedWith.
    return dues.enumerated().sorted { a, b in
        (rank(a.element.level), a.element.remaining, a.offset) < (rank(b.element.level), b.element.remaining, b.offset)
    }.map(\.element)
}

/// The reminder shown at trip start and end: only when something is due soon or overdue.
func reminderOf(_ dues: [ServiceDue]) -> ServiceDue? {
    byUrgency(dues).first { $0.level == .soon || $0.level == .overdue }
}

/// "due in 120 km", "overdue by 50 km", "due in 12 days", "due today", "overdue by 3 days".
func dueText(_ d: ServiceDue) -> String {
    let km = d.dueInKm, days = d.dueInDays
    let kmFirst: Bool
    if km == nil { kmFirst = false }
    else if days == nil { kmFirst = true }
    else if let ik = d.item.intervalKm, let im = d.item.intervalMonths { kmFirst = km! / ik <= Double(days!) / (Double(im) * 30) }
    else { kmFirst = true }
    if d.level == .noInterval { return "no interval set" }
    if kmFirst, let km {
        let n = Int64(abs(km).rounded(.toNearestOrAwayFromZero))
        return km <= 0 ? "overdue by \(groupedInt(n)) km" : "due in \(groupedInt(n)) km"
    }
    if let days {
        if days == 0 { return "due today" }
        return days < 0 ? "overdue by \(dayCount(-days))" : "due in \(dayCount(days))"
    }
    return "set the odometer"
}

private func dayCount(_ d: Int64) -> String { d == 1 ? "1 day" : "\(d) days" }

/// A date item's next due date after "Mark done": `months` after the old one, and after today.
func nextDueDate(_ dueMs: Int64?, months monthsIn: Int, nowMs: Int64, zone: TimeZone) -> Int64 {
    let cal = calendar(zone)
    let months = max(monthsIn, 1)
    let today = cal.startOfDay(for: dateOf(nowMs))
    var due = cal.date(byAdding: .month, value: months, to: cal.startOfDay(for: dateOf(dueMs ?? nowMs)))!
    while due <= today { due = cal.date(byAdding: .month, value: months, to: due)! }
    return msOf(cal.startOfDay(for: due))
}

/// "Engine oil due in 120 km".
func reminderText(_ d: ServiceDue) -> String { "\(d.item.name) \(dueText(d))" }

/// A typed date, day first: "31/12/2026", "31-12-2026", "31.12.2026". Start of that day in `zone`; nil if not real.
func parseDate(_ text: String, zone: TimeZone) -> Int64? {
    let t = text.trimmingCharacters(in: .whitespaces)
    guard let re = try? NSRegularExpression(pattern: #"^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})$"#),
          let m = re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) else { return nil }
    func part(_ i: Int) -> Int { Int(t[Range(m.range(at: i), in: t)!])! }
    let (d, mo, y) = (part(1), part(2), part(3))
    let cal = calendar(zone)
    guard let date = cal.date(from: DateComponents(year: y, month: mo, day: d)) else { return nil }
    let c = cal.dateComponents([.year, .month, .day], from: date)
    guard c.year == y, c.month == mo, c.day == d else { return nil }   // 31/02 rolls over: not a real date
    return msOf(date)
}

/// "31/12/2026".
func formatDate(_ ms: Int64, zone: TimeZone) -> String {
    let c = calendar(zone).dateComponents([.year, .month, .day], from: dateOf(ms))
    return "\(c.day!)/\(c.month!)/\(c.year!)"
}

/// "every 2,000 km · 3 months", "yearly · due 31/12/2026".
func intervalText(_ item: ServiceItem, zone: TimeZone) -> String {
    switch item.serviceKind {
    case .date:
        let m = item.intervalMonths ?? 12
        let every = m == 12 ? "yearly" : m == 1 ? "monthly" : (m % 12 == 0 ? "every \(m / 12) years" : "every \(m) months")
        return ([every] + (item.dueDateMs.map { ["due \(formatDate($0, zone: zone))"] } ?? [])).joined(separator: " · ")
    case .distance:
        var parts: [String] = []
        if let km = item.intervalKm { parts.append("\(groupedInt(Int64(km.rounded(.toNearestOrAwayFromZero)))) km") }
        if let m = item.intervalMonths { parts.append(m == 1 ? "1 month" : "\(m) months") }
        return parts.isEmpty ? "no interval set" : "every " + parts.joined(separator: " · ")
    }
}
