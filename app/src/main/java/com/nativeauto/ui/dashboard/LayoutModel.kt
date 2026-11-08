package com.nativeauto.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

/** Everything that can fill a slot. Each pane exists once; a layout shows some of them. */
enum class Pane { MAP, MEDIA, SPEED, NAV, CLOCK }

/**
 * How a card draws itself, chosen from the slot it's in, never from its measured size (rule #6).
 * [Wide] is a full-width, half-height row (the Rows layout).
 */
enum class CardMode { Compact, Wide, Expanded, Fullscreen }

/**
 * The layouts in the switcher. Slots are numbered; [columnsOf] says where each one sits.
 * - [DUO]: one tall pane (0) beside two stacked ones (1 above 2). The original layout.
 * - [SIDE_BY_SIDE]: two tall panes, 0 left and 1 right.
 * - [ROWS]: two full-width rows, 0 above 1.
 * - [QUAD]: 2×2, read like a page: 0 1 / 2 3.
 */
enum class LayoutPreset(val slots: Int) { DUO(3), SIDE_BY_SIDE(2), ROWS(2), QUAD(4) }

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
}

/**
 * How a pane draws in a slot when nothing is maximized or fullscreen. A tall slot (Duo's big
 * one, Side by side) has the room of a maximized card, so it gets the Expanded content.
 */
internal fun naturalMode(preset: LayoutPreset, slot: Int): CardMode = when (preset) {
    LayoutPreset.DUO -> if (slot == 0) CardMode.Expanded else CardMode.Compact
    LayoutPreset.SIDE_BY_SIDE -> CardMode.Expanded
    LayoutPreset.ROWS -> CardMode.Wide
    LayoutPreset.QUAD -> CardMode.Compact
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

/** The default panes per layout. Quad matches the brainstorm's everyday set, with the clock until the Garage card exists. */
internal val DefaultAssignments: Map<LayoutPreset, List<Pane>> = mapOf(
    LayoutPreset.DUO to listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED),
    LayoutPreset.SIDE_BY_SIDE to listOf(Pane.SPEED, Pane.MEDIA),
    LayoutPreset.ROWS to listOf(Pane.SPEED, Pane.MEDIA),
    LayoutPreset.QUAD to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK),
)

/** Quad while navigating: map, next turn, speed, media (decided 2026-11-08). */
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
}
