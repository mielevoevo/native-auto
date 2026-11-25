package com.nativeauto.history

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import com.nativeauto.garage.GarageBackup
import com.nativeauto.garage.GarageData
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The ZIP backup (Rider-Feedback "Decided", 9d): `backup.json` holds everything needed to restore
 * (every trip with all its figures and its full track), and `gpx/` holds one GPX per trip for other
 * apps. Import reads only `backup.json`. Format 2 (11c) adds the Garage (`garage`, see
 * [GarageBackup]); format 1 files still import (no Garage in them). 4.7.2 adds altitude to the points, and gauge readings
 * and dash km/L to the Garage; both optional, so the format stays 2.
 *
 * Plain JVM code (java.util.zip + the kotlinx JSON tree), so it's unit-tested without a device.
 */
object Backup {
    const val FORMAT = 2
    const val JSON_ENTRY = "backup.json"

    class TripWithTrack(val trip: TripRecord, val points: List<TrackPointRecord>)

    /** A backup's contents: the trips and (format 2) the Garage. */
    class Contents(val trips: List<TripWithTrack>, val garage: GarageData?)

    fun write(trips: List<TripWithTrack>, out: OutputStream, garage: GarageData? = null) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(encode(trips, garage).toByteArray())
            zip.closeEntry()
            trips.forEach { t ->
                zip.putNextEntry(ZipEntry("gpx/" + gpxFileName(t.trip)))
                zip.write(buildGpx(t.trip, t.points).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** The trips in a backup, or an exception (not a backup, or a newer format) with a reason. */
    fun read(input: InputStream): List<TripWithTrack> = readAll(input).trips

    /** Trips and Garage; an exception (not a backup, or a newer format) with a reason. */
    fun readAll(input: InputStream): Contents {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == JSON_ENTRY) return decodeAll(zip.readBytes().decodeToString())
            }
        }
        throw IllegalArgumentException("Not a Native Auto backup (no $JSON_ENTRY)")
    }

    internal fun encode(trips: List<TripWithTrack>, garage: GarageData? = null): String = buildJsonObject {
        put("format", FORMAT)
        put("app", "Native Auto")
        if (garage != null) put("garage", GarageBackup.encode(garage))
        putJsonArray("trips") {
            trips.forEach { t ->
                val r = t.trip
                addJsonObject {
                    put("startedAtMs", r.startedAtMs)
                    put("endedAtMs", r.endedAtMs)
                    put("durationMs", r.durationMs)
                    put("movingMs", r.movingMs)
                    put("stoppedMs", r.stoppedMs)
                    put("distanceM", r.distanceM)
                    put("maxSpeedMps", r.maxSpeedMps)
                    put("startLat", r.startLat)
                    put("startLon", r.startLon)
                    put("endLat", r.endLat)
                    put("endLon", r.endLon)
                    put("startAddress", r.startAddress)
                    put("endAddress", r.endAddress)
                    put("track", r.track)
                    put("bikeId", r.bikeId)
                    // 4.3d: optional (format stays 2); absent on trips without them, and older files read fine.
                    r.loadIndex?.let { put("loadIndex", it) }
                    r.loadDistanceM?.let { put("loadDistanceM", it) }
                    r.loadAero?.let { put("loadAero", it) }
                    r.loadAccel?.let { put("loadAccel", it) }
                    r.loadClimbM?.let { put("loadClimbM", it) }
                    r.loadStops?.let { put("loadStops", it) }
                    r.loadCrawlS?.let { put("loadCrawlS", it) }
                    r.loadCrawlM?.let { put("loadCrawlM", it) }
                    r.loadCrawlClimbM?.let { put("loadCrawlClimbM", it) }
                    // Compact: one array per point, [timeMs, lat, lon, speed, bearing|null, accuracy], and since 4.7.2
                    // [.., altitude, its accuracy|null] on points that have one (older versions read the first six).
                    putJsonArray("points") {
                        t.points.forEach { p ->
                            add(
                                JsonArray(
                                    buildList {
                                        add(JsonPrimitive(p.timeMs)); add(JsonPrimitive(p.latitude)); add(JsonPrimitive(p.longitude))
                                        add(JsonPrimitive(p.speedMps)); add(p.bearingDeg?.let(::JsonPrimitive) ?: JsonNull)
                                        add(JsonPrimitive(p.accuracyM))
                                        p.altitudeM?.let {
                                            add(JsonPrimitive(it))
                                            add(p.altitudeAccuracyM?.let(::JsonPrimitive) ?: JsonNull)
                                        }
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }.toString()

    internal fun decode(text: String): List<TripWithTrack> = decodeAll(text).trips

    internal fun decodeAll(text: String): Contents {
        val root = Json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.long ?: 0
        require(format in 1..FORMAT) { "Backup format $format isn't supported by this version" }
        val garage = (root["garage"] as? JsonObject)?.let(GarageBackup::decode)
        return Contents(decodeTrips(root), garage)
    }

    private fun decodeTrips(root: JsonObject): List<TripWithTrack> {
        return root.getValue("trips").jsonArray.map { e ->
            val o = e.jsonObject
            fun long(k: String) = o.getValue(k).jsonPrimitive.long
            fun double(k: String) = o.getValue(k).jsonPrimitive.double
            fun text(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            val trip = TripRecord(
                startedAtMs = long("startedAtMs"),
                endedAtMs = long("endedAtMs"),
                durationMs = long("durationMs"),
                movingMs = long("movingMs"),
                stoppedMs = o["stoppedMs"]?.jsonPrimitive?.long ?: 0,
                distanceM = double("distanceM"),
                maxSpeedMps = o.getValue("maxSpeedMps").jsonPrimitive.float,
                startLat = double("startLat"),
                startLon = double("startLon"),
                endLat = double("endLat"),
                endLon = double("endLon"),
                startAddress = text("startAddress"),
                endAddress = text("endAddress"),
                track = text("track") ?: "",
                bikeId = text("bikeId"),
                loadIndex = o["loadIndex"]?.jsonPrimitive?.doubleOrNull,
                loadDistanceM = o["loadDistanceM"]?.jsonPrimitive?.doubleOrNull,
                loadAero = o["loadAero"]?.jsonPrimitive?.doubleOrNull,
                loadAccel = o["loadAccel"]?.jsonPrimitive?.doubleOrNull,
                loadClimbM = o["loadClimbM"]?.jsonPrimitive?.doubleOrNull,
                loadStops = o["loadStops"]?.jsonPrimitive?.intOrNull,
                loadCrawlS = o["loadCrawlS"]?.jsonPrimitive?.doubleOrNull,
                loadCrawlM = o["loadCrawlM"]?.jsonPrimitive?.doubleOrNull,
                loadCrawlClimbM = o["loadCrawlClimbM"]?.jsonPrimitive?.doubleOrNull,
            )
            val points = (o["points"] as? JsonArray).orEmpty().map { pe ->
                val a = pe.jsonArray
                TrackPointRecord(
                    tripId = 0,
                    timeMs = a[0].jsonPrimitive.long,
                    latitude = a[1].jsonPrimitive.double,
                    longitude = a[2].jsonPrimitive.double,
                    speedMps = a[3].jsonPrimitive.float,
                    bearingDeg = a[4].jsonPrimitive.floatOrNull,
                    accuracyM = a[5].jsonPrimitive.float,
                    altitudeM = a.getOrNull(6)?.jsonPrimitive?.floatOrNull,
                    altitudeAccuracyM = a.getOrNull(7)?.jsonPrimitive?.floatOrNull,
                )
            }
            TripWithTrack(trip, points)
        }
    }

    /**
     * Adds the backup's trips that aren't in the history yet (same start time = same trip), so
     * importing the same file twice changes nothing. Returns how many were added.
     */
    suspend fun restore(dao: TripDao, trips: List<TripWithTrack>): Int {
        val known = dao.observeAll().first().mapTo(HashSet()) { it.startedAtMs }
        var added = 0
        trips.forEach { t ->
            if (known.add(t.trip.startedAtMs)) {
                dao.insertWithTrack(t.trip.copy(id = 0), t.points)
                added++
            }
        }
        return added
    }
}
