import Foundation

/// A point on the card's edge and the edge's outward normal there. Port of Android `ui/clock/EdgeGeometry.kt`.
struct EdgeHit: Equatable {
    var x: Double, y: Double
    var nx: Double, ny: Double
}

/// The edge-tick face (8c): `fraction` of a turn is that fraction of the way **along the edge** of a rounded
/// rectangle centred on the origin (half-sizes `halfW` × `halfH`, corner `radius`, y down), clockwise from the top
/// centre. So 60 ticks are evenly spaced on any shape of pane, and a hand pointing at `edgeAt(minute / 60)` lands on
/// its tick.
func edgeAt(_ fraction: Double, halfW: Double, halfH: Double, radius: Double) -> EdgeHit {
    let r = min(max(radius, 0), min(halfW, halfH))
    let sx = halfW - r, sy = halfH - r
    let arc = Double.pi / 2 * r
    let perimeter = 4 * sx + 4 * sy + 4 * arc
    let f = (fraction.truncatingRemainder(dividingBy: 1) + 1).truncatingRemainder(dividingBy: 1)
    var d = f * perimeter

    func corner(_ cx: Double, _ cy: Double, _ startDeg: Double, _ along: Double) -> EdgeHit {
        let a = (startDeg + 90 * (arc > 0 ? along / arc : 0)) * Double.pi / 180
        let nx = cos(a), ny = sin(a)
        return EdgeHit(x: cx + nx * r, y: cy + ny * r, nx: nx, ny: ny)
    }
    if d <= sx { return EdgeHit(x: d, y: -halfH, nx: 0, ny: -1) }; d -= sx
    if d <= arc { return corner(sx, -sy, -90, d) }; d -= arc
    if d <= 2 * sy { return EdgeHit(x: halfW, y: -sy + d, nx: 1, ny: 0) }; d -= 2 * sy
    if d <= arc { return corner(sx, sy, 0, d) }; d -= arc
    if d <= 2 * sx { return EdgeHit(x: sx - d, y: halfH, nx: 0, ny: 1) }; d -= 2 * sx
    if d <= arc { return corner(-sx, sy, 90, d) }; d -= arc
    if d <= 2 * sy { return EdgeHit(x: -halfW, y: sy - d, nx: -1, ny: 0) }; d -= 2 * sy
    if d <= arc { return corner(-sx, -sy, 180, d) }; d -= arc
    return EdgeHit(x: -sx + d, y: -halfH, nx: 0, ny: -1)
}

/// The hands as fractions of a turn.
struct HandTurns: Equatable {
    var hour: Double, minute: Double, second: Double
}

func handTurns(hour: Double, minute: Double, second: Double) -> HandTurns {
    HandTurns(hour: hour.truncatingRemainder(dividingBy: 12) / 12,
              minute: minute.truncatingRemainder(dividingBy: 60) / 60,
              second: second.truncatingRemainder(dividingBy: 60) / 60)
}

/// The numerals, and "AM"/"PM" on a 12-hour phone.
struct ClockTime: Equatable {
    var digits: String
    var suffix: String?
}

func formatClockTime(_ date: Date, zone: TimeZone, is24: Bool) -> ClockTime {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = zone
    let c = cal.dateComponents([.hour, .minute], from: date)
    let h = c.hour!, m = c.minute!
    if is24 { return ClockTime(digits: String(format: "%02d:%02d", h, m), suffix: nil) }
    let h12 = h % 12 == 0 ? 12 : h % 12
    return ClockTime(digits: String(format: "%d:%02d", h12, m), suffix: h < 12 ? "AM" : "PM")
}

/// "Sun 8 Nov": short enough for a quarter pane.
func formatClockDate(_ date: Date, zone: TimeZone, locale: Locale) -> String {
    let f = DateFormatter()
    f.locale = locale
    f.timeZone = zone
    f.dateFormat = "EEE d MMM"
    return f.string(from: date)
}

/// Whether the phone uses the 24-hour clock (the "j" skeleton has no "a").
func phoneUses24Hour(_ locale: Locale = .current) -> Bool {
    !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "").contains("a")
}
