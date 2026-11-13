package com.nativeauto.ui.dashboard

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import com.nativeauto.ui.map.MapDiagnostics
import org.junit.Rule
import com.nativeauto.di.SettingsEntryPoint
import com.nativeauto.history.TripRecord
import dagger.hilt.android.EntryPointAccessors
import androidx.test.platform.app.InstrumentationRegistry
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
    /** Off-centre: the centre of the card can hold a button (Resume, the log face controls). */
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

    private fun awaitGone(t: String) = rule.waitUntil(5_000) { count(t).fetchSemanticsNodes().isEmpty() }

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

    /** 12a/13a: hold past the lift time and the arming time, let go without moving: the pane picker. */
    private fun openPicker(pane: Pane) {
        tag("header_${pane.name}").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            advanceEventTime(PICKER_MS)
            up()
        }
        awaitSheet("picker_sheet")
    }

    /** 12a: hold [from]'s slot, drag the finger to the centre of [to]'s slot, let go. */
    private fun drag(from: Pane, to: Pane) {
        val start = slot(from).fetchSemanticsNode().boundsInRoot
        val end = slot(to).fetchSemanticsNode().boundsInRoot
        slot(from).performTouchInput {
            val p = Offset(width * 0.15f, height * 0.8f)
            down(p)
            advanceEventTime(HOLD_MS)
            val target = end.center - start.topLeft
            repeat(10) { i -> moveTo(p + (target - p) * ((i + 1) / 10f)) }
            up()
        }
        rule.waitForIdle()
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
        drag(Pane.MEDIA, Pane.SPEED)
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

    private fun openSettings(category: String? = null) {
        click("settings_open")
        awaitSheet("settings_sheet")
        // 13c: the sheet opens on Display; the other pages are a category away.
        if (category != null) click("settings_cat_$category")
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
            if (mediaOnTop != (slot(Pane.MEDIA).getBoundsInRoot().top < slot(Pane.SPEED).getBoundsInRoot().top)) drag(Pane.MEDIA, Pane.SPEED)

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
        drag(Pane.MEDIA, Pane.SPEED)
        openSettings("LAYOUT")
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
        toggleBatteryInScreenOptions()
        closeSettings()
        count("battery_indicator").assertCountEquals(0)
        tag("settings_open").assertIsDisplayed() // the way back into settings stays

        openSettings()
        toggleBatteryInScreenOptions()
        closeSettings()
        rule.waitUntil(5_000) { count("battery_indicator").fetchSemanticsNodes().size == 1 }
    }

    /** 12a: the battery switch moved into Screen options (the sheet's columns stay one screen). */
    private fun toggleBatteryInScreenOptions() {
        click("settings_screen_options")
        awaitSheet("screen_options")
        click("settings_battery")
        click("settings_screen_options_done")
        rule.waitUntil(5_000) { count("screen_options").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun speedUnitSwitchesTheSpeedCard() {
        rule.onNodeWithText("km/h", useUnmergedTree = true).assertIsDisplayed()
        openSettings("NAVIGATION")
        click("settings_unit_MPH")
        closeSettings()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("mph", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("km/h", useUnmergedTree = true).assertCountEquals(0)
    }

    /** The speed card's second face (9b): swipe the pager left. */
    private fun openLogFace() {
        tag("speed_faces").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { count("log_start").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitEnabled(t: String, enabled: Boolean) = rule.waitUntil(5_000) {
        count(t).fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config == enabled }
    }

    @Test fun aTripStartsOnlyOnStartAndResetDiscardsIt() {
        // 9a: opening the app starts nothing; 9b: Start is on the log face.
        openLogFace()
        tag("log_pause").assertIsNotEnabled()
        tag("log_end").assertIsNotEnabled()
        click("log_start")
        waitEnabled("log_pause", true)
        tag("log_start").assertIsNotEnabled()
        openSettings("TRIP_GARAGE")
        click("settings_reset_trip")
        click("settings_reset_trip")
        closeSettings()
        waitEnabled("log_start", true)
        tag("log_pause").assertIsNotEnabled()
    }

    @Test fun pauseResumeAndEndShowTheSummaryUntilDismissed() {
        openLogFace()
        click("log_start")
        waitEnabled("log_pause", true)
        click("log_pause")
        waitEnabled("log_start", true) // ▶ is Resume now
        tag("log_end").assertIsEnabled()
        click("log_start")
        waitEnabled("log_pause", true)
        // End needs a second tap.
        click("log_end")
        count("trip_summary").assertCountEquals(0)
        click("log_end")
        rule.waitUntil(5_000) { count("trip_summary").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Trip complete", useUnmergedTree = true).assertIsDisplayed()
        click("trip_summary")
        rule.waitUntil(5_000) { count("trip_summary").fetchSemanticsNodes().isEmpty() }
        // Back on the log face, zeroed and ready for the next Start.
        tag("log_start").assertIsEnabled()
        tag("log_end").assertIsNotEnabled()
    }

    @Test fun telemetrySettingsChooseTheLogFaceFigures() {
        // Defaults (9c): moving and stopped time hidden, the rest shown.
        openSettings("TRIP_GARAGE")
        click("settings_trip_options")
        tag("settings_stat_moving_time").assertIsNotSelected()
        click("settings_stat_moving_time")
        click("settings_stat_max")
        tag("settings_stat_moving_time").assertIsSelected()
        tag("settings_stat_max").assertIsNotSelected()
        click("settings_trip_options_done")
        closeSettings()
        click("maximize_SPEED")
        assertMode(Pane.SPEED, CardMode.Expanded)
        openLogFace()
        rule.waitUntil(5_000) { count("log_stat_moving_time").fetchSemanticsNodes().isNotEmpty() }
        count("log_stat_max").assertCountEquals(0)
        tag("log_stat_distance").assertIsDisplayed()
    }

    @Test fun historyGroupsTripsByDayAndOffersBackupAndExport() {
        val entry = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            SettingsEntryPoint::class.java,
        )
        val dao = entry.tripDao()
        val now = System.currentTimeMillis()
        val id = runBlocking {
            dao.insertWithTrack(
                TripRecord(
                    startedAtMs = now - 60_000, endedAtMs = now, durationMs = 60_000, movingMs = 60_000, distanceM = 1_234.0,
                    maxSpeedMps = 15f, startLat = -7.8, startLon = 110.37, endLat = -7.79, endLon = 110.37, track = "",
                ),
                emptyList(),
            )
        }
        try {
            openSettings("TRIP_GARAGE")
            click("settings_history")
            rule.waitUntil(5_000) { count("history_day_${java.time.LocalDate.now()}").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Today", useUnmergedTree = true).assertIsDisplayed()
            tag("history_backup").assertIsEnabled()
            tag("history_import").assertIsEnabled()
        } finally {
            runBlocking { dao.delete(id) }
        }
    }

    private val NL = Char(10).toString()

    /** 10b: what Google Maps' share button sends, delivered to the running activity (singleTask). */
    private fun share(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_SEND)
                .setClassName(context, "com.nativeauto.MainActivity")
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, text)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Test fun anUnreadableSharedLinkSaysSo() {
        share("Somewhere" + NL + "geo:0,0")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Couldn't read a place from that link", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag("link_status").assertIsDisplayed()
    }

    @Test fun aSharedPlaceStartsRouting() {
        share(listOf("Tugu Jogja", "Jl. Jend. Sudirman, Yogyakarta", "https://maps.google.com/?q=-7.7829,110.3671").joinToString(NL))
        // Routing, then a preview (or a failure card when the emulator is offline): never idle.
        rule.waitUntil(15_000) {
            listOf("nav_status", "nav_preview", "nav_retry").any { count(it).fetchSemanticsNodes().isNotEmpty() }
        }
        count("nav_where_to").assertCountEquals(0)
    }

    @Test fun backOnceShowsTheHint() {
        back()
        tag("back_hint").assertIsDisplayed()
        rule.waitUntil(5_000) { count("back_hint").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun doubleBackDuringATripAsksToSave() {
        openLogFace()
        click("log_start")
        waitEnabled("log_pause", true)
        back()
        back()
        rule.onNodeWithText("Save this trip?", useUnmergedTree = true).assertIsDisplayed()
        click("save_prompt_cancel")
        count("save_prompt_cancel").assertCountEquals(0)
        // Cancel keeps the trip and the app.
        tag("log_pause").assertIsEnabled()
    }

    @Test fun autoPauseAndAutoEndAreSettings() {
        openSettings("TRIP_GARAGE")
        click("settings_trip_options")
        click("settings_auto_pause_10")
        click("settings_auto_end_0")
        tag("settings_auto_pause_10").assertIsSelected()
        tag("settings_auto_end_0").assertIsSelected()
        click("settings_trip_options_done")
        closeSettings()
    }

    @Test fun resetTripAsksForASecondTap() {
        openSettings("TRIP_GARAGE")
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

    @Test fun fullscreenIsTheButtonAndATapThereDoesNothing() {
        click("fullscreen_MEDIA")
        assertMode(Pane.MEDIA, CardMode.Fullscreen)
        // A tap while fullscreen is deliberately a no-op.
        rule.onNodeWithTag("pane_MEDIA").performClick(); rule.waitForIdle()
        assertMode(Pane.MEDIA, CardMode.Fullscreen)
        click("fullscreen_MEDIA")
        assertMode(Pane.MEDIA, CardMode.Compact)
    }

    // ---------------------------------------------------------------- Phase 12a: hold and drag

    @Test fun holdingACardAndLettingGoOpensThePickerWithoutZooming() {
        // On the body this time (the old long-press = fullscreen is gone).
        rule.onNodeWithTag("pane_SPEED").performTouchInput {
            down(percentOffset(0.1f, 0.85f))
            advanceEventTime(PICKER_MS)
            up()
        }
        awaitSheet("picker_sheet")
        assertMode(Pane.SPEED, CardMode.Compact)
        back()
        // A short press is still a tap: maximize.
        tapSpeedCardEdge()
        assertMode(Pane.SPEED, CardMode.Expanded)
    }

    @Test fun draggingACardOntoAnotherSwapsThemAndKeepsTheMap() {
        val maps = MapDiagnostics.instances(rule.activity)
        val tallTop = slot(Pane.MAP).getBoundsInRoot()
        drag(Pane.SPEED, Pane.MAP)
        rule.waitUntil(5_000) { count("picker_sheet").fetchSemanticsNodes().isEmpty() }
        // Speed took the tall slot, the map went to speed's corner.
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertNear("speed now tall", rootHeight(), slot(Pane.SPEED).height())
        assertNear("speed where the map was", tallTop.left, slot(Pane.SPEED).getBoundsInRoot().left)
        assertNear("map now stacked", rootHeight() / 2, slot(Pane.MAP).height())
        assertMode(Pane.SPEED, CardMode.Expanded)
        assertEquals("the map wasn't recreated", maps, MapDiagnostics.instances(rule.activity))
    }

    @Test fun aSwipeBeforeTheHoldIsTheCardsOwn() {
        // The speed card's pager still turns: moving early cancels the hold.
        tag("speed_faces").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { count("log_start").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(count("picker_sheet").fetchSemanticsNodes().isEmpty())
    }

    // ---------------------------------------------------------------- Phase 13a

    @Test fun liftedButNotArmedLettingGoDoesNothing() {
        // Past the 1 s hold but well short of the 0.7 s more: the card lifts and settles back.
        rule.onNodeWithTag("pane_SPEED").performTouchInput {
            down(percentOffset(0.1f, 0.85f))
            advanceEventTime(HOLD_MS)
            up()
        }
        rule.waitForIdle()
        assertTrue("no picker", count("picker_sheet").fetchSemanticsNodes().isEmpty())
        assertMode(Pane.SPEED, CardMode.Compact)
    }

    @Test fun movingAfterArmingDragsInsteadOfOpeningThePicker() {
        val tallLeft = slot(Pane.MAP).getBoundsInRoot().left
        val end = slot(Pane.MAP).fetchSemanticsNode().boundsInRoot
        val start = slot(Pane.SPEED).fetchSemanticsNode().boundsInRoot
        slot(Pane.SPEED).performTouchInput {
            val p = Offset(width * 0.15f, height * 0.8f)
            down(p)
            advanceEventTime(PICKER_MS) // armed
            val target = end.center - start.topLeft
            repeat(10) { i -> moveTo(p + (target - p) * ((i + 1) / 10f)) }
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertTrue("no picker after a drag", count("picker_sheet").fetchSemanticsNodes().isEmpty())
        assertNear("speed took the tall slot", tallLeft, slot(Pane.SPEED).getBoundsInRoot().left)
    }

    @Test fun droppingACardStraightOnItsNeighbourSwapsThemByOverlap() {
        // The finger ends in the gutter between the two stacked cards; the lifted card covers the other one.
        val start = slot(Pane.MEDIA).fetchSemanticsNode().boundsInRoot
        val speed = slot(Pane.SPEED).fetchSemanticsNode().boundsInRoot
        slot(Pane.MEDIA).performTouchInput {
            val p = Offset(width * 0.5f, height * 0.5f)
            down(p)
            advanceEventTime(HOLD_MS)
            val dy = speed.top - start.top
            repeat(10) { i -> moveTo(p + Offset(0f, dy * ((i + 1) / 10f))) }
            up()
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertTrue("speed moved up", slot(Pane.SPEED).getBoundsInRoot().top < slot(Pane.MEDIA).getBoundsInRoot().top)
    }

    @Test fun duoShowsSwapOnlyOnTheTallPane() {
        tag("swap_MAP").assertIsDisplayed()
        assertEquals(0, count("swap_MEDIA").fetchSemanticsNodes().size)
        assertEquals(0, count("swap_SPEED").fetchSemanticsNodes().size)
        switchLayout(LayoutPreset.QUAD)
        listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK).forEach {
            assertEquals("quad $it", 0, count("swap_${it.name}").fetchSemanticsNodes().size)
        }
        switchLayout(LayoutPreset.SIDE_BY_SIDE)
        listOf(Pane.SPEED, Pane.MEDIA).forEach { tag("swap_${it.name}").assertIsDisplayed() }
        switchLayout(LayoutPreset.ROWS)
        listOf(Pane.SPEED, Pane.MEDIA).forEach { tag("swap_${it.name}").assertIsDisplayed() }
    }

    @Test fun fullscreenHidesMaximizeAndKeepsTheFullscreenButton() {
        tag("maximize_SPEED").assertIsDisplayed()
        click("fullscreen_SPEED")
        assertMode(Pane.SPEED, CardMode.Fullscreen)
        assertEquals(0, count("maximize_SPEED").fetchSemanticsNodes().size)
        tag("fullscreen_SPEED").assertIsDisplayed()
        click("fullscreen_SPEED")
        assertMode(Pane.SPEED, CardMode.Compact)
        tag("maximize_SPEED").assertIsDisplayed()
    }

    @Test fun compactHeadersCarryAtMostTwoOwnButtons() {
        listOf(Pane.MEDIA, Pane.SPEED).forEach { p ->
            val own = listOf("maximize_", "fullscreen_", "swap_").count { count("$it${p.name}").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("$p has $own own buttons", own <= 2)
        }
    }

    @Test fun theHeaderTitleIsNeverCutByTheCornerClock() {
        // Quad's top-right pane is the narrowest header that carries the clock, battery and both corner buttons.
        switchLayout(LayoutPreset.QUAD)
        listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK).forEach { p ->
            val node = tag("header_title_${p.name}").fetchSemanticsNode()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            assertTrue("$p title shows whole", results.isNotEmpty() && !results[0].hasVisualOverflow)
        }
    }

    @Test fun theSpeedCardOpensOnTheSpeedFace() {
        tag("speed_value").assertIsDisplayed()
        // After a recreation too (the pager state isn't kept).
        tag("speed_faces").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { count("log_start").fetchSemanticsNodes().isNotEmpty() }
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        tag("speed_value").assertIsDisplayed()
    }

    @Test fun theChangelogSheetShowsOnceAfterAnUpdateAndTheHistoryIsInSettings() {
        val store = com.nativeauto.di.SettingsEntryPoint::class.java.let {
            dagger.hilt.android.EntryPointAccessors.fromApplication(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext, it,
            ).changelogStore()
        }
        // Pretend the rider last saw v3.3 (versionCode 18), then reopen.
        runBlocking { store.markSeen(com.nativeauto.changelog.BEFORE_CHANGELOG) }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { count("changelog_sheet").fetchSemanticsNodes().isNotEmpty() }
        tag("changelog_entry_${com.nativeauto.BuildConfig.VERSION_CODE}").assertIsDisplayed()
        click("changelog_done")
        rule.waitUntil(5_000) { count("changelog_sheet").fetchSemanticsNodes().isEmpty() }
        assertEquals(com.nativeauto.BuildConfig.VERSION_CODE, runBlocking { store.lastSeen() })
        // Not again on the next open.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertTrue(count("changelog_sheet").fetchSemanticsNodes().isEmpty())

        // The full history lives in settings.
        openSettings()
        click("settings_whats_new")
        rule.waitUntil(5_000) { count("changelog_history").fetchSemanticsNodes().isNotEmpty() }
        tag("changelog_entry_${com.nativeauto.BuildConfig.VERSION_CODE}").assertIsDisplayed()
        click("changelog_history_done")
        rule.waitUntil(5_000) { count("changelog_history").fetchSemanticsNodes().isEmpty() }
    }

    // ---------------------------------------------------------------- Phase 13b

    @Test fun autoStartBeginsATripAndAnnouncesItOverTheSpeedFace() {
        try {
            runBlocking {
                resetSettings.settings.setAutoStartHoldS(20)
                resetSettings.settings.setAutoStartMode(com.nativeauto.trip.AutoStartMode.TRIP)
                // The scripted drive: stationary jitter, then 100 km/h; 20 s over 15 km/h completes inside it.
                resetSettings.settings.setUseFakeGps(true)
            }
            rule.waitUntil(120_000) { count("auto_start_banner").fetchSemanticsNodes().isNotEmpty() }
            tag("auto_start_banner").assertIsDisplayed()
            // The trip is running: the log face's pause button is enabled.
            // A swipe dismisses the banner.
            tag("auto_start_banner").performTouchInput { swipeLeft() }
            rule.waitUntil(10_000) { count("auto_start_banner").fetchSemanticsNodes().isEmpty() }
        } finally {
            runBlocking { resetSettings.settings.setUseFakeGps(false) }
        }
    }

    @Test fun autoStartTellsWithAPillWhenNoSpeedCardIsOnScreen() {
        try {
            openPicker(Pane.SPEED)
            pickFromSheet("pick_CLOCK", "picker_sheet")
            assertCollapsed("speed left the layout", Pane.SPEED)
            runBlocking {
                resetSettings.settings.setAutoStartHoldS(20)
                resetSettings.settings.setAutoStartMode(com.nativeauto.trip.AutoStartMode.TRIP)
                resetSettings.settings.setUseFakeGps(true)
            }
            rule.waitUntil(120_000) { count("auto_start_pill").fetchSemanticsNodes().isNotEmpty() }
            tag("auto_start_pill").assertIsDisplayed()
            assertEquals(0, count("auto_start_banner").fetchSemanticsNodes().size)
        } finally {
            runBlocking { resetSettings.settings.setUseFakeGps(false) }
        }
    }

    @Test fun autoStartStaysOffByDefaultAndTheChoiceIsSaved() {
        assertEquals(com.nativeauto.trip.AutoStartMode.OFF, runBlocking { resetSettings.settings.autoStartMode.first() })
        openSettings("TRIP_GARAGE")
        click("settings_auto_start_ODOMETER_ONLY")
        click("settings_auto_start_hold_45")
        rule.waitUntil(5_000) {
            runBlocking { resetSettings.settings.autoStartMode.first() } == com.nativeauto.trip.AutoStartMode.ODOMETER_ONLY &&
                runBlocking { resetSettings.settings.autoStartHoldS.first() } == 45
        }
        closeSettings()
    }

    /**
     * 13b: a compact map that guides is the route, one slim chip, and mute + End in its header. Needs
     * the routing server and the scripted GPS, so it skips itself when no route comes back.
     */
    @Test fun aCompactMapGuidingShowsAChipAndMuteAndEndInItsHeader() {
        val navigator = dagger.hilt.android.EntryPointAccessors.fromApplication(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        ).navigator()
        try {
            runBlocking {
                resetSettings.settings.setAutoLayout(false) // keep the Quad: no Navigation pane to take over from the chip
                resetSettings.settings.setUseFakeGps(true)
            }
            switchLayout(LayoutPreset.QUAD)
            assertMode(Pane.MAP, CardMode.Compact)
            val place = com.nativeauto.nav.Place(
                "Test", null, com.nativeauto.nav.GeoPoint(com.nativeauto.location.FakeDrive.START_LAT + 0.02, com.nativeauto.location.FakeDrive.START_LON),
            )
            rule.runOnUiThread { navigator.routeTo(place) }
            val deadline = System.currentTimeMillis() + 60_000
            while (navigator.state.value !is com.nativeauto.nav.NavState.Preview && System.currentTimeMillis() < deadline) {
                if (navigator.state.value is com.nativeauto.nav.NavState.Failed) break
                Thread.sleep(500)
            }
            org.junit.Assume.assumeTrue("no route from the server", navigator.state.value is com.nativeauto.nav.NavState.Preview)
            rule.runOnUiThread { navigator.start() }
            rule.waitUntil(30_000) { count("nav_chip").fetchSemanticsNodes().isNotEmpty() }
            tag("nav_chip").assertIsDisplayed()
            assertEquals(0, count("nav_banner").fetchSemanticsNodes().size)
            assertEquals(0, count("nav_summary").fetchSemanticsNodes().size)
            tag("nav_voice").assertIsDisplayed()
            tag("nav_end").assertIsDisplayed()
            // Maximize and fullscreen moved into the hold menu.
            assertEquals(0, count("fullscreen_MAP").fetchSemanticsNodes().size)
            // The big bottom-right button is the follow button.
            tag("map_recenter").assertIsDisplayed()
            // Mute is a switch that flips the setting.
            click("nav_voice")
            rule.waitUntil(5_000) { !runBlocking { resetSettings.settings.voiceGuidance.first() } }
            click("nav_voice")
            rule.waitUntil(5_000) { runBlocking { resetSettings.settings.voiceGuidance.first() } }
            click("nav_end")
            rule.waitUntil(10_000) { navigator.state.value is com.nativeauto.nav.NavState.Idle }
        } finally {
            rule.runOnUiThread { navigator.stop() }
            runBlocking { resetSettings.settings.setUseFakeGps(false) }
        }
    }

    @Test fun theHoldMenuHoldsFullscreenAndMaximize() {
        openPicker(Pane.SPEED)
        click("pick_maximize")
        awaitGone("picker_sheet")
        assertMode(Pane.SPEED, CardMode.Expanded)
        openPicker(Pane.SPEED)
        click("pick_fullscreen")
        awaitGone("picker_sheet")
        assertMode(Pane.SPEED, CardMode.Fullscreen)
    }

    // ---------------------------------------------------------------- Phase 13c

    @Test fun everySettingsCategoryHasItsPage() {
        openSettings()
        listOf("DISPLAY", "LAYOUT", "NAVIGATION", "TRIP_GARAGE", "AUDIO", "BACKUP", "DEBUG").forEach { cat ->
            click("settings_cat_$cat")
            tag("settings_page_$cat").assertIsDisplayed()
        }
        // The controls that used to sit in one sheet are where the categories say.
        click("settings_cat_LAYOUT"); tag("settings_split_50").assertIsDisplayed(); tag("settings_drag_hold_1").assertIsDisplayed()
        click("settings_cat_NAVIGATION"); tag("settings_unit_KMH").assertIsDisplayed(); tag("settings_auto_layout").assertIsDisplayed()
        click("settings_cat_AUDIO"); tag("settings_voice").assertIsDisplayed()
        click("settings_cat_TRIP_GARAGE")
        listOf("settings_history", "settings_garage", "settings_reset_trip", "settings_trip_options", "settings_auto_start_OFF").forEach { tag(it).assertIsDisplayed() }
        click("settings_cat_BACKUP"); tag("backup_export").assertIsDisplayed(); tag("backup_import").assertIsDisplayed()
        click("settings_cat_DEBUG"); tag("debug_fake_gps").assertIsDisplayed()
        click("settings_cat_DISPLAY"); tag("settings_colours").assertIsDisplayed(); tag("settings_whats_new").assertIsDisplayed()
    }

    @Test fun theAccentCanBePickedByPresetOrHexAndTheRouteColourFollowsOrOverrides() {
        fun accent() = runBlocking { resetSettings.settings.accentColor.first() }
        fun route() = runBlocking { resetSettings.settings.routeColor.first() }
        assertEquals(0xFF3DDC84.toInt(), accent())
        openSettings()
        click("settings_colours")
        awaitSheet("colours_dialog")
        click("accent_preset_violet")
        rule.waitUntil(5_000) { accent() == 0xFFB794F6.toInt() }
        // The hex field shows the colour, takes a new one, and complains about a bad one.
        assertEquals("#B794F6", tag("accent_hex").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        tag("accent_hex").performTextReplacement("#FF00AA")
        rule.waitUntil(5_000) { accent() == 0xFFFF00AA.toInt() }
        tag("accent_hex").performTextReplacement("#12")
        rule.waitForIdle()
        assertEquals("a half-typed colour isn't applied", 0xFFFF00AA.toInt(), accent())
        tag("accent_hex_error").assertIsDisplayed()
        // A colour that vanishes on black: applied, with a warning.
        tag("accent_hex").performTextReplacement("#101030")
        rule.waitUntil(5_000) { accent() == 0xFF101030.toInt() }
        tag("accent_hex_warning").assertIsDisplayed()
        click("accent_preset_green")
        rule.waitUntil(5_000) { accent() == 0xFF3DDC84.toInt() }

        // Route line: follows the accent until overridden.
        assertEquals(null, route())
        click("route_follow")
        rule.waitUntil(5_000) { route() != null }
        click("route_preset_magenta")
        rule.waitUntil(5_000) { route() == 0xFFFF6BCB.toInt() }
        assertEquals("the accent is untouched", 0xFF3DDC84.toInt(), accent())
        click("route_follow")
        rule.waitUntil(5_000) { route() == null }
        click("colours_done")
        awaitGone("colours_dialog")
        closeSettings()
    }

    // ---------------------------------------------------------------- Phase 14a

    @Test fun theAudioPageHasCallControlsAndTheCallCardIsNotAPickerChoice() {
        openSettings("AUDIO")
        tag("settings_call_status").assertIsDisplayed()
        closeSettings()
        // The call card is nobody's choice: the pane picker lists the other six only.
        openPicker(Pane.MEDIA)
        assertEquals(0, count("pick_CALL").fetchSemanticsNodes().size)
        tag("pick_MAP").assertIsDisplayed()
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

    @Test fun holdingTheHeaderPicksWhatAPaneShows() {
        openPicker(Pane.MEDIA)
        assertMode(Pane.MEDIA, CardMode.Compact) // the hold didn't zoom anything
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
        openSettings("NAVIGATION")
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

/** Past the default 1 s hold (12a), with room for the test clock. */
private const val HOLD_MS = 1_300L

/** Past the hold (1 s) and the second step that arms the picker (0.7 s), 13a. */
private const val PICKER_MS = 2_100L
