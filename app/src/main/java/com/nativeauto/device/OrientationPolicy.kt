package com.nativeauto.device

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 14b: tuning for Auto orientation. These are first guesses, deliberately generous, so a phone propped on a mount and
 * leaning a few degrees never turns the app; the rider tunes them on the bike after release (DECISIONS.md, 14b).
 */
object OrientationTuning {
    /**
     * The phone must lean within this many degrees of an axis before the app follows: within 30 degrees of upright to go
     * portrait, within 30 of sideways to go landscape. In between, nothing changes (a 45 degree lean is "undecided").
     */
    const val ZONE_DEG = 30f

    /** The new orientation must hold this long without a break before the app turns (bumps and glances don't count). */
    const val STEADY_MS = 1_500L

    /** Below this much gravity in the screen's plane the phone lies flat, and its roll means nothing (m/s²). */
    const val MIN_IN_PLANE = 3f
}

/**
 * Decides when Auto orientation turns. Pure: feed it gravity as the sensor reports it (x to the screen's right, y to its
 * top, in m/s²) and a clock. [onSample] returns the orientation to switch to, or null to stay.
 *
 * The in-plane angle is 0 with the phone upright, ±90 on its side, 180 upside down; "portrait" covers 0 and 180 (the
 * app doesn't care which way up), "landscape" covers ±90.
 */
class OrientationPolicy(
    private val zoneDeg: Float = OrientationTuning.ZONE_DEG,
    private val steadyMs: Long = OrientationTuning.STEADY_MS,
    private val minInPlane: Float = OrientationTuning.MIN_IN_PLANE,
) {
    private var candidate: Boolean? = null
    private var since = 0L

    /** Forget a pending turn (the mode changed, or the app was hidden). */
    fun reset() { candidate = null }

    /** @return true = turn to portrait, false = turn to landscape, null = stay as [currentPortrait]. */
    fun onSample(ax: Float, ay: Float, atMs: Long, currentPortrait: Boolean): Boolean? {
        val wanted = wantedPortrait(ax, ay)
        if (wanted == null || wanted == currentPortrait) {
            candidate = null
            return null
        }
        if (candidate != wanted) {
            candidate = wanted
            since = atMs
            return null
        }
        return if (atMs - since >= steadyMs) { candidate = null; wanted } else null
    }

    /** The orientation the lean asks for, or null when it is flat or in the undecided band. */
    internal fun wantedPortrait(ax: Float, ay: Float): Boolean? {
        if (hypot(ax, ay) < minInPlane) return null
        val angle = abs(Math.toDegrees(atan2(ax, ay).toDouble())).toFloat() // 0..180
        val fromPortraitAxis = minOf(angle, 180f - angle) // 0 upright or upside down .. 90 on its side
        return when {
            fromPortraitAxis <= zoneDeg -> true
            fromPortraitAxis >= 90f - zoneDeg -> false
            else -> null
        }
    }
}
