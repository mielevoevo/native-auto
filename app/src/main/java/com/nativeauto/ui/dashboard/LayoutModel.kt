package com.nativeauto.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/** Everything that can fill a slot. Each pane exists once; a layout shows some of them. */
enum class Pane {
    MAP, MEDIA, SPEED, NAV, CLOCK, GARAGE,

    /** 14a: the call card. Never chosen by the rider: it takes over a slot while a call rings or is on ([withCall]). */
    CALL,
}

/**
 * How a card draws itself, chosen from the slot it's in, never from its measured size (rule #6).
 * [Wide] is a full-width, half-height row (the Rows layout).
 */
enum class CardMode {
    Compact, Wide, Expanded, Fullscreen,

    /** 14b: a portrait 4-stack slot (about 85 dp tall): one line of essentials, title-only header. */
    Mini,

    /** 14b: a portrait half-width slot under the big pane ([LayoutPreset.BIG_TWO]): about 190 dp wide. */
    Narrow,
}

/**
 * The layouts in the switcher. Slots are numbered; [columnsOf] says where each one sits.
 * - [DUO]: one tall pane (0) beside two stacked ones (1 above 2). The original layout.
 * - [SIDE_BY_SIDE]: two tall panes, 0 left and 1 right.
 * - [ROWS]: two full-width rows, 0 above 1.
 * - [QUAD]: 2×2, read like a page: 0 1 / 2 3.
 * - Portrait (14b): [LayoutPreset.STACK1]..[LayoutPreset.STACK4] are full-width rows, 0 on top;
 *   [LayoutPreset.BIG_TWO] is slot 0 over slots 1 and 2 side by side ([BIG_TWO_TOP] of the height for slot 0).
 */
enum class LayoutPreset(val slots: Int, val portrait: Boolean = false) {
    DUO(3), SIDE_BY_SIDE(2), ROWS(2), QUAD(4),

    /** 14b portrait (vertically mounted phone): 1 to 4 panes stacked, or one big pane over two side by side. */
    STACK1(1, true), STACK2(2, true), STACK3(3, true), BIG_TWO(3, true), STACK4(4, true),
}

/** One column of the layout: its share of the width, and its slots from top to bottom. */
internal data class LayoutColumn(val weight: Float, val slots: List<Int>)

/** Duo's tall column takes [split] of the width; every other layout splits evenly. */
internal fun columnsOf(preset: LayoutPreset, bigOnLeft: Boolean, split: Float): List<LayoutColumn> = when (preset) {
    LayoutPreset.DUO -> {
        val big = LayoutColumn(split, listOf(0))
        val stack = LayoutColumn(1f - split, listOf(1, 2))
        if (bigOnLeft) listOf(big, stack) else listOf(stack, big)
    }
    LayoutPreset.SIDE_BY_SIDE -> listOf(LayoutColumn(0.5f, listOf(0)), LayoutColumn(0.5f, listOf(1)))
    LayoutPreset.ROWS -> listOf(LayoutColumn(1f, listOf(0, 1)))
    LayoutPreset.QUAD -> listOf(LayoutColumn(0.5f, listOf(0, 2)), LayoutColumn(0.5f, listOf(1, 3)))
    // Portrait: one column of rows. BIG_TWO's real geometry is in [portraitRects]; this is only "who shares a column".
    LayoutPreset.STACK1, LayoutPreset.STACK2, LayoutPreset.STACK3, LayoutPreset.BIG_TWO, LayoutPreset.STACK4 ->
        listOf(LayoutColumn(1f, (0 until preset.slots).toList()))
}

/** Portrait [LayoutPreset.BIG_TWO]: the big pane's share of the height. */
internal const val BIG_TWO_TOP = 0.55f

/**
 * How a pane draws in a slot when nothing is maximized or fullscreen. A tall slot (Duo's big
 * one, Side by side) has the room of a maximized card, so it gets the Expanded content.
 */
internal fun naturalMode(preset: LayoutPreset, slot: Int): CardMode = when (preset) {
    LayoutPreset.DUO -> if (slot == 0) CardMode.Expanded else CardMode.Compact
    LayoutPreset.SIDE_BY_SIDE -> CardMode.Expanded
    LayoutPreset.ROWS -> CardMode.Wide
    LayoutPreset.QUAD -> CardMode.Compact
    // Portrait: one or two panes have the room of a tall card; three stacked are full-width rows; four are Mini.
    LayoutPreset.STACK1, LayoutPreset.STACK2 -> CardMode.Expanded
    LayoutPreset.STACK3 -> CardMode.Wide
    LayoutPreset.BIG_TWO -> if (slot == 0) CardMode.Expanded else CardMode.Narrow
    LayoutPreset.STACK4 -> CardMode.Mini
}

/** What's on screen: the layout, which pane is in each slot, and the zoom modes. */
@Immutable
data class LayoutView(
    val preset: LayoutPreset,
    val panes: List<Pane>,
    val fullscreen: Pane? = null,
    val maximized: Pane? = null,
)

/** Off screen: zero width at the left edge. Panes that leave a layout shrink there, and grow from there. */
internal val ParkedRect = Rect(0f, 0f, 0f, 1f)

/**
 * Each pane's place as fractions of the screen (rule #1: derived from intent, never stored).
 * Like the handoff's weight table, generalised: a fullscreen pane's column takes the whole
 * width and the pane its whole column; a maximized pane takes its whole column. Panes the
 * layout doesn't show are parked at [ParkedRect]. Zero-size results are fine here; the
 * layout clamps and clips them.
 */
internal fun computeRects(view: LayoutView, bigOnLeft: Boolean, split: Float): Map<Pane, Rect> {
    val out = HashMap<Pane, Rect>(Pane.entries.size)
    Pane.entries.forEach { out[it] = ParkedRect }
    if (view.preset.portrait) return portraitRects(view, out)
    val columns = columnsOf(view.preset, bigOnLeft, split)
    val slotOf = view.panes.withIndex().associate { (i, p) -> p to i }
    val full = view.fullscreen?.let { slotOf[it] }
    val max = view.maximized?.let { slotOf[it] }

    val colWeights = if (full != null) columns.map { if (full in it.slots) 1f else 0f } else columns.map { it.weight }
    val total = colWeights.sum().takeIf { it > 0f } ?: 1f
    var x = 0f
    columns.forEachIndexed { c, column ->
        val width = colWeights[c] / total
        val focus = full?.takeIf { it in column.slots } ?: max?.takeIf { it in column.slots }
        val heights = column.slots.map { s -> if (focus == null) 1f / column.slots.size else if (s == focus) 1f else 0f }
        var y = 0f
        column.slots.forEachIndexed { k, slot ->
            val pane = view.panes.getOrNull(slot) ?: return@forEachIndexed
            out[pane] = Rect(x, y, x + width, y + heights[k])
            y += heights[k]
        }
        x += width
    }
    return out
}

/**
 * 14b: portrait geometry. Stacks split the height evenly; [LayoutPreset.BIG_TWO] puts slot 0 over slots 1 and 2.
 * No maximize here (a stack's column is the whole screen already): a fullscreen pane takes everything, and the
 * others fold into the screen's top or bottom edge as zero-height rows, so they slide away instead of vanishing.
 */
private fun portraitRects(view: LayoutView, out: HashMap<Pane, Rect>): Map<Pane, Rect> {
    val panes = view.panes
    val full = view.fullscreen?.let { panes.indexOf(it) }?.takeIf { it >= 0 }
    val n = panes.size
    // x range and y range of each slot, unzoomed
    fun slotRect(slot: Int): Rect = when {
        view.preset == LayoutPreset.BIG_TWO -> when (slot) {
            0 -> Rect(0f, 0f, 1f, BIG_TWO_TOP)
            1 -> Rect(0f, BIG_TWO_TOP, 0.5f, 1f)
            else -> Rect(0.5f, BIG_TWO_TOP, 1f, 1f)
        }
        else -> Rect(0f, slot.toFloat() / n, 1f, (slot + 1).toFloat() / n)
    }
    panes.forEachIndexed { slot, pane ->
        val r = slotRect(slot)
        out[pane] = when {
            full == null -> r
            slot == full -> Rect(0f, 0f, 1f, 1f)
            // Above the fullscreen slot (or, in BIG_TWO, the big one) folds to the top edge, the rest to the bottom.
            slot < full && view.preset != LayoutPreset.BIG_TWO || (view.preset == LayoutPreset.BIG_TWO && slot == 0) ->
                Rect(r.left, 0f, r.right, 0f)
            else -> Rect(r.left, 1f, r.right, 1f)
        }
    }
    return out
}

/**
 * The default panes per layout. Quad keeps the clock: the Garage card (11c) is shown on demand
 * (Rider-Feedback §6), picked for a slot with a header long-press.
 */
internal val DefaultAssignments: Map<LayoutPreset, List<Pane>> = mapOf(
    LayoutPreset.DUO to listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED),
    LayoutPreset.SIDE_BY_SIDE to listOf(Pane.SPEED, Pane.MEDIA),
    LayoutPreset.ROWS to listOf(Pane.SPEED, Pane.MEDIA),
    LayoutPreset.QUAD to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK),
    LayoutPreset.STACK1 to listOf(Pane.SPEED),
    LayoutPreset.STACK2 to listOf(Pane.SPEED, Pane.MAP),
    LayoutPreset.STACK3 to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA),
    LayoutPreset.BIG_TWO to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA),
    LayoutPreset.STACK4 to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK),
)

/** Quad while navigating: map, navigation, speed, media (decided 2026-11-08). */
internal val NavQuad = listOf(Pane.MAP, Pane.NAV, Pane.SPEED, Pane.MEDIA)

/**
 * Slot assignments as text for DataStore: `DUO=MAP,MEDIA,SPEED;QUAD=MAP,SPEED,MEDIA,CLOCK`.
 * Decoding never fails: an unknown, duplicated or wrong-sized entry falls back to that
 * layout's default, so a damaged or older preferences file still gives a usable screen.
 */
internal object LayoutCodec {
    fun encode(assignments: Map<LayoutPreset, List<Pane>>): String =
        LayoutPreset.entries.mapNotNull { p -> assignments[p]?.let { "${p.name}=${it.joinToString(",") { pane -> pane.name }}" } }
            .joinToString(";")

    fun decode(text: String): Map<LayoutPreset, List<Pane>> {
        val parsed = text.split(';').mapNotNull { entry ->
            val (name, list) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val preset = LayoutPreset.entries.firstOrNull { it.name == name.trim() } ?: return@mapNotNull null
            val panes = list.split(',').map { s -> Pane.entries.firstOrNull { it.name == s.trim() } ?: return@mapNotNull null }
            if (panes.size != preset.slots || panes.toSet().size != panes.size) return@mapNotNull null
            preset to panes
        }.toMap()
        return LayoutPreset.entries.associateWith { parsed[it] ?: DefaultAssignments.getValue(it) }
    }

    fun preset(name: String): LayoutPreset = LayoutPreset.entries.firstOrNull { it.name == name } ?: LayoutPreset.DUO

    /** A saved portrait preset, or null (blank, unknown, or a landscape name). */
    fun portraitPreset(name: String): LayoutPreset? = LayoutPreset.entries.firstOrNull { it.name == name && it.portrait }
}

/**
 * 14a: the order in which a call takes a pane's slot, first to go first. Media is the least useful during a call
 * (the call mutes it anyway), the clock and the Garage next, then speed, then the Navigation pane; the map is
 * the last to give way.
 */
internal val CallTakeoverOrder = listOf(Pane.MEDIA, Pane.CLOCK, Pane.GARAGE, Pane.SPEED, Pane.NAV, Pane.MAP)

/**
 * The pane the call card replaces in [view], or null when it must not: a fullscreen pane is never taken over
 * (the rider chose it; the call notification still tells them), and only panes that have room on screen
 * (not folded away by a maximized neighbour) count.
 */
internal fun callTakeoverPane(view: LayoutView, bigOnLeft: Boolean, split: Float): Pane? {
    if (view.fullscreen != null) return null
    val rects = computeRects(view, bigOnLeft, split)
    val onScreen = view.panes.filter { p -> rects.getValue(p).let { it.width > 0f && it.height > 0f } }
    return CallTakeoverOrder.firstOrNull { it in onScreen } ?: onScreen.firstOrNull()
}

/** [view] with the call card in [replaced]'s slot (a zoom on that pane moves to the call card). */
internal fun LayoutView.withCall(replaced: Pane?): LayoutView {
    if (replaced == null || replaced !in panes) return this
    return copy(
        panes = panes.map { if (it == replaced) Pane.CALL else it },
        fullscreen = if (fullscreen == replaced) Pane.CALL else fullscreen,
        maximized = if (maximized == replaced) Pane.CALL else maximized,
    )
}


/**
 * 14b: what a rotation shows. The panes keep their count and order (2 landscape cards = 2 stacked, Duo = a stack of
 * three), so the rider sees the same cards; only the geometry changes. Where an orientation has two shapes for a
 * count (3: stack or "big + two"; 2: side by side or rows), the one the rider last used there wins ([lastPortrait],
 * [lastLandscape]). A maximized pane has no portrait equivalent and is dropped; a fullscreen one stays.
 * Landscape has no single-pane layout, so one pane gets a neighbour from [fillFrom].
 */
internal fun rotatedView(
    view: LayoutView,
    toPortrait: Boolean,
    lastPortrait: LayoutPreset? = null,
    lastLandscape: LayoutPreset? = null,
    fillFrom: List<Pane> = listOf(Pane.MEDIA, Pane.SPEED, Pane.MAP),
): LayoutView {
    if (view.preset.portrait == toPortrait) return view
    var panes = view.panes
    val preset = if (toPortrait) when (panes.size) {
        1 -> LayoutPreset.STACK1
        2 -> LayoutPreset.STACK2
        3 -> lastPortrait?.takeIf { it.portrait && it.slots == 3 } ?: LayoutPreset.STACK3
        else -> LayoutPreset.STACK4
    } else when (panes.size) {
        1 -> {
            panes = panes + fillFrom.first { it !in panes }
            LayoutPreset.SIDE_BY_SIDE
        }
        2 -> lastLandscape?.takeIf { !it.portrait && it.slots == 2 } ?: LayoutPreset.SIDE_BY_SIDE
        3 -> LayoutPreset.DUO
        else -> LayoutPreset.QUAD
    }
    return LayoutView(preset, panes, view.fullscreen?.takeIf { it in panes }, maximized = null)
}