package com.nativeauto.ui.dashboard

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.settings.ClockFace
import com.nativeauto.settings.SavedLayout
import com.nativeauto.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Smoke tests on the real activity.
 * Phase 1: every mode renders, hidden slots collapse with no stray gutter, Back unwinds,
 * state survives recreation. Phase 2: card content per mode, tap / long-press gestures.
 * Phase 7: the full mode-switching suite. Phase 8b: layout presets, the pane picker, and the
 * clock and Next turn panes. Phase 8c: theme switch, clock faces, corner clock.
 */
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    /** Granted before launch, so PermissionsGate goes straight to tracking instead of a dialog. */
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()

    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    // Cards are clickable, which merges their descendants' semantics, so content lookups
    // go through the unmerged tree.
    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true)
    private fun click(t: String) { tag(t).performClick(); rule.waitForIdle() }
    /** Off-centre: since 9a the idle card's centre holds the Start trip button. */
    private fun tapSpeedCardEdge() {
        rule.onNodeWithTag("pane_SPEED").performTouchInput { click(percentOffset(0.1f, 0.85f)) }
        rule.waitForIdle()
    }
    private fun back() { Espresso.pressBack(); rule.waitForIdle() }
    private fun SemanticsNodeInteraction.width(): Dp = getBoundsInRoot().let { it.right - it.left }
    private fun SemanticsNodeInteraction.height(): Dp = getBoundsInRoot().let { it.bottom - it.top }
    private fun SemanticsNodeInteraction.fetchText(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun rootWidth() = rule.onRoot().width()
    private fun rootHeight() = rule.onRoot().height()

    private fun assertMode(pane: Pane, mode: CardMode) {
        rule.onNodeWithTag("pane_${pane.name}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, mode.name))
    }

    private fun assertNear(message: String, expected: Dp, actual: Dp, tolerance: Dp = 1.dp) =
        assertTrue("$message: expected $expected but was $actual", abs((expected - actual).value) <= tolerance.value)

    private fun slot(pane: Pane) = tag(SlotTag.of(pane))

    /** A pane the layout doesn't show is a zero-area slot (no stray gutter). */
    private fun assertCollapsed(message: String, pane: Pane) {
        val b = slot(pane).getBoundsInRoot()
        val w = b.right - b.left
        val h = b.bottom - b.top
        assertTrue("$message: $pane is $w x $h", w.value < 0.5f || h.value < 0.5f)
    }

    /** Duo, unzoomed: the tall slot is roomy (Expanded), the stacked ones Compact. */
    private fun assertDuoModes() {
        assertMode(Pane.MAP, CardMode.Expanded)
        assertMode(Pane.MEDIA, CardMode.Compact)
        assertMode(Pane.SPEED, CardMode.Compact)
    }

    private fun awaitSheet(sheet: String) =
        rule.waitUntil(5_000) { count(sheet).fetchSemanticsNodes().isNotEmpty() }

    private fun pickFromSheet(item: String, sheet: String) {
        click(item)
        rule.waitUntil(5_000) { count(sheet).fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
    }

    private fun switchLayout(preset: LayoutPreset) {
        click("layouts_open")
        awaitSheet("layouts_sheet")
        pickFromSheet("preset_${preset.name}", "layouts_sheet")
    }

    private fun openPicker(pane: Pane) {
        // On the title side: the header's centre can be one of its buttons (the status corner).
        tag("header_${pane.name}").performTouchInput { longClick(Offset(width * 0.1f, height / 2f)) }
        awaitSheet("picker_sheet")
    }

    private val telemetryTags =
        listOf("stat_max", "stat_avg", "stat_moving_avg", "stat_elapsed", "stat_distance", "stat_gps")

    // ---------------------------------------------------------------- Phase 1: layout

    @Test fun defaultLayoutSplitsScreenInHalf() {
        assertDuoModes()
        assertNear("map half width", rootWidth() / 2, slot(Pane.MAP).width())
        assertNear("map full height", rootHeight(), slot(Pane.MAP).height())
        assertNear("media half width", rootWidth() / 2, slot(Pane.MEDIA).width())
        assertNear("media half height", rootHeight() / 2, slot(Pane.MEDIA).height())
        assertCollapsed("not in Duo", Pane.NAV)
        assertCollapsed("not in Duo", Pane.CLOCK)
    }

    @Test fun mapFullscreenLeavesNoGutter() {
        click("fullscreen_MAP")
        assertMode(Pane.MAP, CardMode.Fullscreen)
        assertNear("map slot fills width", rootWidth(), slot(Pane.MAP).width())
        assertCollapsed("cards column", Pane.MEDIA)
        assertCollapsed("cards column", Pane.SPEED)
    }

    @Test fun cardFullscreenLeavesNoGutter() {
        for (pane in listOf(Pane.MEDIA, Pane.SPEED)) {
            click("fullscreen_${pane.name}")
            assertMode(pane, CardMode.Fullscreen)
            assertCollapsed("map", Pane.MAP)
            assertNear("$pane slot fills width", rootWidth(), slot(pane).width())
            assertNear("$pane slot fills height", rootHeight(), slot(pane).height())
            assertCollapsed("other card", if (pane == Pane.MEDIA) Pane.SPEED else Pane.MEDIA)
            back()
        }
    }

    @Test fun maximizeFillsColumnAndBackUnwinds() {
        click("maximize_SPEED")
        assertMode(Pane.SPEED, CardMode.Expanded)
        assertNear("speed fills column", rootHeight(), slot(Pane.SPEED).height())
        assertCollapsed("media", Pane.MEDIA)

        click("fullscreen_SPEED")
        assertMode(Pane.SPEED, CardMode.Fullscreen)

        back()
        assertMode(Pane.SPEED, CardMode.Expanded)

        back()
        assertMode(Pane.SPEED, CardMode.Compact)
        assertNear("media restored", rootHeight() / 2, slot(Pane.MEDIA).height())
    }

    @Test fun swapsReorderSlots() {
        click("swap_MAP")
        assertTrue("map moved right", slot(Pane.MAP).getBoundsInRoot().left > slot(Pane.MEDIA).getBoundsInRoot().left)
        click("swap_MEDIA")
        assertTrue("speed moved up", slot(Pane.SPEED).getBoundsInRoot().top < slot(Pane.MEDIA).getBoundsInRoot().top)
    }

    @Test fun stateSurvivesRecreation() {
        click("swap_MAP")
        click("maximize_MEDIA")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        assertMode(Pane.MEDIA, CardMode.Expanded)
        assertTrue("map still on the right", slot(Pane.MAP).getBoundsInRoot().left > slot(Pane.MEDIA).getBoundsInRoot().left)
        assertCollapsed("speed still", Pane.SPEED)
    }

    // ---------------------------------------------------------------- Phase 2: card content

    @Test fun speedCompactShowsOnlyTheBigSpeed() {
        assertMode(Pane.SPEED, CardMode.Compact)
        tag("speed_hero").assertIsDisplayed()
        // Live data: a whole number, or "--" while there is no fresh fix.
        val value = tag("speed_value").fetchText()
        assertTrue("speed shows '$value'", value == "--" || value.toIntOrNull() != null)
        telemetryTags.forEach { count(it).assertCountEquals(0) }
    }

    @Test fun speedExpandedAndFullscreenShowTheTelemetryGrid() {
        click("maximize_SPEED")
        assertMode(Pane.SPEED, CardMode.Expanded)
        tag("speed_hero").assertIsDisplayed()
        telemetryTags.forEach { tag(it).assertIsDisplayed() }

        click("fullscreen_SPEED")
        assertMode(Pane.SPEED, CardMode.Fullscreen)
        tag("speed_hero").assertIsDisplayed()
        telemetryTags.forEach { tag(it).assertIsDisplayed() }
    }

    // Media content and controls: MediaCardTest (Phase 6, against a real media session).

    // ---------------------------------------------------------------- Phase 7

    private fun openSettings() {
        click("settings_open")
        awaitSheet("settings_sheet")
    }

    private fun closeSettings() {
        click("settings_done")
        rule.waitUntil(5_000) { count("settings_sheet").fetchSemanticsNodes().isEmpty() }
    }

    private fun SemanticsNodeInteraction.contains(inner: SemanticsNodeInteraction): Boolean {
        val o = getBoundsInRoot()
        val i = inner.getBoundsInRoot()
        return i.left >= o.left && i.right <= o.right && i.top >= o.top && i.bottom <= o.bottom
    }

    /**
     * The mode-switching smoke test (§9 Phase 7), in Duo: every side × card order × mode.
     * Each must show the expected modes, give the focused pane its space, and put exactly one
     * status corner (battery + layouts + settings) in the top-right pane.
     */
    @Test fun everyLayoutCombinationRendersWithTheStatusCornerTopRight() {
        val modes: List<Pair<String, () -> Unit>> = listOf(
            "normal" to {},
            "media maximized" to { click("maximize_MEDIA") },
            "speed maximized" to { click("maximize_SPEED") },
            "map fullscreen" to { click("fullscreen_MAP") },
            "media fullscreen" to { click("fullscreen_MEDIA") },
            "speed fullscreen" to { click("fullscreen_SPEED") },
        )
        for (mapOnLeft in listOf(true, false)) for (mediaOnTop in listOf(true, false)) {
            if (mapOnLeft != (slot(Pane.MAP).getBoundsInRoot().left < slot(Pane.MEDIA).getBoundsInRoot().left)) click("swap_MAP")
            if (mediaOnTop != (slot(Pane.MEDIA).getBoundsInRoot().top < slot(Pane.SPEED).getBoundsInRoot().top)) click("swap_MEDIA")

            for ((name, enter) in modes) {
                val label = "map ${if (mapOnLeft) "left" else "right"}, media ${if (mediaOnTop) "top" else "bottom"}, $name"
                enter()
                val focused = when (name) {
                    "media maximized", "media fullscreen" -> Pane.MEDIA
                    "speed maximized", "speed fullscreen" -> Pane.SPEED
                    "map fullscreen" -> Pane.MAP
                    else -> null
                }
                if (name.endsWith("fullscreen")) {
                    assertMode(focused!!, CardMode.Fullscreen)
                    assertNear("$label: fills width", rootWidth(), slot(focused).width())
                } else if (focused != null) {
                    assertMode(focused, CardMode.Expanded)
                    assertNear("$label: fills column", rootHeight(), slot(focused).height())
                } else {
                    assertDuoModes()
                }

                val expectedCorner = when {
                    focused != null && name.endsWith("fullscreen") -> focused
                    !mapOnLeft -> Pane.MAP
                    focused != null -> focused
                    mediaOnTop -> Pane.MEDIA
                    else -> Pane.SPEED
                }
                count("settings_open").assertCountEquals(1)
                count("layouts_open").assertCountEquals(1)
                assertTrue("$label: status corner in $expectedCorner", tag("pane_${expectedCorner.name}").contains(tag("settings_open")))
                tag("battery_indicator").assertIsDisplayed()

                repeat(2) { layoutBack() } // fullscreen → maximized → normal at most
            }
        }
    }

    /** Back until the layout is back to normal (never past it: that would leave the app). */
    private fun layoutBack(): Boolean {
        fun modeOf(p: Pane) =
            rule.onNodeWithTag("pane_${p.name}").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val anyFocused = modeOf(Pane.MAP) == CardMode.Fullscreen.name ||
            listOf(Pane.MEDIA, Pane.SPEED).any { modeOf(it) != CardMode.Compact.name }
        if (anyFocused) back()
        return anyFocused
    }

    @Test fun layoutChangesAreSavedForTheNextLaunch() {
        click("swap_MAP")
        click("swap_MEDIA")
        openSettings()
        click("settings_split_60")
        closeSettings()
        val expected = SavedLayout(
            bigOnLeft = false,
            split = 0.6f,
            preset = "DUO",
            slots = LayoutCodec.encode(DefaultAssignments + (LayoutPreset.DUO to listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA))),
        )
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.savedLayout.first() } == expected }
        assertNear("map is 60% wide", rootWidth() * 0.6f, slot(Pane.MAP).width())
        // Modes are session-only: maximizing doesn't touch what's saved.
        click("maximize_SPEED")
        assertEquals(expected, runBlocking { resetSettings.settings.savedLayout.first() })

        switchLayout(LayoutPreset.QUAD)
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.savedLayout.first() }.preset == "QUAD" }
    }

    @Test fun batteryIndicatorCanBeHiddenAndShownAgain() {
        tag("battery_indicator").assertIsDisplayed()
        openSettings()
        click("settings_battery")
        closeSettings()
        count("battery_indicator").assertCountEquals(0)
        tag("settings_open").assertIsDisplayed() // the way back into settings stays

        openSettings()
        click("settings_battery")
        closeSettings()
        rule.waitUntil(5_000) { count("battery_indicator").fetchSemanticsNodes().size == 1 }
    }

    @Test fun speedUnitSwitchesTheSpeedCard() {
        rule.onNodeWithText("km/h", useUnmergedTree = true).assertIsDisplayed()
        openSettings()
        click("settings_unit_MPH")
        closeSettings()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("mph", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("km/h", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aTripStartsOnlyOnStartAndResetDiscardsIt() {
        // 9a: opening the app starts nothing.
        rule.onNodeWithText("Start trip", useUnmergedTree = true).assertIsDisplayed()
        click("hero_action")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Start trip", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        openSettings()
        click("settings_reset_trip")
        click("settings_reset_trip")
        closeSettings()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Start trip", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun autoPauseAndAutoEndAreSettings() {
        openSettings()
        click("settings_auto_pause_10")
        click("settings_auto_end_0")
        tag("settings_auto_pause_10").assertIsSelected()
        tag("settings_auto_end_0").assertIsSelected()
        closeSettings()
    }

    @Test fun resetTripAsksForASecondTap() {
        openSettings()
        click("settings_reset_trip")
        rule.onNodeWithText("Tap again to reset", useUnmergedTree = true).assertIsDisplayed()
        click("settings_reset_trip")
        rule.onNodeWithText("Reset trip", useUnmergedTree = true).assertIsDisplayed()
        closeSettings()
    }

    // ---------------------------------------------------------------- Phase 2: gestures

    @Test fun tappingACardTogglesMaximize() {
        tapSpeedCardEdge()
        assertMode(Pane.SPEED, CardMode.Expanded)
        tapSpeedCardEdge()
        assertMode(Pane.SPEED, CardMode.Compact)
    }

    @Test fun longPressTogglesFullscreen() {
        rule.onNodeWithTag("pane_MEDIA").performTouchInput { longClick() }; rule.waitForIdle()
        assertMode(Pane.MEDIA, CardMode.Fullscreen)
        // A tap while fullscreen is deliberately a no-op.
        rule.onNodeWithTag("pane_MEDIA").performClick(); rule.waitForIdle()
        assertMode(Pane.MEDIA, CardMode.Fullscreen)
        rule.onNodeWithTag("pane_MEDIA").performTouchInput { longClick() }; rule.waitForIdle()
        assertMode(Pane.MEDIA, CardMode.Compact)
    }

    // ---------------------------------------------------------------- Phase 8b: layouts

    @Test fun everyPresetTilesTheScreen() {
        switchLayout(LayoutPreset.SIDE_BY_SIDE)
        assertCollapsed("side by side", Pane.MAP)
        for (p in listOf(Pane.SPEED, Pane.MEDIA)) {
            assertMode(p, CardMode.Expanded)
            assertNear("$p half width", rootWidth() / 2, slot(p).width())
            assertNear("$p full height", rootHeight(), slot(p).height())
        }
        assertTrue("speed on the left", slot(Pane.SPEED).getBoundsInRoot().left < slot(Pane.MEDIA).getBoundsInRoot().left)

        switchLayout(LayoutPreset.ROWS)
        for (p in listOf(Pane.SPEED, Pane.MEDIA)) {
            assertMode(p, CardMode.Wide)
            assertNear("$p full width", rootWidth(), slot(p).width())
            assertNear("$p half height", rootHeight() / 2, slot(p).height())
        }
        tag("speed_inline_stats").assertIsDisplayed()
        listOf("stat_max", "stat_avg", "stat_distance", "stat_elapsed").forEach { tag("inline_$it").assertIsDisplayed() }

        switchLayout(LayoutPreset.QUAD)
        for (p in listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK)) {
            assertMode(p, CardMode.Compact)
            assertNear("$p half width", rootWidth() / 2, slot(p).width())
            assertNear("$p half height", rootHeight() / 2, slot(p).height())
        }
        assertCollapsed("quad", Pane.NAV)
        tag("clock_time").assertIsDisplayed()
        tag("clock_date").assertIsDisplayed()
        // The status corner follows: top right is Speed here.
        assertTrue(tag("pane_SPEED").contains(tag("layouts_open")))

        switchLayout(LayoutPreset.DUO)
        assertDuoModes()
        assertCollapsed("back to Duo", Pane.CLOCK)
    }

    @Test fun switchingLayoutClearsZoomAndSurvivesRecreation() {
        click("maximize_MEDIA")
        switchLayout(LayoutPreset.ROWS)
        assertMode(Pane.MEDIA, CardMode.Wide)
        click("maximize_SPEED")
        assertMode(Pane.SPEED, CardMode.Expanded)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertMode(Pane.SPEED, CardMode.Expanded)
        back()
        assertMode(Pane.SPEED, CardMode.Wide)
    }

    @Test fun headerLongPressPicksWhatAPaneShows() {
        openPicker(Pane.MEDIA)
        assertMode(Pane.MEDIA, CardMode.Compact) // the long-press didn't zoom anything
        pickFromSheet("pick_CLOCK", "picker_sheet")
        assertCollapsed("replaced", Pane.MEDIA)
        assertNear("clock took media's place", rootHeight() / 2, slot(Pane.CLOCK).height())
        assertTrue("clock on top", slot(Pane.CLOCK).getBoundsInRoot().top < slot(Pane.SPEED).getBoundsInRoot().top)
        tag("clock_time").assertIsDisplayed()

        // A pane already shown trades places instead.
        openPicker(Pane.CLOCK)
        pickFromSheet("pick_MAP", "picker_sheet")
        assertNear("map now stacked", rootHeight() / 2, slot(Pane.MAP).height())
        assertNear("clock now tall", rootHeight(), slot(Pane.CLOCK).height())
        assertMode(Pane.CLOCK, CardMode.Expanded)
    }

    @Test fun nextTurnPaneOffersSearchWhenIdle() {
        openPicker(Pane.SPEED)
        pickFromSheet("pick_NAV", "picker_sheet")
        tag("nav_where_to").assertIsDisplayed()
        click("nav_where_to")
        awaitSheet("search_sheet")
        back() // the keyboard first
        if (count("search_sheet").fetchSemanticsNodes().isNotEmpty()) back()
        rule.waitUntil(5_000) { count("search_sheet").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun autoLayoutCanBeTurnedOff() {
        openSettings()
        click("settings_auto_layout")
        closeSettings()
        rule.waitUntil(5_000) { runBlocking { !resetSettings.settings.autoLayout.first() } }
    }

    // ---------------------------------------------------------------- Phase 8c: look and clock

    @Test fun themeSwitchesWithTheMapOnScreen() {
        // The map restyles live (OLED dark ↔ Positron); its vehicle glide must not touch the
        // replaced style (that crashed once).
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.DARK)) {
            openSettings()
            click("settings_theme_${mode.name}")
            closeSettings()
            rule.waitUntil(5_000) { runBlocking { resetSettings.settings.themeMode.first() == mode } }
            rule.mainClock.advanceTimeBy(2_000)
            tag("pane_MAP").assertIsDisplayed()
        }
    }

    @Test fun clockFaceAndSecondHandArePickedInTheLargePane() {
        switchLayout(LayoutPreset.QUAD)
        count("clock_face_ANALOG").assertCountEquals(0) // Compact: no controls
        click("maximize_CLOCK")
        click("clock_face_ANALOG")
        rule.waitUntil(5_000) { count("clock_analog").fetchSemanticsNodes().isNotEmpty() }
        click("clock_seconds")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.clockSeconds.first() } }
        back()
        assertMode(Pane.CLOCK, CardMode.Compact)
        tag("clock_analog").assertIsDisplayed() // the face is the pane's, at every size

        click("maximize_CLOCK")
        click("clock_face_DIGITAL")
        rule.waitUntil(5_000) { count("clock_time").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ClockFace.DIGITAL, runBlocking { resetSettings.settings.clockFace.first() })
    }

    @Test fun cornerClockShowsOnlyWithoutAClockPane() {
        tag("corner_clock").assertIsDisplayed()
        switchLayout(LayoutPreset.QUAD)
        count("corner_clock").assertCountEquals(0)
        switchLayout(LayoutPreset.DUO)
        tag("corner_clock").assertIsDisplayed()
    }
}
