package com.nativeauto.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * Stores layout *intent* only (rule #1): the [preset], which pane sits in each slot of every
 * preset, the zoom modes ([fullscreen], [maximized]) and Duo's side and width. Each pane's
 * place ([rectOf]) and drawing mode ([modeOf]) are derived.
 *
 * **Auto layout** ([applyAuto] / [clearAuto]) shows a temporary [LayoutView] on top of the
 * user's own, without touching it, so it can be put back exactly. The user always wins:
 * - zooming (fullscreen, maximize, Back) works on whatever is shown and keeps the override;
 * - changing the arrangement (layout, panes, swaps, map width) first makes the shown layout
 *   the user's own, ending the override, so nothing is "restored" over their change later.
 */
@Stable
class DashboardLayoutState(
    preset: LayoutPreset = LayoutPreset.DUO,
    assignments: Map<LayoutPreset, List<Pane>> = DefaultAssignments,
    fullscreen: Pane? = null,
    maximized: Pane? = null,
    bigOnLeft: Boolean = true,
    split: Float = 0.5f,
    override: LayoutView? = null,
    lastArrangedAtMs: Long = Long.MIN_VALUE / 2,
    portrait: Boolean = false,
    lastLandscapePreset: LayoutPreset? = null,
    lastPortraitPreset: LayoutPreset? = null,
    private val clock: () -> Long = ::monotonicMs,
) {
    // As with split: the private setter would clash with setPreset(LayoutPreset) on the JVM.
    var preset by mutableStateOf(preset); @JvmName("assignPreset") private set
    var assignments by mutableStateOf(DefaultAssignments + assignments); private set
    private var baseFullscreen by mutableStateOf(fullscreen)
    private var baseMaximized by mutableStateOf(maximized)
    /** Duo: the tall pane is on the left. */
    var bigOnLeft by mutableStateOf(bigOnLeft); private set
    // The setter's JVM name would clash with setSplit(Float) below; rename it on the JVM only.
    var split by mutableFloatStateOf(split); @JvmName("assignSplit") private set

    /**
     * 14b: the screen is portrait (the phone is mounted upright). Set from the window's orientation, never saved with
     * the arrangement (it follows the phone). [setPortrait] swaps the preset for its counterpart.
     */
    var portrait by mutableStateOf(portrait); @JvmName("assignPortrait") private set

    /** Each orientation remembers the preset it last showed (the current one is [preset]); null = never used. */
    var lastLandscapePreset by mutableStateOf(lastLandscapePreset); private set
    var lastPortraitPreset by mutableStateOf(lastPortraitPreset); private set

    /** The auto layout on screen, or null when the user's own layout shows. */
    var override by mutableStateOf(override); private set

    /** When the user last changed the arrangement by hand ([monotonicMs]); auto layout holds off after it. */
    var lastArrangedAtMs by mutableLongStateOf(lastArrangedAtMs); private set

    /** What's on screen. `this.` everywhere: the constructor parameters would shadow the properties. */
    /** 14a: a call is ringing or on: the call card takes the lowest-priority slot ([callTakeoverPane]), never a fullscreen one. */
    var callActive by mutableStateOf(false); @JvmName("assignCallActive") private set

    fun setCallActive(active: Boolean) { callActive = active }

    /** What the rider arranged (or auto layout shows), before any call card. */
    private val baseView: LayoutView by derivedStateOf {
        this.override ?: LayoutView(this.preset, this.assignments.getValue(this.preset), this.baseFullscreen, this.baseMaximized)
    }

    val view: LayoutView by derivedStateOf {
        val b = this.baseView
        if (this.callActive) b.withCall(callTakeoverPane(b, this.bigOnLeft, this.split)) else b
    }

    val fullscreen: Pane? get() = view.fullscreen
    val maximized: Pane? get() = view.maximized

    private val rects: Map<Pane, Rect> by derivedStateOf { computeRects(this.view, this.bigOnLeft, this.split) }

    /** Where [pane] goes, as fractions of the screen. Panes the layout doesn't show are parked at zero width. */
    fun rectOf(pane: Pane): Rect = rects.getValue(pane)

    /** Takes up any room at all (the target, not mid-animation). */
    fun isOnScreen(pane: Pane): Boolean = rectOf(pane).let { it.width > 0f && it.height > 0f }

    /**
     * The pane in the screen's top-right corner. Its header carries the status corner
     * (battery, layouts, settings), where a phone's status bar battery would be.
     */
    val statusPane: Pane by derivedStateOf {
        Pane.entries.firstOrNull { p -> rects.getValue(p).let { it.width > 0f && it.height > 0f && it.contains(TOP_RIGHT) } }
            ?: Pane.entries.first { isOnScreen(it) }
    }

    fun modeOf(pane: Pane): CardMode {
        val v = view
        val slot = v.panes.indexOf(pane)
        return when {
            v.fullscreen == pane && slot >= 0 -> CardMode.Fullscreen
            v.fullscreen != null || slot < 0 -> CardMode.Compact // hidden
            v.maximized == pane -> CardMode.Expanded
            v.maximized != null && v.preset.portrait -> CardMode.Mini
            else -> naturalMode(v.preset, slot)
        }
    }

    /**
     * A pane can be maximized when it shares its column (it then takes the whole column). In portrait (4.1.1) only with three or
     * more panes: it takes the space of two cards and the others become slim rows. With two panes fullscreen does the same, so
     * there is no button; the big pane of "big + two" has nothing to grow into.
     */
    fun canMaximize(pane: Pane): Boolean =
        if (view.preset.portrait) view.panes.size >= 3 && pane in view.panes &&
            !(view.preset == LayoutPreset.BIG_TWO && view.panes.first() == pane)
        else columnOf(pane)?.let { it.slots.size > 1 } ?: false

    /** Its swap trades places with the pane above or below; otherwise it moves the whole column across. */
    fun swapsVertically(pane: Pane): Boolean = view.preset.portrait || canMaximize(pane)

    /**
     * 13a: the header's swap button exists only where it is the clearest way to rearrange: the two
     * panes of Side by side and Rows, and Duo's tall pane. Quad and Duo's stacked panes rearrange
     * by holding a card (drag, or let go to pick); a fullscreen pane has no swap.
     */
    fun canSwap(pane: Pane): Boolean {
        val v = view
        if (v.fullscreen != null) return false
        val slot = v.panes.indexOf(pane)
        if (slot < 0) return false
        return when (v.preset) {
            LayoutPreset.SIDE_BY_SIDE, LayoutPreset.ROWS -> true
            LayoutPreset.DUO -> slot == 0
            LayoutPreset.QUAD -> false
            // Portrait: two stacked panes trade places; the other stacks rearrange by drag and the picker.
            LayoutPreset.STACK2 -> true
            LayoutPreset.STACK1, LayoutPreset.STACK3, LayoutPreset.BIG_TWO, LayoutPreset.STACK4 -> false
        }
    }

    private fun columnOf(pane: Pane): LayoutColumn? {
        val slot = view.panes.indexOf(pane).takeIf { it >= 0 } ?: return null
        return columnsOf(view.preset, bigOnLeft, split).first { slot in it.slots }
    }

    // ---- zoom: works on what's shown, keeps an override ----

    fun toggleFullscreen(pane: Pane) {
        if (pane !in view.panes) return
        zoom(fullscreenPane = if (view.fullscreen == pane) null else pane, maximizedPane = view.maximized)
    }

    fun toggleMaximized(pane: Pane) {
        if (!canMaximize(pane)) return
        zoom(fullscreenPane = view.fullscreen, maximizedPane = if (view.maximized == pane) null else pane)
    }

    /** Returns true if it consumed the back press. */
    fun back(): Boolean = when {
        view.fullscreen != null -> { zoom(null, view.maximized); true }
        view.maximized != null -> { zoom(null, null); true }
        else -> false
    }

    private fun zoom(fullscreenPane: Pane?, maximizedPane: Pane?) {
        // The call card stands in for a pane (14a): a zoom on it is a zoom on that pane underneath.
        val fullscreen = real(fullscreenPane)
        val maximized = real(maximizedPane)
        val o = override
        if (o != null) {
            override = o.copy(fullscreen = fullscreen, maximized = maximized)
        } else {
            baseFullscreen = fullscreen
            baseMaximized = maximized
        }
    }

    /** The pane the call card currently stands in for, when [pane] is the call card; otherwise [pane]. */
    private fun real(pane: Pane?): Pane? =
        if (pane == Pane.CALL) callTakeoverPane(baseView, bigOnLeft, split) ?: pane else pane

    /** [pane] as it may be stored: the call card's own pane, or null when the call card isn't on screen (the call just ended). */
    private fun arranged(pane: Pane): Pane? =
        if (pane == Pane.CALL && Pane.CALL !in view.panes) null else real(pane)?.takeIf { it != Pane.CALL }

    // ---- arrangement: the user's own, ends an override ----

    /** One tap in the switcher. Starts unzoomed: a new layout is a fresh view. A layout of the other orientation is ignored. */
    fun setPreset(preset: LayoutPreset) {
        if (preset.portrait != portrait) return
        arrangeByHand()
        this.preset = preset
        baseFullscreen = null
        baseMaximized = null
    }

    /**
     * The pane picker: show [pane] in [slotPane]'s slot. If [pane] is already elsewhere in
     * this layout the two trade places; otherwise [slotPane] leaves the layout.
     */
    fun assign(slotPane: Pane, pane: Pane) {
        // 4.7.1: the call card is only a stand-in. It is never stored: moving it moves the pane underneath.
        val slotPane = arranged(slotPane) ?: return
        val pane = arranged(pane) ?: return
        if (slotPane == pane) return
        arrangeByHand()
        val list = assignments.getValue(preset).toMutableList()
        val i = list.indexOf(slotPane).takeIf { it >= 0 } ?: return
        val j = list.indexOf(pane)
        if (j >= 0) list[j] = slotPane
        list[i] = pane
        assignments = assignments + (preset to list)
        if (slotPane !in list) {
            if (baseFullscreen == slotPane) baseFullscreen = null
            if (baseMaximized == slotPane) baseMaximized = null
        }
    }

    /** The header's swap button: with the pane above / below, or (alone in its column) across. */
    fun swap(pane: Pane) {
        val pane = arranged(pane) ?: return
        arrangeByHand()
        val list = assignments.getValue(preset)
        val slot = list.indexOf(pane).takeIf { it >= 0 } ?: return
        val column = columnsOf(preset, bigOnLeft, split).first { slot in it.slots }
        when {
            column.slots.size > 1 -> {
                val other = column.slots.first { it != slot }
                assignments = assignments + (preset to list.swapped(slot, other))
            }
            preset == LayoutPreset.DUO -> bigOnLeft = !bigOnLeft
            preset == LayoutPreset.SIDE_BY_SIDE -> assignments = assignments + (preset to list.swapped(0, 1))
        }
    }

    fun setSplit(fraction: Float) {
        arrangeByHand()
        split = fraction.coerceIn(MIN_SPLIT, MAX_SPLIT)
    }

    private fun arrangeByHand() {
        override?.let { o ->
            preset = o.preset
            assignments = assignments + (o.preset to o.panes)
            baseFullscreen = o.fullscreen
            baseMaximized = o.maximized
            override = null
        }
        lastArrangedAtMs = clock()
    }

    // ---- orientation (14b) ----

    /**
     * The phone turned: show the counterpart of the current layout ([rotatedView]), same panes in the same order,
     * and remember the preset left behind for its own orientation. Not an arrangement by hand: auto layout's hold
     * timer is untouched, and an auto-layout override is rotated too.
     */
    fun setPortrait(portrait: Boolean) {
        if (portrait == this.portrait) return
        if (this.portrait) lastPortraitPreset = preset else lastLandscapePreset = preset
        val shown = userView
        val turned = rotatedView(shown, portrait, lastPortraitPreset, lastLandscapePreset)
        this.portrait = portrait
        preset = turned.preset
        assignments = assignments + (turned.preset to turned.panes)
        baseFullscreen = turned.fullscreen
        baseMaximized = null
        override = override?.let { rotatedView(it, portrait, lastPortraitPreset, lastLandscapePreset) }
    }

    // ---- auto layout ----

    fun applyAuto(view: LayoutView) { override = view }

    fun clearAuto() { override = null }

    /** The user's own layout, whatever an override shows on top of it. */
    val userView: LayoutView
        get() = LayoutView(preset, assignments.getValue(preset), baseFullscreen, baseMaximized)

    fun nowMs(): Long = clock()

    companion object {
        private val TOP_RIGHT = androidx.compose.ui.geometry.Offset(0.999f, 0.001f)

        val Saver = listSaver<DashboardLayoutState, Any?>(
            save = {
                listOf(
                    it.preset.name,
                    LayoutCodec.encode(it.assignments),
                    it.baseFullscreen?.name,
                    it.baseMaximized?.name,
                    it.bigOnLeft,
                    it.split,
                    it.override?.let(::encodeView),
                    it.lastArrangedAtMs,
                    it.portrait,
                    it.lastLandscapePreset?.name,
                    it.lastPortraitPreset?.name,
                )
            },
            restore = {
                DashboardLayoutState(
                    preset = LayoutCodec.preset(it[0] as String),
                    assignments = LayoutCodec.decode(it[1] as String),
                    fullscreen = (it[2] as String?)?.let(Pane::valueOf),
                    maximized = (it[3] as String?)?.let(Pane::valueOf),
                    bigOnLeft = it[4] as Boolean,
                    split = it[5] as Float,
                    override = (it[6] as String?)?.let(::decodeView),
                    lastArrangedAtMs = it[7] as Long,
                    portrait = it[8] as Boolean,
                    lastLandscapePreset = (it[9] as String?)?.let { n -> LayoutPreset.entries.firstOrNull { p -> p.name == n } },
                    lastPortraitPreset = (it[10] as String?)?.let { n -> LayoutPreset.entries.firstOrNull { p -> p.name == n } },
                )
            },
        )

        private fun encodeView(v: LayoutView) =
            listOf(v.preset.name, v.panes.joinToString(",") { it.name }, v.fullscreen?.name.orEmpty(), v.maximized?.name.orEmpty()).joinToString("|")

        private fun decodeView(s: String): LayoutView? = runCatching {
            val (preset, panes, full, max) = s.split('|')
            LayoutView(
                LayoutPreset.valueOf(preset),
                panes.split(',').map(Pane::valueOf).withoutCall(LayoutPreset.valueOf(preset)),
                full.takeIf { it.isNotEmpty() }?.let(Pane::valueOf),
                max.takeIf { it.isNotEmpty() }?.let(Pane::valueOf),
            )
        }.getOrNull()
    }
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> = toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }

/** Monotonic milliseconds (CLOCK_MONOTONIC on Android): immune to wall-clock changes. */
internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

@Composable
fun rememberDashboardLayoutState() =
    rememberSaveable(saver = DashboardLayoutState.Saver) { DashboardLayoutState() }
