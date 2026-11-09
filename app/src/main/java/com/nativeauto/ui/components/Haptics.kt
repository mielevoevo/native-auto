package com.nativeauto.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * One vocabulary of haptics for the whole app (Phase 7), so a rider can feel a press land
 * through gloves without looking. The system's touch-feedback setting still applies.
 */
@Stable
class Haptics(private val feedback: HapticFeedback) {
    /** Something switched on or off: maximize, fullscreen, traffic, a settings switch. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** A plain button: swap, skip, play/pause, recenter. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.VirtualKey)

    /** One option of a segmented choice picked. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A deliberate, consequential action completed (trip reset). */
    /** A card lifts to be dragged (12a). */
    fun lift() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
