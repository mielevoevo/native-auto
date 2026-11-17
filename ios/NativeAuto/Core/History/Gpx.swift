import Foundation

// Port of Android `history/Gpx.kt`: GPX 1.1 for one trip, byte for byte the same as the Android export.

/// Trips with the full track (9c) carry each point's time, and speed (m/s) and course (degrees) as `<extensions>`.
/// Older trips only have the simplified path, so their points have no time.
func buildGpx(_ trip: TripRecord, _ points: [TrackPointRecord], zone: TimeZone = .current) -> String {
    var s = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    s += "<gpx version=\"1.1\" creator=\"Native Auto\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
    let name = escapeXml(gpxName(trip, zone: zone))
    s += "  <metadata><name>\(name)</name><time>\(isoTime(trip.startedAtMs))</time></metadata>\n"
    s += "  <trk>\n    <name>\(name)</name>\n    <trkseg>\n"
    if !points.isEmpty {
        for p in points {
            s += "      <trkpt lat=\"\(coord(p.latitude))\" lon=\"\(coord(p.longitude))\">"
            s += "<time>\(isoTime(p.timeMs))</time>"
            s += "<extensions><speed>\(number(Double(p.speedMps)))</speed>"
            if let b = p.bearingDeg { s += "<course>\(number(Double(b)))</course>" }
            s += "</extensions></trkpt>\n"
        }
    } else {
        for p in Polyline.decode(trip.track) {
            s += "      <trkpt lat=\"\(coord(p.latitude))\" lon=\"\(coord(p.longitude))\"/>\n"
        }
    }
    s += "    </trkseg>\n  </trk>\n</gpx>\n"
    return s
}

/// "Ride 2026-11-09 08:15", plus the start address when known. Local time.
func gpxName(_ trip: TripRecord, zone: TimeZone = .current) -> String {
    "Ride " + stamp(trip.startedAtMs, "yyyy-MM-dd HH:mm", zone) + (trip.startAddress.map { " · \($0)" } ?? "")
}

/// A file name that sorts by date: "ride-2026-11-09-0815-<id>.gpx" (UTC, so it never collides on DST).
func gpxFileName(_ trip: TripRecord) -> String {
    "ride-" + stamp(trip.startedAtMs, "yyyy-MM-dd-HHmm", TimeZone(identifier: "UTC")!) + "-\(trip.id).gpx"
}

/// Java's `ISO_INSTANT`: "2025-11-09T01:00:00Z", milliseconds only when there are any.
func isoTime(_ ms: Int64) -> String {
    let secs = Int64((Double(ms) / 1000).rounded(.down))
    let frac = ms - secs * 1000
    let base = stamp(secs * 1000, "yyyy-MM-dd'T'HH:mm:ss", TimeZone(identifier: "UTC")!)
    return frac == 0 ? base + "Z" : base + String(format: ".%03lld", frac) + "Z"
}

private func stamp(_ ms: Int64, _ pattern: String, _ zone: TimeZone) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.calendar = Calendar(identifier: .gregorian)
    f.timeZone = zone
    f.dateFormat = pattern
    return f.string(from: dateOf(ms))
}

private func coord(_ v: Double) -> String { String(format: "%.7f", v) }
private func number(_ v: Double) -> String { String(format: "%.2f", v) }

func escapeXml(_ s: String) -> String {
    s.replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\"", with: "&quot;")
        .replacingOccurrences(of: "'", with: "&apos;")
}
