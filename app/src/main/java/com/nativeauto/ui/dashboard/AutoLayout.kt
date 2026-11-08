package com.nativeauto.ui.dashboard

/**
 * Event-driven layout switching (no polling): it reacts to navigation starting and ending,
 * state changes the app already has, so it costs next to nothing.
 *
 * - **Navigation starts** (leaves idle: planning, preview, guidance): the map comes on screen.
 *   Quad switches to its navigation set ([NavQuad]: map, next turn, speed, media). Any other
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
        if (user.preset == LayoutPreset.QUAD) {
            return LayoutView(LayoutPreset.QUAD, NavQuad).takeIf { it != user }
        }
        val mapShown = Pane.MAP in user.panes && (user.fullscreen == null || user.fullscreen == Pane.MAP) &&
            (user.maximized == null || user.maximized == Pane.MAP || !sharesColumn(user, Pane.MAP, user.maximized))
        if (mapShown) return null
        val duo = assignments.getValue(LayoutPreset.DUO)
        // The map goes in the tall slot when Duo doesn't hold it; whatever was there leaves.
        val panes = if (Pane.MAP in duo) duo else listOf(Pane.MAP) + duo.drop(1)
        return LayoutView(LayoutPreset.DUO, panes)
    }

    private fun sharesColumn(view: LayoutView, a: Pane, b: Pane): Boolean {
        val sa = view.panes.indexOf(a)
        val sb = view.panes.indexOf(b)
        if (sa < 0 || sb < 0) return false
        // Side doesn't matter for "same column"; Duo's split doesn't either.
        return columnsOf(view.preset, bigOnLeft = true, split = 0.5f).any { sa in it.slots && sb in it.slots }
    }
}
