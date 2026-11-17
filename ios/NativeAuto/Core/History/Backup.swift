import Foundation

/// Port of Android `history/Backup.kt`: the ZIP backup. `backup.json` holds everything needed to restore (every trip
/// with its figures and full track, and, from format 2, the Garage); `gpx/` holds one GPX per trip for other apps.
/// Import reads only `backup.json`. The same file moves between the Android and iOS apps.
enum Backup {
    static let format = 2
    static let jsonEntry = "backup.json"

    struct TripWithTrack: Equatable {
        var trip: TripRecord
        var points: [TrackPointRecord]
    }

    struct Contents {
        var trips: [TripWithTrack]
        var garage: GarageData?
    }

    struct BackupError: Error, Equatable, CustomStringConvertible {
        let description: String
    }

    static func write(_ trips: [TripWithTrack], garage: GarageData? = nil) -> Data {
        var entries = [Zip.Entry(name: jsonEntry, data: encode(trips, garage: garage))]
        for t in trips { entries.append(Zip.Entry(name: "gpx/" + gpxFileName(t.trip), data: Data(buildGpx(t.trip, t.points).utf8))) }
        return Zip.write(entries)
    }

    /// Trips and Garage; throws (not a backup, or a newer format) with a reason.
    static func readAll(_ zip: Data) throws -> Contents {
        let entries: [Zip.Entry]
        do { entries = try Zip.read(zip) } catch { throw BackupError(description: "Not a Native Auto backup (not a ZIP file)") }
        guard let json = entries.first(where: { $0.name == jsonEntry }) else {
            throw BackupError(description: "Not a Native Auto backup (no \(jsonEntry))")
        }
        return try decodeAll(json.data)
    }

    static func read(_ zip: Data) throws -> [TripWithTrack] { try readAll(zip).trips }

    // MARK: - JSON

    static func encode(_ trips: [TripWithTrack], garage: GarageData? = nil) -> Data {
        var root: [String: Any] = ["format": format, "app": "Native Auto"]
        if let garage { root["garage"] = GarageBackup.encode(garage) }
        root["trips"] = trips.map { t -> [String: Any] in
            let r = t.trip
            return [
                "startedAtMs": NSNumber(value: r.startedAtMs), "endedAtMs": NSNumber(value: r.endedAtMs),
                "durationMs": NSNumber(value: r.durationMs), "movingMs": NSNumber(value: r.movingMs),
                "stoppedMs": NSNumber(value: r.stoppedMs), "distanceM": NSNumber(value: r.distanceM),
                "maxSpeedMps": NSNumber(value: r.maxSpeedMps),
                "startLat": NSNumber(value: r.startLat), "startLon": NSNumber(value: r.startLon),
                "endLat": NSNumber(value: r.endLat), "endLon": NSNumber(value: r.endLon),
                "startAddress": r.startAddress ?? NSNull(), "endAddress": r.endAddress ?? NSNull(),
                "track": r.track, "bikeId": r.bikeId ?? NSNull(),
                // Compact: one array per point, [timeMs, lat, lon, speed, bearing|null, accuracy].
                "points": t.points.map { p -> [Any] in
                    [NSNumber(value: p.timeMs), NSNumber(value: p.latitude), NSNumber(value: p.longitude), NSNumber(value: p.speedMps),
                     p.bearingDeg.map { NSNumber(value: $0) } ?? NSNull(), NSNumber(value: p.accuracyM)]
                },
            ]
        }
        return (try? JSONSerialization.data(withJSONObject: root, options: [.withoutEscapingSlashes])) ?? Data()
    }

    static func decodeAll(_ data: Data) throws -> Contents {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw BackupError(description: "Not a Native Auto backup (unreadable \(jsonEntry))")
        }
        let format = Json.int64(root["format"]) ?? 0
        guard (1...Int64(Self.format)).contains(format) else {
            throw BackupError(description: "Backup format \(format) isn't supported by this version")
        }
        let garage = (root["garage"] as? [String: Any]).map(GarageBackup.decode)
        return Contents(trips: try decodeTrips(root), garage: garage)
    }

    static func decodeAll(_ text: String) throws -> Contents { try decodeAll(Data(text.utf8)) }

    private static func decodeTrips(_ root: [String: Any]) throws -> [TripWithTrack] {
        guard let list = root["trips"] as? [Any] else { throw BackupError(description: "Damaged backup: no trips") }
        return try list.map { e in
            guard let o = e as? [String: Any] else { throw BackupError(description: "Damaged backup: a trip") }
            func long(_ k: String) throws -> Int64 {
                guard let v = Json.int64(o[k]) else { throw BackupError(description: "Damaged backup: \(k)") }
                return v
            }
            func double(_ k: String) throws -> Double {
                guard let v = Json.double(o[k]) else { throw BackupError(description: "Damaged backup: \(k)") }
                return v
            }
            let trip = TripRecord(
                startedAtMs: try long("startedAtMs"), endedAtMs: try long("endedAtMs"), durationMs: try long("durationMs"),
                movingMs: try long("movingMs"), distanceM: try double("distanceM"), maxSpeedMps: Float(try double("maxSpeedMps")),
                startLat: try double("startLat"), startLon: try double("startLon"), endLat: try double("endLat"), endLon: try double("endLon"),
                startAddress: Json.string(o["startAddress"]), endAddress: Json.string(o["endAddress"]),
                track: Json.string(o["track"]) ?? "", stoppedMs: Json.int64(o["stoppedMs"]) ?? 0, bikeId: Json.string(o["bikeId"]))
            let points = try ((o["points"] as? [Any]) ?? []).map { pe -> TrackPointRecord in
                guard let a = pe as? [Any], a.count >= 6, let time = Json.int64(a[0]), let lat = Json.double(a[1]), let lon = Json.double(a[2]),
                      let speed = Json.double(a[3]), let acc = Json.double(a[5]) else { throw BackupError(description: "Damaged backup: a point") }
                return TrackPointRecord(timeMs: time, latitude: lat, longitude: lon, speedMps: Float(speed),
                                        bearingDeg: Json.double(a[4]).map(Float.init), accuracyM: Float(acc))
            }
            return TripWithTrack(trip: trip, points: points)
        }
    }

    /// Adds the backup's trips that aren't in the history yet (same start time = same trip), so importing the same
    /// file twice changes nothing. Returns how many were added.
    static func restore(_ dao: TripDao, _ trips: [TripWithTrack]) -> Int {
        var known = Set(dao.allTrips().map(\.startedAtMs))
        var added = 0
        for t in trips where known.insert(t.trip.startedAtMs).inserted {
            var trip = t.trip
            trip.id = 0
            dao.insertWithTrack(trip, t.points)
            added += 1
        }
        return added
    }
}

/// Reading JSON values as Android's kotlinx tree does: numbers by kind, `null` as nil, no type coercion of strings.
enum Json {
    static func number(_ v: Any?) -> NSNumber? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n
    }
    static func int64(_ v: Any?) -> Int64? {
        guard let n = number(v) else { return nil }
        let d = n.doubleValue
        return d == d.rounded() ? n.int64Value : nil
    }
    static func int(_ v: Any?) -> Int? { int64(v).map(Int.init) }
    static func double(_ v: Any?) -> Double? { number(v)?.doubleValue }
    static func string(_ v: Any?) -> String? { v as? String }
    static func bool(_ v: Any?) -> Bool? {
        guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }
}
