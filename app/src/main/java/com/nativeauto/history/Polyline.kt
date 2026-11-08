package com.nativeauto.history

import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.haversineMeters
import kotlin.math.cos
import kotlin.math.roundToLong

/** Google's encoded polyline format, precision 5 (~1 m): compact text for a trip's path. */
object Polyline {

    fun encode(points: List<GeoPoint>): String = buildString {
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.latitude * 1e5).roundToLong()
            val lon = (p.longitude * 1e5).roundToLong()
            writeValue(lat - lastLat)
            writeValue(lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
    }

    fun decode(encoded: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0L
        var lon = 0L
        fun readValue(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = encoded[i++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            lat += readValue()
            lon += readValue()
            out += GeoPoint(lat / 1e5, lon / 1e5)
        }
        return out
    }

    private fun StringBuilder.writeValue(value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        append((v.toInt() + 63).toChar())
    }
}

/**
 * Douglas–Peucker: drops points that deviate less than [toleranceM] from the line through
 * their neighbours. A 1 Hz hour-long trip (3,600 fixes) keeps a few hundred.
 */
fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (points.size < 3) return points
    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.lastIndex] = true
    val stack = ArrayDeque<IntRange>()
    stack.addLast(0..points.lastIndex)
    while (stack.isNotEmpty()) {
        val r = stack.removeLast()
        if (r.last - r.first < 2) continue
        var maxD = 0.0
        var maxI = -1
        for (k in r.first + 1 until r.last) {
            val d = distanceToSegmentM(points[k], points[r.first], points[r.last])
            if (d > maxD) { maxD = d; maxI = k }
        }
        if (maxD > toleranceM) {
            keep[maxI] = true
            stack.addLast(r.first..maxI)
            stack.addLast(maxI..r.last)
        }
    }
    return points.filterIndexed { i, _ -> keep[i] }
}

/** Local flat-earth approximation: plenty for segments of a few km. */
private fun distanceToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.latitude))
    val bx = (b.longitude - a.longitude) * mPerDegLon
    val by = (b.latitude - a.latitude) * mPerDegLat
    val px = (p.longitude - a.longitude) * mPerDegLon
    val py = (p.latitude - a.latitude) * mPerDegLat
    val len2 = bx * bx + by * by
    if (len2 == 0.0) return haversineMeters(p.latitude, p.longitude, a.latitude, a.longitude)
    val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
