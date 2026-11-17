import Foundation

// Port of Android `ui/nav/NavFormat.kt`.

private let posix = Locale(identifier: "en_US_POSIX")

/// Kotlin's `roundToInt` (half up, toward +∞), which Swift's `.rounded()` (half away from zero) differs from below 0.
private func roundHalfUp(_ v: Double) -> Int { Int((v + 0.5).rounded(.down)) }

/// Glanceable distances: "350 m", "1.2 km", "12 km"; imperial: "500 ft", "0.3 mi", "12 mi".
func formatNavDistance(_ meters: Double, _ unit: SpeedUnit) -> String {
    switch unit {
    case .kmh:
        if meters < 100 { return "\(max(0, roundHalfUp(meters / 10)) * 10) m" }
        if meters < 1_000 {
            let m = roundHalfUp(meters / 50) * 50
            return m == 1_000 ? "1 km" : "\(m) m"
        }
        // 9 950 m and up would print as "10.0 km"; from there it's whole kilometres.
        if meters < 9_950 { return String(format: "%.1f km", locale: posix, meters / 1_000) }
        return "\(roundHalfUp(meters / 1_000)) km"
    case .mph:
        let miles = meters / 1_609.344
        if miles < 0.1 { return "\(max(0, roundHalfUp(meters * 3.28084 / 50)) * 50) ft" }
        if miles < 9.95 { return String(format: "%.1f mi", locale: posix, miles) }
        return "\(roundHalfUp(miles)) mi"
    }
}

/// "8 min", "1 h 05 min". Rounded up: "0 min" while still riding reads as "arrived".
func formatNavDuration(_ seconds: Double) -> String {
    let minutes = max(0, Int64((seconds + 59) / 60))
    return minutes < 60 ? "\(minutes) min" : String(format: "%d h %02d min", locale: posix, minutes / 60, minutes % 60)
}

/// Arrival clock time ("HH:mm"), in `zone`.
func formatEta(nowMs: Int64, remainingS: Double, zone: TimeZone = .current) -> String {
    let ms = nowMs + Int64((remainingS * 1000).rounded())
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = zone
    let c = cal.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: Double(ms) / 1000))
    return String(format: "%02d:%02d", locale: posix, c.hour ?? 0, c.minute ?? 0)
}

private let roadNumber = try! NSRegularExpression(pattern: #"^\d+[A-Za-z]?$"#)

extension Instruction {
    /// The banner's line. Valhalla puts the road name there; a road known only by its number ("7") becomes
    /// "Route 7", and an unnamed road falls back to the maneuver in words.
    var bannerText: String {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return maneuverWords }
        if roadNumber.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil { return "Route \(t)" }
        return t.hasSuffix(".") ? String(t.dropLast()) : t // the router's fallback for unnamed roads: "Turn left."
    }

    var maneuverWords: String {
        let left = direction == .left || direction == .slightLeft || direction == .sharpLeft
        switch kind {
        case .arrive: return "Arrive"
        case .depart: return "Head out"
        case .roundabout: return "Roundabout"
        case .merge: return "Merge"
        case .fork: return left ? "Keep left" : "Keep right"
        case .ramp: return left ? "Take the ramp left" : "Take the ramp right"
        case .continue, .turn:
            switch direction {
            case .left: return "Turn left"
            case .right: return "Turn right"
            case .slightLeft: return "Bear left"
            case .slightRight: return "Bear right"
            case .sharpLeft: return "Sharp left"
            case .sharpRight: return "Sharp right"
            case .uTurn: return "Make a U-turn"
            case .straight, nil: return "Continue"
            }
        }
    }
}
