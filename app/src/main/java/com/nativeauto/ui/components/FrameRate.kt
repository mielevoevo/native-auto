package com.nativeauto.ui.components

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.nativeauto.ui.dashboard.Pane
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** One display mode, as much of it as the choice needs. */
internal data class ModeSpec(val id: Int, val width: Int, val height: Int, val refreshHz: Float)

/**
 * The mode to ask for while animating: the highest refresh rate at the current resolution (a
 * resolution change would be a visible mode switch). Null if no mode beats the current one.
 */
internal fun highRefreshMode(modes: List<ModeSpec>, current: ModeSpec): ModeSpec? =
    modes.filter { it.width == current.width && it.height == current.height }
        .maxByOrNull { it.refreshHz }
        ?.takeIf { it.refreshHz > current.refreshHz + 0.5f }

/** How long the high rate is kept after the last move, so back-to-back moves don't flip modes. */
internal const val HIGH_RATE_LINGER_MS = 500L

/**
 * Phase 8d (Rider-Feedback §12.3): HyperOS can hold apps that aren't on its list at 60 Hz outside
 * touch, so a maximize or layout switch animated at 60. While any pane moves, the window asks for
 * the display's fastest mode; [HIGH_RATE_LINGER_MS] after the last move it goes back to the
 * system's choice (0), which saves power: the dashboard's data changes once a second.
 */
@Composable
fun HighRefreshWhileMoving(motion: (Pane) -> Boolean) {
    val activity = LocalActivity.current ?: return
    LaunchedEffect(activity) {
        val fast = activity.fastModeId() ?: return@LaunchedEffect
        snapshotFlow { Pane.entries.any(motion) }
            .distinctUntilChanged()
            .collectLatest { moving ->
                if (moving) {
                    activity.setPreferredMode(fast)
                } else {
                    delay(HIGH_RATE_LINGER_MS)
                    activity.setPreferredMode(0)
                }
            }
    }
}

private fun Activity.fastModeId(): Int? {
    val display: Display = displayCompat ?: return null
    fun Display.Mode.spec() = ModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)
    return highRefreshMode(display.supportedModes.map { it.spec() }, display.mode.spec())?.id
}

private fun Activity.setPreferredMode(id: Int) {
    val attrs = window.attributes
    if (attrs.preferredDisplayModeId == id) return
    attrs.preferredDisplayModeId = id
    window.attributes = attrs
}

/** `Activity.display` needs API 30; below that the hint is skipped (the phone runs API 35). */
private val Activity.displayCompat: Display?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
