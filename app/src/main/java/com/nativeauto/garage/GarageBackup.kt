package com.nativeauto.garage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/** Everything in the Garage, for the ZIP backup (11c, Rider-Feedback "Decided": the Garage data as JSON). */
data class GarageData(
    val bikes: List<BikeRecord> = emptyList(),
    val readings: List<OdometerReading> = emptyList(),
    val refuels: List<RefuelRecord> = emptyList(),
    val prices: List<FuelPrice> = emptyList(),
    val services: List<ServiceItem> = emptyList(),
    val serviceRecords: List<ServiceRecord> = emptyList(),
    /** 4.7.2: gauge readings and dash km/L; none in older backups. */
    val fuelMarks: List<FuelMark> = emptyList(),
    val dashKmPerL: List<DashKmPerL> = emptyList(),
    /** 4.8: the rider's own fuels. */
    val customFuels: List<CustomFuel> = emptyList(),
) {
    val isEmpty: Boolean get() = bikes.isEmpty() && prices.isEmpty()
}

/**
 * The Garage's part of `backup.json`: plain JVM (the kotlinx JSON tree), unit-tested. Field names
 * match the Room columns, so a later column is one more line on each side. Restoring merges: what
 * is already there (same bike id, or the same entry at the same time) is skipped, so importing
 * one file twice changes nothing.
 */
object GarageBackup {

    suspend fun collect(dao: GarageDao) = GarageData(
        bikes = dao.allBikes(),
        readings = dao.allReadings(),
        refuels = dao.allRefuels(),
        prices = dao.allPrices(),
        services = dao.allServices(),
        serviceRecords = dao.allServiceRecords(),
        fuelMarks = dao.allFuelMarks(),
        dashKmPerL = dao.allDashKmPerL(),
        customFuels = dao.allCustomFuels(),
    )

    fun encode(g: GarageData): JsonObject = buildJsonObject {
        putJsonArray("bikes") {
            g.bikes.forEach { b ->
                addJsonObject {
                    put("id", b.id); put("name", b.name); put("type", b.type); put("model", b.model)
                    put("tankLitres", b.tankLitres); put("reserveLitres", b.reserveLitres); put("fuelType", b.fuelType)
                    put("startKmPerL", b.startKmPerL); put("odoFactor", b.odoFactor); put("active", b.active); put("lowFuelKm", b.lowFuelKm)
                    put("gaugeBars", b.gaugeBars); b.odoFactorManual?.let { put("odoFactorManual", it) }
                    put("cautionPct", b.cautionPct)
                    put("lastUsedAtMs", b.lastUsedAtMs); put("createdAtMs", b.createdAtMs)
                }
            }
        }
        putJsonArray("readings") {
            g.readings.forEach { r ->
                addJsonObject {
                    put("bikeId", r.bikeId); put("timeMs", r.timeMs); put("km", r.km); put("kind", r.kind)
                    put("awayKm", r.awayKm); put("tripOffsetM", r.tripOffsetM)
                }
            }
        }
        putJsonArray("refuels") {
            g.refuels.forEach { r ->
                addJsonObject {
                    put("bikeId", r.bikeId); put("timeMs", r.timeMs); put("odometerKm", r.odometerKm); put("litres", r.litres)
                    put("pricePerL", r.pricePerL); put("totalRp", r.totalRp); put("full", r.full); put("fuelType", r.fuelType)
                    if (!r.learn) put("learn", false)
                }
            }
        }
        putJsonArray("prices") {
            g.prices.forEach { p -> addJsonObject { put("fuelType", p.fuelType); put("pricePerL", p.pricePerL); put("updatedAtMs", p.updatedAtMs) } }
        }
        putJsonArray("customFuels") {
            g.customFuels.forEach { f ->
                addJsonObject { put("key", f.key); put("name", f.name); put("createdAtMs", f.createdAtMs); put("retired", f.retired) }
            }
        }
        putJsonArray("services") {
            g.services.forEach { s ->
                addJsonObject {
                    put("id", s.id); put("bikeId", s.bikeId); put("name", s.name); put("kind", s.kind)
                    put("intervalKm", s.intervalKm); put("intervalMonths", s.intervalMonths); put("dueDateMs", s.dueDateMs)
                    put("lastDoneMs", s.lastDoneMs); put("lastDoneKm", s.lastDoneKm); put("createdAtMs", s.createdAtMs)
                }
            }
        }
        putJsonArray("serviceRecords") {
            g.serviceRecords.forEach { r ->
                addJsonObject {
                    put("itemId", r.itemId); put("bikeId", r.bikeId); put("itemName", r.itemName); put("timeMs", r.timeMs)
                    put("odometerKm", r.odometerKm); put("costRp", r.costRp); put("note", r.note)
                }
            }
        }
        putJsonArray("fuelMarks") {
            g.fuelMarks.forEach { m ->
                addJsonObject {
                    put("bikeId", m.bikeId); put("timeMs", m.timeMs); put("odometerKm", m.odometerKm)
                    put("bars", m.bars); put("ofBars", m.ofBars); put("litres", m.litres)
                }
            }
        }
        putJsonArray("dashKmPerL") {
            g.dashKmPerL.forEach { d ->
                addJsonObject { put("bikeId", d.bikeId); put("timeMs", d.timeMs); put("kmPerL", d.kmPerL); put("tripKm", d.tripKm) }
            }
        }
    }

    fun decode(o: JsonObject): GarageData {
        fun list(key: String) = (o[key] as? JsonArray).orEmpty().map { it.jsonObject }
        return GarageData(
            bikes = list("bikes").map { b ->
                BikeRecord(
                    id = b.str("id")!!, name = b.str("name") ?: "Bike", type = b.str("type") ?: BikeType.SCOOTER_CVT.name,
                    model = b.str("model") ?: BikeModel.OTHER.name, tankLitres = b.dbl("tankLitres") ?: BikeModel.OTHER.tankLitres,
                    reserveLitres = b.dbl("reserveLitres") ?: BikeModel.OTHER.reserveLitres, fuelType = b.str("fuelType") ?: FuelType.PERTAMAX.name,
                    startKmPerL = b.dbl("startKmPerL") ?: DEFAULT_KM_PER_L, odoFactor = b.dbl("odoFactor") ?: 1.0,
                    active = b.bool("active") ?: false, lastUsedAtMs = b.lng("lastUsedAtMs") ?: 0, createdAtMs = b.lng("createdAtMs") ?: 0,
                    lowFuelKm = b.lng("lowFuelKm")?.toInt()?.takeIf { it in LOW_FUEL_RANGE_KM } ?: DEFAULT_LOW_FUEL_KM,
                    // Before 4.7: the model's bars.
                    gaugeBars = b.int("gaugeBars")?.takeIf { it in GAUGE_BARS_RANGE }
                        ?: BikeModel.entries.firstOrNull { it.name == b.str("model") }?.gaugeBars ?: DEFAULT_GAUGE_BARS,
                    odoFactorManual = b.dbl("odoFactorManual")?.takeIf { it in FACTOR_RANGE },
                    cautionPct = b.int("cautionPct")?.takeIf { p -> RangeCaution.entries.any { it.percent == p } } ?: 0,
                )
            },
            readings = list("readings").map { r ->
                OdometerReading(
                    bikeId = r.str("bikeId")!!, timeMs = r.lng("timeMs")!!, km = r.dbl("km")!!, kind = r.str("kind") ?: ReadingKind.CORRECTION.name,
                    awayKm = r.dbl("awayKm") ?: 0.0, tripOffsetM = r.dbl("tripOffsetM") ?: 0.0,
                )
            },
            refuels = list("refuels").map { r ->
                RefuelRecord(
                    bikeId = r.str("bikeId")!!, timeMs = r.lng("timeMs")!!, odometerKm = r.dbl("odometerKm")!!, litres = r.dbl("litres")!!,
                    pricePerL = r.dbl("pricePerL") ?: 0.0, totalRp = r.dbl("totalRp") ?: 0.0, full = r.bool("full") ?: true,
                    fuelType = r.str("fuelType") ?: FuelType.PERTAMAX.name, learn = r.bool("learn") ?: true,
                )
            },
            customFuels = list("customFuels").map { f ->
                CustomFuel(f.str("key")!!, f.str("name") ?: "Other fuel", f.lng("createdAtMs") ?: 0, f.bool("retired") ?: false)
            },
            prices = list("prices").map { p -> FuelPrice(p.str("fuelType")!!, p.dbl("pricePerL")!!, p.lng("updatedAtMs") ?: 0) },
            services = list("services").map { s ->
                ServiceItem(
                    id = s.lng("id") ?: 0, bikeId = s.str("bikeId")!!, name = s.str("name") ?: "Service", kind = s.str("kind") ?: ServiceKind.DISTANCE.name,
                    intervalKm = s.dbl("intervalKm"), intervalMonths = s.int("intervalMonths"), dueDateMs = s.lng("dueDateMs"),
                    lastDoneMs = s.lng("lastDoneMs") ?: 0, lastDoneKm = s.dbl("lastDoneKm"), createdAtMs = s.lng("createdAtMs") ?: 0,
                )
            },
            serviceRecords = list("serviceRecords").map { r ->
                ServiceRecord(
                    itemId = r.lng("itemId") ?: 0, bikeId = r.str("bikeId")!!, itemName = r.str("itemName") ?: "Service", timeMs = r.lng("timeMs")!!,
                    odometerKm = r.dbl("odometerKm"), costRp = r.dbl("costRp"), note = r.str("note"),
                )
            },
            fuelMarks = list("fuelMarks").map { m ->
                FuelMark(
                    bikeId = m.str("bikeId")!!, timeMs = m.lng("timeMs")!!, odometerKm = m.dbl("odometerKm")!!,
                    bars = m.int("bars") ?: 0, ofBars = m.int("ofBars") ?: DEFAULT_GAUGE_BARS, litres = m.dbl("litres")!!,
                )
            },
            dashKmPerL = list("dashKmPerL").map { d ->
                DashKmPerL(bikeId = d.str("bikeId")!!, timeMs = d.lng("timeMs")!!, kmPerL = d.dbl("kmPerL")!!, tripKm = d.dbl("tripKm") ?: 0.0)
            },
        )
    }

    /**
     * Merges [g] into the Garage. Bikes are matched by id; readings, fill-ups, gauge readings, dash km/L and service
     * records by bike and time; service items by bike, name and creation time. Restored bikes are inactive
     * when a bike is already being ridden here (one active bike). A fuel price replaces the local
     * one only if it's newer. Returns how many entries were added.
     */
    suspend fun restore(dao: GarageDao, g: GarageData): Int {
        var added = 0
        val local = dao.allBikes()
        val known = local.mapTo(HashSet()) { it.id }
        var hasActive = local.any { it.active }
        g.bikes.forEach { b ->
            if (known.add(b.id)) {
                val active = b.active && !hasActive
                dao.insertBike(b.copy(active = active))
                if (active) hasActive = true
                added++
            }
        }
        val readings = dao.allReadings().mapTo(HashSet()) { it.bikeId to it.timeMs }
        g.readings.filter { it.bikeId in known && readings.add(it.bikeId to it.timeMs) }.forEach { dao.insertReading(it.copy(id = 0)); added++ }
        val refuels = dao.allRefuels().mapTo(HashSet()) { it.bikeId to it.timeMs }
        g.refuels.filter { it.bikeId in known && refuels.add(it.bikeId to it.timeMs) }.forEach { dao.insertRefuel(it.copy(id = 0)); added++ }
        // 4.8: the rider's own fuels, by key; one that is here (or was deleted here) stays as it is.
        val fuels = dao.allCustomFuels().mapTo(HashSet()) { it.key }
        g.customFuels.filter { fuels.add(it.key) }.forEach { dao.upsertCustomFuel(it); added++ }
        val prices = dao.allPrices().associateBy { it.fuelType }
        g.prices.filter { p -> (prices[p.fuelType]?.updatedAtMs ?: Long.MIN_VALUE) < p.updatedAtMs }.forEach { dao.upsertPrice(it) }
        // Service items get new ids here; their history follows them.
        val localServices = dao.allServices()
        val idMap = HashMap<Long, Long>()
        g.services.filter { it.bikeId in known }.forEach { s ->
            val same = localServices.firstOrNull { it.bikeId == s.bikeId && it.name == s.name && it.createdAtMs == s.createdAtMs }
            idMap[s.id] = same?.id ?: dao.insertService(s.copy(id = 0)).also { added++ }
            // 4.7.2: done later on the other phone: this one's item starts over from there too.
            if (same != null && s.lastDoneMs > same.lastDoneMs) {
                dao.updateService(same.copy(lastDoneMs = s.lastDoneMs, lastDoneKm = s.lastDoneKm, dueDateMs = s.dueDateMs))
            }
        }
        val records = dao.allServiceRecords().mapTo(HashSet()) { Triple(it.bikeId, it.timeMs, it.itemName) }
        g.serviceRecords.filter { it.bikeId in known && records.add(Triple(it.bikeId, it.timeMs, it.itemName)) }.forEach {
            dao.insertServiceRecord(it.copy(id = 0, itemId = idMap[it.itemId] ?: 0))
            added++
        }
        val marks = dao.allFuelMarks().mapTo(HashSet()) { it.bikeId to it.timeMs }
        g.fuelMarks.filter { it.bikeId in known && marks.add(it.bikeId to it.timeMs) }.forEach { dao.insertFuelMark(it.copy(id = 0)); added++ }
        val dash = dao.allDashKmPerL().mapTo(HashSet()) { it.bikeId to it.timeMs }
        g.dashKmPerL.filter { it.bikeId in known && dash.add(it.bikeId to it.timeMs) }.forEach { dao.insertDashKmPerL(it.copy(id = 0)); added++ }
        return added
    }
}

private fun JsonObject.prim(k: String): JsonPrimitive? = this[k] as? JsonPrimitive
private fun JsonObject.str(k: String) = prim(k)?.contentOrNull
private fun JsonObject.dbl(k: String) = prim(k)?.doubleOrNull
private fun JsonObject.lng(k: String) = prim(k)?.longOrNull
private fun JsonObject.int(k: String) = prim(k)?.intOrNull
private fun JsonObject.bool(k: String) = prim(k)?.booleanOrNull
