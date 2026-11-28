package com.nativeauto.history

import com.nativeauto.nav.GeoPoint
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.haversineMeters

/**
 * 4.8: a trip's route in shades by speed, for its detail map. Fixed steps (the same on every trip, so trips compare), the
 * speed averaged over ~[SMOOTH_M] of road so a single fix doesn't speckle the line.
 */
const val SMOOTH_M = 100.0

/** Where each step starts, in [unit]; the last one is open ended. */
fun speedSteps(unit: SpeedUnit): List<Int> = if (unit == SpeedUnit.MPH) listOf(0, 15, 30, 45, 60, 75) else listOf(0, 20, 40, 60, 80, 100)

/** A stretch of road at one step. Neighbours share their end point, so the line has no gaps. */
data class SpeedBand(val step: Int, val points: List<GeoPoint>)

/** The route cut into stretches. Empty when there is no track to shade (a trip from before 9c). */
fun speedBands(track: List<TrackPointRecord>, unit: SpeedUnit): List<SpeedBand> {
    // Standing points say nothing about the road and would only pile up.
    val points = ArrayList<TrackPointRecord>(track.size)
    track.forEach { p ->
        val last = points.lastOrNull()
        if (last == null || haversineMeters(last.latitude, last.longitude, p.latitude, p.longitude) >= MIN_STEP_M) points += p
    }
    if (points.size < 2) return emptyList()
    val n = points.size - 1
    val lengths = DoubleArray(n) { haversineMeters(points[it].latitude, points[it].longitude, points[it + 1].latitude, points[it + 1].longitude) }
    val speeds = DoubleArray(n) { (points[it].speedMps + points[it + 1].speedMps) / 2.0 }
    val mids = DoubleArray(n)
    var along = 0.0
    for (i in 0 until n) {
        mids[i] = along + lengths[i] / 2
        along += lengths[i]
    }
    val limits = speedSteps(unit).map { it / unit.perMps.toDouble() }
    val bands = ArrayList<SpeedBand>()
    var from = 0
    var to = 0
    var sum = 0.0
    var length = 0.0
    var open = ArrayList<GeoPoint>()
    var openStep = -1
    for (i in 0 until n) {
        while (to < n && mids[to] <= mids[i] + SMOOTH_M / 2) {
            sum += speeds[to] * lengths[to]
            length += lengths[to]
            to++
        }
        while (mids[from] < mids[i] - SMOOTH_M / 2) {
            sum -= speeds[from] * lengths[from]
            length -= lengths[from]
            from++
        }
        val v = if (length > 0) sum / length else speeds[i]
        val step = limits.indexOfLast { v >= it - 1e-6 }.coerceAtLeast(0)
        if (step != openStep) {
            if (open.isNotEmpty()) bands += SpeedBand(openStep, open)
            open = arrayListOf(points[i].point())
            openStep = step
        }
        open += points[i + 1].point()
    }
    bands += SpeedBand(openStep, open)
    return bands
}

private fun TrackPointRecord.point() = GeoPoint(latitude, longitude)

private const val MIN_STEP_M = 3.0

/** How far each step is mixed toward black (negative) or white (positive) from the accent: faster is brighter on black, darker on a light map. */
private val DARK_MIX = listOf(-0.55, -0.38, -0.2, 0.1, 0.45, 0.82)
private val LIGHT_MIX = listOf(0.5, 0.25, 0.0, -0.28, -0.52, -0.76)

/** The steps' colors (opaque ARGB), slowest first. */
fun speedShades(accentArgb: Int, dark: Boolean): List<Int> = (if (dark) DARK_MIX else LIGHT_MIX).map { mix ->
    fun channel(shift: Int): Int {
        val c = (accentArgb shr shift) and 0xFF
        val v = if (mix < 0) c * (1 + mix) else c + (255 - c) * mix
        return v.toInt().coerceIn(0, 255)
    }
    (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
