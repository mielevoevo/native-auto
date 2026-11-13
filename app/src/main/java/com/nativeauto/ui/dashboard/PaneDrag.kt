package com.nativeauto.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 12a (Rider-Feedback R3) + 13a two-step feedback: hold a card to lift it, drag it onto another
 * card to swap the two, or keep holding without moving to choose what the slot shows.
 *
 * Two stages, each with its own buzz:
 *  1. **Lifted** (after the hold time): the card scales up, its outline lights, one buzz. Moving
 *     drags it; letting go without moving does nothing, the card settles back.
 *  2. **Armed** ([ARM_MS] more without moving): a second ring fills in the card's centre, the card
 *     dims a little, a double buzz. Letting go now opens the pane picker. Moving at any time drops
 *     back to dragging, so a drag can't open the picker by accident.
 *
 * The gesture only *watches* touches until the hold completes (pointer pass Initial, nothing
 * consumed), so a tap, a swipe, a scroll or a button press inside the card works as before; any
 * movement past touch slop before then is the card's own gesture and cancels the hold. Once
 * lifted, it takes every touch until the finger is up.
 *
 * The swap itself is [DashboardLayoutState.assign], which already slides both panes to their new
 * rectangles; nothing is recreated (rule #3).
 */
@Stable
class PaneDragState internal constructor(private val scope: CoroutineScope) {
    /** The card being held (the ring is filling). */
    var holding by mutableStateOf<Pane?>(null); private set

    /** The lifted card, following the finger. */
    var lifted by mutableStateOf<Pane?>(null); private set

    /** The lifted card has been held still long enough: letting go opens the picker. */
    var armed by mutableStateOf(false); private set

    /** The card the lifted one would swap with if dropped now. */
    var target by mutableStateOf<Pane?>(null); private set

    /** Where the finger is, in root pixels (the ring's centre). */
    var finger by mutableStateOf(Offset.Zero); private set

    /** 0..1 while holding: the ring. */
    val holdProgress = Animatable(0f)

    /** 0..1 while lifted and still: the second ring, in the card's centre. */
    val armProgress = Animatable(0f)

    /** The lifted (or settling) card's offset from its slot, in pixels. */
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)

    /** The card sliding back from where it was dropped. */
    var settling by mutableStateOf<Pane?>(null); private set

    /** Anything to animate: the refresh rate follows this. */
    val active: Boolean get() = holding != null || lifted != null || settling != null

    /** Screen size in pixels, for turning a finger position into the pane under it. */
    internal var screen: Size = Size.Zero

    private var ring: Job? = null
    private var armTimer: Job? = null
    private var dragging = false

    internal fun startHold(pane: Pane, at: Offset, holdMs: Long) {
        holding = pane
        finger = at
        ring?.cancel()
        ring = scope.launch {
            holdProgress.snapTo(0f)
            // A quick tap shouldn't flash a ring: it starts filling a moment in.
            delay(RING_DELAY_MS)
            holdProgress.animateTo(1f, tween((holdMs - RING_DELAY_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
    }

    internal fun cancelHold() {
        ring?.cancel()
        holding = null
        scope.launch { holdProgress.snapTo(0f) }
    }

    /** The hold completed. [onArm] runs when the card has then been held still for [ARM_MS]. */
    internal fun lift(pane: Pane, onArm: () -> Unit) {
        ring?.cancel()
        holding = null
        lifted = pane
        armed = false
        dragging = false
        settling = null
        armTimer?.cancel()
        armTimer = scope.launch {
            holdProgress.snapTo(0f)
            offset.snapTo(Offset.Zero)
            armProgress.snapTo(0f)
            armProgress.animateTo(1f, tween(ARM_MS.toInt(), easing = LinearEasing))
            if (lifted == pane) {
                armed = true
                onArm()
            }
        }
    }

    /** Moving drops back to plain dragging: the picker is no longer armed. */
    private fun disarm() {
        armTimer?.cancel()
        armed = false
        scope.launch { armProgress.snapTo(0f) }
    }

    internal fun move(delta: Offset, fingerAt: Offset, layout: DashboardLayoutState) {
        finger = fingerAt
        if (!dragging) {
            dragging = true
            disarm()
        }
        scope.launch { offset.snapTo(delta) }
        val pane = lifted
        target = if (pane == null || screen.width <= 0f || screen.height <= 0f) null else {
            val home = layout.rectOf(pane)
            val shift = Offset(delta.x / screen.width, delta.y / screen.height)
            val others = Pane.entries.filter { it != pane && layout.isOnScreen(it) }.associateWith { layout.rectOf(it) }
            pickDropTarget(
                liftedRect = home.translate(shift),
                homeRect = home,
                finger = Offset(fingerAt.x / screen.width, fingerAt.y / screen.height),
                others = others,
            )
        }
    }

    /** Finger up after a drag. Returns the pane swapped with, if any. */
    internal fun drop(layout: DashboardLayoutState): Pane? {
        val pane = lifted ?: return null
        val swapWith = target
        if (swapWith != null) layout.assign(pane, swapWith)
        clearLift()
        settling = pane
        // Its slot moves to the target's rectangle with the panes' spring; the offset springs back
        // to zero with the same spring, so the card glides from under the finger into place.
        scope.launch {
            offset.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMediumLow))
            if (settling == pane) settling = null
        }
        return swapWith
    }

    /** Lifted and let go without moving (armed or not). */
    internal fun release() {
        clearLift()
        scope.launch { offset.snapTo(Offset.Zero) }
    }

    private fun clearLift() {
        armTimer?.cancel()
        lifted = null
        armed = false
        target = null
        scope.launch { armProgress.snapTo(0f) }
    }

    /** The on-screen pane whose target rectangle holds [at] (root pixels). */
    internal fun paneAt(at: Offset, layout: DashboardLayoutState): Pane? {
        if (screen.width <= 0f || screen.height <= 0f) return null
        val f = Offset(at.x / screen.width, at.y / screen.height)
        return Pane.entries.firstOrNull { p -> layout.isOnScreen(p) && layout.rectOf(p).containsInclusive(f) }
    }

    companion object {
        const val RING_DELAY_MS = 150L

        /** Held still this long after the lift, the picker is armed. */
        const val ARM_MS = 700L

        /** The share of the smaller card that must be covered for an overlap to pick a target. */
        const val OVERLAP_THRESHOLD = 0.2f
    }
}

private fun Rect.containsInclusive(o: Offset) = o.x >= left && o.x <= right && o.y >= top && o.y <= bottom

private fun Rect.areaOrZero() = if (width <= 0f || height <= 0f) 0f else width * height

/**
 * 13a: which card a dragged one would swap with. All rectangles are fractions of the screen.
 *  1. The card the lifted one covers most, if that is at least [PaneDragState.OVERLAP_THRESHOLD]
 *     of the smaller of the two (dropping a card straight onto its neighbour works).
 *  2. Otherwise the card under the finger.
 *  3. Otherwise the nearest card by centre, but only once the lifted card has travelled at least
 *     half the way to it (a small nudge never swaps).
 */
internal fun pickDropTarget(liftedRect: Rect, homeRect: Rect, finger: Offset, others: Map<Pane, Rect>): Pane? {
    if (others.isEmpty()) return null
    val liftedArea = liftedRect.areaOrZero()
    val byOverlap = others.entries
        .map { (pane, r) ->
            val overlap = liftedRect.intersect(r).areaOrZero()
            val share = overlap / minOf(liftedArea, r.areaOrZero()).coerceAtLeast(1e-6f)
            Triple(pane, overlap, share)
        }
        .filter { it.third >= PaneDragState.OVERLAP_THRESHOLD }
        .maxByOrNull { it.second }
    if (byOverlap != null) return byOverlap.first
    others.entries.firstOrNull { it.value.containsInclusive(finger) }?.let { return it.key }
    val nearest = others.entries.minBy { (it.value.center - liftedRect.center).getDistance() }
    val travelled = (liftedRect.center - homeRect.center).getDistance()
    val toNearest = (nearest.value.center - homeRect.center).getDistance()
    return if (travelled >= toNearest / 2f) nearest.key else null
}

/**
 * The hold-to-lift gesture on one pane's slot. [allowAt] limits where it may start (a fullscreen
 * map keeps its own long-press for destinations, so it lifts from its header). [onLift] is called
 * when the card lifts (haptics), [onArm] when it is armed for the picker, [onPick] when it is let
 * go armed, [onSwap] after a swap.
 */
internal fun Modifier.paneDrag(
    pane: Pane,
    state: PaneDragState,
    layout: DashboardLayoutState,
    holdMs: () -> Long,
    allowAt: (Offset) -> Boolean,
    onLift: () -> Unit,
    onArm: () -> Unit,
    onPick: (Pane) -> Unit,
    onSwap: () -> Unit,
): Modifier {
    var origin = Offset.Zero
    return this
        .onPlaced { origin = it.positionInRoot() }
        .pointerInput(pane, state, layout) {
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!allowAt(down.position)) return@awaitEachGesture
                    val hold = holdMs()
                    state.startHold(pane, origin + down.position, hold)
                    // Wait out the hold; a lift, a second finger or a move past slop gives the touch back.
                    val stillDown = withTimeoutOrNull(hold) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (!change.pressed || event.changes.size > 1) return@withTimeoutOrNull false
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    } == null
                    if (!stillDown) {
                        state.cancelHold()
                        return@awaitEachGesture
                    }

                    state.lift(pane, onArm)
                    onLift()
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - down.position
                        if (!moved && delta.getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved) state.move(delta, origin + change.position, layout)
                    }
                    when {
                        moved -> if (state.drop(layout) != null) onSwap()
                        state.armed -> {
                            state.release()
                            onPick(pane)
                        }
                        else -> state.release()
                    }
                }
            }
        }
}
