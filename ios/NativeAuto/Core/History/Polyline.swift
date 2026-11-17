import Foundation

/// Port of Android `history/Polyline.kt`. `GeoPoint` lives in `Core/Nav/NavModels.swift`.

/// Google's encoded polyline format, precision 5 (~1 m). Same text as the Android app writes,
/// so backups move between the two.
enum Polyline {
    static func encode(_ points: [GeoPoint]) -> String {
        var out = ""
        var lastLat: Int64 = 0, lastLon: Int64 = 0
        for p in points {
            let lat = Int64((p.latitude * 1e5).rounded()), lon = Int64((p.longitude * 1e5).rounded())
            write(lat - lastLat, into: &out)
            write(lon - lastLon, into: &out)
            lastLat = lat; lastLon = lon
        }
        return out
    }

    static func decode(_ encoded: String) -> [GeoPoint] {
        let bytes = Array(encoded.utf8)
        var i = 0, lat: Int64 = 0, lon: Int64 = 0
        var out: [GeoPoint] = []
        func read() -> Int64 {
            var result: Int64 = 0, shift: Int64 = 0
            while true {
                let b = Int64(bytes[i]) - 63
                i += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 { break }
            }
            return result & 1 != 0 ? ~(result >> 1) : result >> 1
        }
        while i < bytes.count {
            lat += read(); lon += read()
            out.append(GeoPoint(latitude: Double(lat) / 1e5, longitude: Double(lon) / 1e5))
        }
        return out
    }

    private static func write(_ value: Int64, into out: inout String) {
        var v = value < 0 ? ~(value << 1) : value << 1
        while v >= 0x20 {
            out.unicodeScalars.append(UnicodeScalar(UInt8((0x20 | (v & 0x1f)) + 63)))
            v >>= 5
        }
        out.unicodeScalars.append(UnicodeScalar(UInt8(v + 63)))
    }
}

/// Douglas–Peucker: drops points that deviate less than `toleranceM` from the line through their neighbours.
func simplify(_ points: [GeoPoint], toleranceM: Double) -> [GeoPoint] {
    guard points.count >= 3 else { return points }
    var keep = [Bool](repeating: false, count: points.count)
    keep[0] = true; keep[points.count - 1] = true
    var stack = [(0, points.count - 1)]
    while let (first, last) = stack.popLast() {
        if last - first < 2 { continue }
        var maxD = 0.0, maxI = -1
        for k in (first + 1)..<last {
            let d = distanceToSegmentM(points[k], points[first], points[last])
            if d > maxD { maxD = d; maxI = k }
        }
        if maxD > toleranceM {
            keep[maxI] = true
            stack.append((first, maxI)); stack.append((maxI, last))
        }
    }
    return points.enumerated().filter { keep[$0.offset] }.map(\.element)
}

/// Local flat-earth approximation: plenty for segments of a few km.
private func distanceToSegmentM(_ p: GeoPoint, _ a: GeoPoint, _ b: GeoPoint) -> Double {
    let mLat = 111_320.0, mLon = 111_320.0 * cos(a.latitude * .pi / 180)
    let bx = (b.longitude - a.longitude) * mLon, by = (b.latitude - a.latitude) * mLat
    let px = (p.longitude - a.longitude) * mLon, py = (p.latitude - a.latitude) * mLat
    let len2 = bx * bx + by * by
    if len2 == 0 { return haversineMeters(p.latitude, p.longitude, a.latitude, a.longitude) }
    let t = min(1, max(0, (px * bx + py * by) / len2))
    let dx = px - t * bx, dy = py - t * by
    return (dx * dx + dy * dy).squareRoot()
}
