package com.nativeauto.history

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
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
 * apps. Import reads only `backup.json`. The Garage joins the JSON in 11c ([FORMAT] goes up then).
 *
 * Plain JVM code (java.util.zip + the kotlinx JSON tree), so it's unit-tested without a device.
 */
object Backup {
    const val FORMAT = 1
    const val JSON_ENTRY = "backup.json"

    class TripWithTrack(val trip: TripRecord, val points: List<TrackPointRecord>)

    fun write(trips: List<TripWithTrack>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(encode(trips).toByteArray())
            zip.closeEntry()
            trips.forEach { t ->
                zip.putNextEntry(ZipEntry("gpx/" + gpxFileName(t.trip)))
                zip.write(buildGpx(t.trip, t.points).toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** The trips in a backup, or an exception (not a backup, or a newer format) with a reason. */
    fun read(input: InputStream): List<TripWithTrack> {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == JSON_ENTRY) return decode(zip.readBytes().decodeToString())
            }
        }
        throw IllegalArgumentException("Not a Native Auto backup (no $JSON_ENTRY)")
    }

    internal fun encode(trips: List<TripWithTrack>): String = buildJsonObject {
        put("format", FORMAT)
        put("app", "Native Auto")
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
                    // Compact: one array per point, [timeMs, lat, lon, speed, bearing|null, accuracy].
                    putJsonArray("points") {
                        t.points.forEach { p ->
                            add(
                                JsonArray(
                                    listOf(
                                        JsonPrimitive(p.timeMs), JsonPrimitive(p.latitude), JsonPrimitive(p.longitude),
                                        JsonPrimitive(p.speedMps), p.bearingDeg?.let(::JsonPrimitive) ?: JsonNull,
                                        JsonPrimitive(p.accuracyM),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }.toString()

    internal fun decode(text: String): List<TripWithTrack> {
        val root = Json.parseToJsonElement(text).jsonObject
        val format = root["format"]?.jsonPrimitive?.long ?: 0
        require(format in 1..FORMAT) { "Backup format $format isn't supported by this version" }
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
