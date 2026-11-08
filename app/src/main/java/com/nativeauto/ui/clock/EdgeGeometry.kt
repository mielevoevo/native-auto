package com.nativeauto.ui.clock

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A point on the card's edge and the edge's outward normal there. */
internal data class EdgeHit(val point: Offset, val normal: Offset)

/**
 * The edge-tick face's geometry (8c). The dial is the rounded rectangle itself (centred on the
 * origin, half-sizes [halfW] × [halfH], corner [radius]; y grows downwards), and [fraction] of
 * a turn is that fraction of the way **along its edge**, clockwise from the top centre. So the
 * 60 ticks are evenly spaced on any shape of pane (spacing them by angle would crowd them
 * around 12 and 6 on a wide one), and a hand pointing at `edgeAt(minute / 60)` lands exactly
 * on its tick. Ticks are drawn inward along the normal: square to a straight edge, fanning out
 * in a corner.
 */
internal fun edgeAt(fraction: Float, halfW: Float, halfH: Float, radius: Float): EdgeHit {
    val r = radius.coerceIn(0f, min(halfW, halfH))
    val sx = halfW - r // half a straight horizontal side
    val sy = halfH - r // half a straight vertical side
    val arc = (PI / 2 * r).toFloat()
    val perimeter = 4 * sx + 4 * sy + 4 * arc
    var d = ((fraction % 1f) + 1f) % 1f * perimeter

    // Walk the edge clockwise from the top centre: straight, corner, straight, …
    fun corner(cx: Float, cy: Float, startDeg: Float, along: Float): EdgeHit {
        val a = Math.toRadians((startDeg + 90f * (if (arc > 0f) along / arc else 0f)).toDouble())
        val n = Offset(cos(a).toFloat(), sin(a).toFloat())
        return EdgeHit(Offset(cx, cy) + n * r, n)
    }
    if (d <= sx) return EdgeHit(Offset(d, -halfH), Offset(0f, -1f)); d -= sx
    if (d <= arc) return corner(sx, -sy, -90f, d); d -= arc
    if (d <= 2 * sy) return EdgeHit(Offset(halfW, -sy + d), Offset(1f, 0f)); d -= 2 * sy
    if (d <= arc) return corner(sx, sy, 0f, d); d -= arc
    if (d <= 2 * sx) return EdgeHit(Offset(sx - d, halfH), Offset(0f, 1f)); d -= 2 * sx
    if (d <= arc) return corner(-sx, sy, 90f, d); d -= arc
    if (d <= 2 * sy) return EdgeHit(Offset(-halfW, sy - d), Offset(-1f, 0f)); d -= 2 * sy
    if (d <= arc) return corner(-sx, -sy, 180f, d); d -= arc
    return EdgeHit(Offset(-sx + d, -halfH), Offset(0f, -1f))
}
