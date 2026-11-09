package com.nativeauto.history

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GPX 1.1 for one trip (Rider-Feedback §5, 9d): read by Strava, Komoot, OsmAnd, Google Earth.
 * Trips recorded since 9c carry the full track: each point has its time, and speed (m/s) and
 * course (degrees) as `<extensions>`, which most readers pick up. Older trips only have the
 * simplified path, so their points have no time.
 */
fun buildGpx(trip: TripRecord, points: List<TrackPointRecord>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    append("""<gpx version="1.1" creator="Native Auto" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
    append("  <metadata><name>").append(escapeXml(gpxName(trip))).append("</name><time>")
        .append(isoTime(trip.startedAtMs)).append("</time></metadata>\n")
    append("  <trk>\n    <name>").append(escapeXml(gpxName(trip))).append("</name>\n    <trkseg>\n")
    if (points.isNotEmpty()) {
        points.forEach { p ->
            append("      <trkpt lat=\"").append(coord(p.latitude)).append("\" lon=\"").append(coord(p.longitude)).append("\">")
            append("<time>").append(isoTime(p.timeMs)).append("</time>")
            append("<extensions><speed>").append(number(p.speedMps.toDouble())).append("</speed>")
            p.bearingDeg?.let { append("<course>").append(number(it.toDouble())).append("</course>") }
            append("</extensions></trkpt>\n")
        }
    } else {
        Polyline.decode(trip.track).forEach { p ->
            append("      <trkpt lat=\"").append(coord(p.latitude)).append("\" lon=\"").append(coord(p.longitude)).append("\"/>\n")
        }
    }
    append("    </trkseg>\n  </trk>\n</gpx>\n")
}

/** e.g. "Ride 2026-11-09 08:15", plus the start address when known. Local time. */
internal fun gpxName(trip: TripRecord): String {
    val local = Instant.ofEpochMilli(trip.startedAtMs).atZone(java.time.ZoneId.systemDefault())
    val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(local)
    return "Ride $stamp" + (trip.startAddress?.let { " · $it" } ?: "")
}

/** A file name that sorts by date: "ride-2026-11-09-0815-<id>.gpx" (UTC, so it never collides on DST). */
internal fun gpxFileName(trip: TripRecord): String {
    val utc = Instant.ofEpochMilli(trip.startedAtMs).atZone(ZoneOffset.UTC)
    return "ride-" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT).format(utc) + "-${trip.id}.gpx"
}

internal fun isoTime(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

private fun coord(v: Double) = String.format(Locale.ROOT, "%.7f", v)

private fun number(v: Double) = String.format(Locale.ROOT, "%.2f", v)

internal fun escapeXml(s: String) = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
