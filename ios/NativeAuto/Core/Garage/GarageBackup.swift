import Foundation

/// Everything in the Garage, for the ZIP backup (11c). Gauge marks and dash km/L aren't in the backup on Android
/// either (format 2, see DECISIONS 12b), so the file stays the same on both phones.
struct GarageData: Equatable {
    var bikes: [BikeRecord] = []
    var readings: [OdometerReading] = []
    var refuels: [RefuelRecord] = []
    var prices: [FuelPrice] = []
    var services: [ServiceItem] = []
    var serviceRecords: [ServiceRecord] = []

    var isEmpty: Bool { bikes.isEmpty && prices.isEmpty }
}

/// Port of Android `garage/GarageBackup.kt`: the Garage's part of `backup.json`. Field names match Android's columns.
/// Restoring merges: what is already there (same bike id, or the same entry at the same time) is skipped, so
/// importing one file twice changes nothing.
enum GarageBackup {
    static func collect(_ dao: GarageDao) -> GarageData {
        GarageData(bikes: dao.allBikes(), readings: dao.allReadings(), refuels: dao.allRefuels(), prices: dao.allPrices(),
                   services: dao.allServices(), serviceRecords: dao.allServiceRecords())
    }

    private static func opt<T>(_ v: T?) -> Any { v.map { $0 as Any } ?? NSNull() }

    static func encode(_ g: GarageData) -> [String: Any] {
        [
            "bikes": g.bikes.map { b -> [String: Any] in
                ["id": b.id, "name": b.name, "type": b.type, "model": b.model, "tankLitres": b.tankLitres, "reserveLitres": b.reserveLitres,
                 "fuelType": b.fuelType, "startKmPerL": b.startKmPerL, "odoFactor": b.odoFactor, "active": b.active,
                 "lastUsedAtMs": NSNumber(value: b.lastUsedAtMs), "createdAtMs": NSNumber(value: b.createdAtMs)]
            },
            "readings": g.readings.map { r -> [String: Any] in
                ["bikeId": r.bikeId, "timeMs": NSNumber(value: r.timeMs), "km": r.km, "kind": r.kind, "awayKm": r.awayKm, "tripOffsetM": r.tripOffsetM]
            },
            "refuels": g.refuels.map { r -> [String: Any] in
                ["bikeId": r.bikeId, "timeMs": NSNumber(value: r.timeMs), "odometerKm": r.odometerKm, "litres": r.litres,
                 "pricePerL": r.pricePerL, "totalRp": r.totalRp, "full": r.full, "fuelType": r.fuelType]
            },
            "prices": g.prices.map { p -> [String: Any] in
                ["fuelType": p.fuelType, "pricePerL": p.pricePerL, "updatedAtMs": NSNumber(value: p.updatedAtMs)]
            },
            "services": g.services.map { s -> [String: Any] in
                ["id": NSNumber(value: s.id), "bikeId": s.bikeId, "name": s.name, "kind": s.kind, "intervalKm": opt(s.intervalKm),
                 "intervalMonths": opt(s.intervalMonths), "dueDateMs": opt(s.dueDateMs.map { NSNumber(value: $0) }),
                 "lastDoneMs": NSNumber(value: s.lastDoneMs), "lastDoneKm": opt(s.lastDoneKm), "createdAtMs": NSNumber(value: s.createdAtMs)]
            },
            "serviceRecords": g.serviceRecords.map { r -> [String: Any] in
                ["itemId": NSNumber(value: r.itemId), "bikeId": r.bikeId, "itemName": r.itemName, "timeMs": NSNumber(value: r.timeMs),
                 "odometerKm": opt(r.odometerKm), "costRp": opt(r.costRp), "note": opt(r.note)]
            },
        ]
    }

    /// Entries missing a required field are skipped (Android would refuse the whole file; skipping one bad row is kinder).
    static func decode(_ o: [String: Any]) -> GarageData {
        func list(_ key: String) -> [[String: Any]] { (o[key] as? [Any] ?? []).compactMap { $0 as? [String: Any] } }
        let other = BikeModel.other
        return GarageData(
            bikes: list("bikes").compactMap { b in
                guard let id = Json.string(b["id"]) else { return nil }
                return BikeRecord(
                    id: id, name: Json.string(b["name"]) ?? "Bike", type: Json.string(b["type"]) ?? BikeType.scooterCvt.rawValue,
                    model: Json.string(b["model"]) ?? other.rawValue, tankLitres: Json.double(b["tankLitres"]) ?? other.tankLitres,
                    reserveLitres: Json.double(b["reserveLitres"]) ?? other.reserveLitres,
                    fuelType: Json.string(b["fuelType"]) ?? FuelType.pertamax.rawValue,
                    startKmPerL: Json.double(b["startKmPerL"]) ?? defaultKmPerL, odoFactor: Json.double(b["odoFactor"]) ?? 1,
                    active: Json.bool(b["active"]) ?? false, lastUsedAtMs: Json.int64(b["lastUsedAtMs"]) ?? 0,
                    createdAtMs: Json.int64(b["createdAtMs"]) ?? 0)
            },
            readings: list("readings").compactMap { r in
                guard let bike = Json.string(r["bikeId"]), let t = Json.int64(r["timeMs"]), let km = Json.double(r["km"]) else { return nil }
                return OdometerReading(bikeId: bike, timeMs: t, km: km, kind: Json.string(r["kind"]) ?? ReadingKind.correction.rawValue,
                                       awayKm: Json.double(r["awayKm"]) ?? 0, tripOffsetM: Json.double(r["tripOffsetM"]) ?? 0)
            },
            refuels: list("refuels").compactMap { r in
                guard let bike = Json.string(r["bikeId"]), let t = Json.int64(r["timeMs"]), let odo = Json.double(r["odometerKm"]),
                      let litres = Json.double(r["litres"]) else { return nil }
                return RefuelRecord(bikeId: bike, timeMs: t, odometerKm: odo, litres: litres, pricePerL: Json.double(r["pricePerL"]) ?? 0,
                                    totalRp: Json.double(r["totalRp"]) ?? 0, full: Json.bool(r["full"]) ?? true,
                                    fuelType: Json.string(r["fuelType"]) ?? FuelType.pertamax.rawValue)
            },
            prices: list("prices").compactMap { p in
                guard let f = Json.string(p["fuelType"]), let price = Json.double(p["pricePerL"]) else { return nil }
                return FuelPrice(f, price, Json.int64(p["updatedAtMs"]) ?? 0)
            },
            services: list("services").compactMap { s in
                guard let bike = Json.string(s["bikeId"]) else { return nil }
                return ServiceItem(
                    id: Json.int64(s["id"]) ?? 0, bikeId: bike, name: Json.string(s["name"]) ?? "Service",
                    kind: Json.string(s["kind"]) ?? ServiceKind.distance.rawValue, intervalKm: Json.double(s["intervalKm"]),
                    intervalMonths: Json.int(s["intervalMonths"]), dueDateMs: Json.int64(s["dueDateMs"]),
                    lastDoneMs: Json.int64(s["lastDoneMs"]) ?? 0, lastDoneKm: Json.double(s["lastDoneKm"]),
                    createdAtMs: Json.int64(s["createdAtMs"]) ?? 0)
            },
            serviceRecords: list("serviceRecords").compactMap { r in
                guard let bike = Json.string(r["bikeId"]), let t = Json.int64(r["timeMs"]) else { return nil }
                return ServiceRecord(itemId: Json.int64(r["itemId"]) ?? 0, bikeId: bike, itemName: Json.string(r["itemName"]) ?? "Service",
                                     timeMs: t, odometerKm: Json.double(r["odometerKm"]), costRp: Json.double(r["costRp"]),
                                     note: Json.string(r["note"]))
            })
    }

    /// Merges `g` into the Garage. Bikes by id; readings, fill-ups and service records by bike and time (records also by
    /// name); service items by bike, name and creation time. Restored bikes are inactive when a bike is already being
    /// ridden here. A fuel price replaces the local one only if newer. Returns how many entries were added.
    static func restore(_ dao: GarageDao, _ g: GarageData) -> Int {
        var added = 0
        let local = dao.allBikes()
        var known = Set(local.map(\.id))
        var hasActive = local.contains { $0.active }
        for b in g.bikes where known.insert(b.id).inserted {
            var bike = b
            bike.active = b.active && !hasActive
            dao.insertBike(bike)
            if bike.active { hasActive = true }
            added += 1
        }
        struct Key: Hashable { let bike: String; let time: Int64 }
        var readings = Set(dao.allReadings().map { Key(bike: $0.bikeId, time: $0.timeMs) })
        for r in g.readings where known.contains(r.bikeId) && readings.insert(Key(bike: r.bikeId, time: r.timeMs)).inserted {
            var x = r; x.id = 0; dao.insertReading(x); added += 1
        }
        var refuels = Set(dao.allRefuels().map { Key(bike: $0.bikeId, time: $0.timeMs) })
        for r in g.refuels where known.contains(r.bikeId) && refuels.insert(Key(bike: r.bikeId, time: r.timeMs)).inserted {
            var x = r; x.id = 0; dao.insertRefuel(x); added += 1
        }
        let prices = Dictionary(dao.allPrices().map { ($0.fuelType, $0) }, uniquingKeysWith: { a, _ in a })
        for p in g.prices where (prices[p.fuelType]?.updatedAtMs ?? Int64.min) < p.updatedAtMs { dao.upsertPrice(p) }
        // Service items get new ids here; their history follows them.
        let localServices = dao.allServices()
        var idMap: [Int64: Int64] = [:]
        for s in g.services where known.contains(s.bikeId) {
            if let same = localServices.first(where: { $0.bikeId == s.bikeId && $0.name == s.name && $0.createdAtMs == s.createdAtMs }) {
                idMap[s.id] = same.id
            } else {
                var x = s; x.id = 0
                idMap[s.id] = dao.insertService(x)
                added += 1
            }
        }
        struct RecordKey: Hashable { let bike: String; let time: Int64; let name: String }
        var records = Set(dao.allServiceRecords().map { RecordKey(bike: $0.bikeId, time: $0.timeMs, name: $0.itemName) })
        for r in g.serviceRecords where known.contains(r.bikeId) && records.insert(RecordKey(bike: r.bikeId, time: r.timeMs, name: r.itemName)).inserted {
            var x = r; x.id = 0; x.itemId = idMap[r.itemId] ?? 0
            dao.insertServiceRecord(x)
            added += 1
        }
        return added
    }
}
