package com.nativeauto.ui.components

import android.app.Activity
import android.view.Display
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.nativeauto.settings.RefreshMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** One display mode, as much of it as the choice needs. */
internal data class ModeSpec(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

/** The fastest and slowest modes at the current resolution (a resolution change would be a visible switch). */
internal data class RefreshTargets(val fast: Int, val low: Int)

/** Null when the display has a single rate at this resolution (the emulator): nothing to choose. */
internal fun refreshTargets(modes: List<ModeSpec>, current: ModeSpec): RefreshTargets? {
    val same = modes.filter { it.width == current.width && it.height == current.height }
    val fast = same.maxByOrNull { it.refreshHz } ?: return null
    val low = same.minByOrNull { it.refreshHz } ?: return null
    return if (fast.refreshHz - low.refreshHz < 0.5f) null else RefreshTargets(fast.id, low.id)
}

/**
 * 12a (Rider-Feedback R4), LTPO-style: any motion gets the fastest mode at once, in every setting,
 * so animations always look smooth. With nothing moving, [RefreshMode] decides: Auto hands the
 * choice back to the system (0), Power saver asks for the slowest mode, Smooth keeps the fastest.
 * The rate is only a hint to the display: the app still draws a frame only when something changed.
 */
internal fun preferredModeId(setting: RefreshMode, moving: Boolean, targets: RefreshTargets): Int = when {
    moving -> targets.fast
    setting == RefreshMode.SMOOTH -> targets.fast
    setting == RefreshMode.POWER_SAVER -> targets.low
    else -> 0
}

/** How long the fast rate lingers after the last motion, so back-to-back moves don't flip modes. */
internal fun lingerMs(setting: RefreshMode): Long = when (setting) {
    RefreshMode.AUTO -> 500L
    RefreshMode.POWER_SAVER -> 200L
    RefreshMode.SMOOTH -> 0L
}

/**
 * Who is moving right now, besides the panes' own slide: the map's camera, a card being held or
 * dragged. Sources set and clear their own key; [any] is read in a snapshot.
 */
@Stable
class MotionDemand {
    private val sources = mutableStateMapOf<String, Boolean>()

    fun set(source: String, moving: Boolean) {
        if (moving) sources[source] = true else sources.remove(source)
    }

    val any: Boolean get() = sources.isNotEmpty()
}

/** Provided by the dashboard; a fresh, unread one in isolation (previews, tests of one card). */
val LocalMotionDemand = staticCompositionLocalOf { MotionDemand() }

/**
 * Applies [preferredModeId] to the window while the dashboard is shown. [moving] is read in a
 * snapshot (panes sliding); [demand] adds the map's camera and drags. Phase 8d started this
 * (HyperOS holds unlisted apps at 60 Hz outside touch); 12a adds the setting and the idle rate.
 */
@Composable
fun AdaptiveRefreshRate(setting: RefreshMode, demand: MotionDemand, moving: () -> Boolean) {
    val activity = LocalActivity.current ?: return
    LaunchedEffect(activity, setting) {
        val targets = activity.refreshTargets() ?: return@LaunchedEffect
        snapshotFlow { moving() || demand.any }
            .distinctUntilChanged()
            .collectLatest { now ->
                if (!now) delay(lingerMs(setting))
                activity.setPreferredMode(preferredModeId(setting, now, targets))
            }
    }
}

private fun Activity.refreshTargets(): RefreshTargets? {
    // The window's display (API 17+), so this works on every supported version, not only 30+.
    val display: Display = window.decorView.display ?: return null
    fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
    return refreshTargets(display.supportedModes.map { it.spec() }, display.mode.spec())
}

private fun Activity.setPreferredMode(id: Int) {
    val attrs = window.attributes
    if (attrs.preferredDisplayModeId == id) return
    attrs.preferredDisplayModeId = id
    window.attributes = attrs
}
