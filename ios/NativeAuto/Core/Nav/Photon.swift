import Foundation

// Port of Android `nav/PlaceSearch.kt` `parsePhoton`: Photon's GeoJSON → places. Pure, so it's tested offline.

func parsePhoton(_ body: Data) -> [Place] {
    guard let root = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
          let features = root["features"] as? [Any] else { return [] }
    return features.compactMap { item -> Place? in
        guard let obj = item as? [String: Any],
              let coords = (obj["geometry"] as? [String: Any])?["coordinates"] as? [Any],
              coords.count >= 2,
              let lon = number(coords[0]), let lat = number(coords[1]) else { return nil }
        let p = obj["properties"] as? [String: Any] ?? [:]
        func prop(_ key: String) -> String? {
            guard let v = p[key] else { return nil }
            let s = (v as? String) ?? (v as? NSNumber)?.stringValue
            return s.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        }
        let streetParts = [prop("street"), prop("housenumber")].compactMap { $0 }
        let street = streetParts.isEmpty ? nil : streetParts.joined(separator: " ")
        guard let name = prop("name") ?? street else { return nil }
        var parts: [String] = []
        for part in [street == name ? nil : street, prop("district") ?? prop("locality"), prop("city") ?? prop("county"), prop("state")] {
            if let part, !parts.contains(part) { parts.append(part) }
        }
        return Place(name: name, detail: parts.isEmpty ? nil : parts.joined(separator: ", "), point: GeoPoint(lat, lon))
    }
}

func parsePhoton(_ body: String) -> [Place] { parsePhoton(Data(body.utf8)) }

/// A JSON number (never a string or a bool).
private func number(_ v: Any) -> Double? {
    guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
    return n.doubleValue
}
