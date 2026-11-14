package com.nativeauto.ui.dashboard

/**
 * Event-driven layout switching (no polling): it reacts to navigation starting and ending,
 * state changes the app already has, so it costs next to nothing.
 *
 * - **Navigation starts** (leaves idle: planning, preview, guidance): the map comes on screen.
 *   Quad switches to its navigation set ([NavQuad]: map, navigation, speed, media). Any other
 *   layout without a visible map switches to Duo with the map in it. A visible map is left alone.
 * - **Navigation ends:** the user's layout comes back, unless they changed the arrangement by
 *   hand in the meantime (that ended the override already; see [DashboardLayoutState]).
 * - **Manual wins:** no switch within [HOLD_MS] of the user arranging the layout by hand.
 *
 * Turned off in settings ("Auto layout"), it does nothing; an override already showing
 * still ends with the navigation.
 */
internal object AutoLayout {
    /** "Never switch again for a while after the user changes the layout by hand." */
    const val HOLD_MS = 2 * 60_000L

    fun onNavigationStarted(layout: DashboardLayoutState, enabled: Boolean) {
        if (!enabled) return
        if (layout.nowMs() - layout.lastArrangedAtMs < HOLD_MS) return
        navigationView(layout.userView, layout.assignments)?.let(layout::applyAuto)
    }

    fun onNavigationEnded(layout: DashboardLayoutState) = layout.clearAuto()

    /** The layout to show while navigating, or null when the user's already shows the map. */
    internal fun navigationView(user: LayoutView, assignments: Map<LayoutPreset, List<Pane>>): LayoutView? {
        if (user.preset == LayoutPreset.QUAD || user.preset == LayoutPreset.STACK4) {
            // 14b: the portrait four-stack takes the same navigation set, in reading order from the top.
            return LayoutView(user.preset, NavQuad).takeIf { it != user }
        }
        val mapShown = Pane.MAP in user.panes && (user.fullscreen == null || user.fullscreen == Pane.MAP) &&
            (user.maximized == null || user.maximized == Pane.MAP || !sharesColumn(user, Pane.MAP, user.maximized))
        if (mapShown) return null
        // Landscape: Duo; portrait (14b): the big pane over two, its counterpart.
        val target = if (user.preset.portrait) LayoutPreset.BIG_TWO else LayoutPreset.DUO
        val duo = assignments.getValue(target)
        // The map goes in the big slot when the layout doesn't hold it; whatever was there leaves.
        val panes = if (Pane.MAP in duo) duo else listOf(Pane.MAP) + duo.drop(1)
        return LayoutView(target, panes)
    }

    private fun sharesColumn(view: LayoutView, a: Pane, b: Pane): Boolean {
        val sa = view.panes.indexOf(a)
        val sb = view.panes.indexOf(b)
        if (sa < 0 || sb < 0) return false
        // Side doesn't matter for "same column"; Duo's split doesn't either.
        return columnsOf(view.preset, bigOnLeft = true, split = 0.5f).any { sa in it.slots && sb in it.slots }
    }
}
