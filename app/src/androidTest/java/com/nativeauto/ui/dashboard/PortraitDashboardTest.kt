package com.nativeauto.ui.dashboard

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.settings.OrientationMode
import com.nativeauto.ui.map.MapDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 14b: the dashboard on a vertically mounted phone, on the real activity. The orientation setting turns the emulator's
 * screen (the activity handles the rotation itself, nothing is recreated), and the layouts, headers, sheets and map
 * controls are checked in portrait. Only what portrait changed is tested here; landscape is covered by
 * DashboardScreenTest.
 */
@RunWith(AndroidJUnit4::class)
class PortraitDashboardTest {

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(t: String) { tag(t).performClick(); rule.waitForIdle() }
    private fun slot(p: Pane) = tag(SlotTag.of(p))
    private fun SemanticsNodeInteraction.w(): Dp = getBoundsInRoot().let { it.right - it.left }
    private fun SemanticsNodeInteraction.h(): Dp = getBoundsInRoot().let { it.bottom - it.top }
    private fun SemanticsNodeInteraction.top(): Float = getBoundsInRoot().top.value
    private fun near(msg: String, expected: Float, actual: Float, tol: Float = 1.5f) =
        assertTrue("$msg: expected $expected, was $actual", abs(expected - actual) <= tol)
    private fun mode(p: Pane, m: CardMode) = rule.onNodeWithTag("pane_${p.name}")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, m.name))
    private fun awaitSheet(t: String) = rule.waitUntil(5_000) { count(t) > 0 }
    private fun awaitGone(t: String) = rule.waitUntil(5_000) { count(t) == 0 }

    /** The orientation setting asks, the activity turns; wait until the window and the springs have settled. */
    private fun turn(mode: OrientationMode, portrait: Boolean) {
        runBlocking { resetSettings.settings.setOrientationMode(mode) }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.onRoot().h().value > rule.onRoot().w().value == portrait }
        rule.waitForIdle()
    }

    private fun goPortrait() {
        turn(OrientationMode.PORTRAIT, portrait = true)
        // Duo's three panes arrive as three full-width rows; wait for the slide to finish.
        rule.waitUntil(10_000) { abs(slot(Pane.MAP).h().value - rule.onRoot().h().value / 3f) < 1.5f }
    }

    private fun openPicker(pane: Pane) {
        tag("header_${pane.name}").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            advanceEventTime(PICKER_MS)
            up()
        }
        awaitSheet("picker_sheet")
    }

    @Test fun portraitTurnsDuoIntoThreeRowsKeepsTheOrderAndTheMapInstance() {
        val activity = rule.activity
        val made = MapDiagnostics.instances(activity)
        goPortrait()
        val h = rule.onRoot().h().value
        val w = rule.onRoot().w().value
        for (p in listOf(Pane.MAP, Pane.MEDIA, Pane.SPEED)) {
            near("$p fills the width", w, slot(p).w().value)
            near("$p is a third of the height", h / 3f, slot(p).h().value)
        }
        // Same panes, same order, top to bottom: Duo's map, media, speed.
        assertTrue(slot(Pane.MAP).top() < slot(Pane.MEDIA).top() && slot(Pane.MEDIA).top() < slot(Pane.SPEED).top())
        mode(Pane.MAP, CardMode.Wide)
        mode(Pane.MEDIA, CardMode.Wide)
        mode(Pane.SPEED, CardMode.Wide)
        // The activity was not recreated and the map not rebuilt.
        assertEquals("same activity", activity, rule.activity)
        assertEquals("the map was not recreated", made, MapDiagnostics.instances(activity))
        // And back: Duo returns with the same panes.
        turn(OrientationMode.LANDSCAPE, portrait = false)
        rule.waitUntil(10_000) { abs(slot(Pane.MAP).w().value - rule.onRoot().w().value / 2f) < 1.5f }
        mode(Pane.MAP, CardMode.Expanded)
        assertEquals("the map was not recreated", made, MapDiagnostics.instances(activity))
    }

    @Test fun portraitOffersOnlyPortraitLayouts() {
        goPortrait()
        click("layouts_open")
        awaitSheet("layouts_sheet")
        for (p in listOf("STACK1", "STACK2", "STACK3", "BIG_TWO", "STACK4")) assertEquals("preset_$p", 1, count("preset_$p"))
        for (p in listOf("DUO", "SIDE_BY_SIDE", "ROWS", "QUAD")) assertEquals("no landscape preset $p", 0, count("preset_$p"))
    }

    @Test fun bigPlusTwoGivesTheLowerPairAnExactHalfEach() {
        goPortrait()
        click("layouts_open"); awaitSheet("layouts_sheet"); click("preset_BIG_TWO"); awaitGone("layouts_sheet")
        val panes = listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA) // BIG_TWO's default order
        rule.waitUntil(10_000) { abs(slot(panes[0]).h().value - rule.onRoot().h().value * BIG_TWO_TOP) < 1.5f }
        near("the big pane is full width", rule.onRoot().w().value, slot(panes[0]).w().value)
        near("the pair splits 50/50", slot(panes[1]).w().value, slot(panes[2]).w().value, 0.5f)
        near("the pair is half the width each", rule.onRoot().w().value / 2f, slot(panes[1]).w().value)
        mode(panes[0], CardMode.Expanded)
        mode(panes[1], CardMode.Narrow)
        mode(panes[2], CardMode.Narrow)
    }

    @Test fun theFourStackHasMiniCardsWhoseHeadersCarryNoPaneButtons() {
        goPortrait()
        click("layouts_open"); awaitSheet("layouts_sheet"); click("preset_STACK4"); awaitGone("layouts_sheet")
        rule.waitUntil(10_000) { abs(slot(Pane.SPEED).h().value - rule.onRoot().h().value / 4f) < 1.5f }
        for (p in listOf(Pane.MAP, Pane.SPEED, Pane.MEDIA, Pane.CLOCK)) {
            mode(p, CardMode.Mini)
            assertEquals("$p has no fullscreen button", 0, count("fullscreen_${p.name}"))
            assertEquals("$p has no maximize button", 0, count("maximize_${p.name}"))
            assertEquals("$p has no swap button", 0, count("swap_${p.name}"))
        }
        // The status corner (layouts, settings) stays on the top card.
        tag("layouts_open").assertIsDisplayed()
        tag("settings_open").assertIsDisplayed()
        // Fullscreen is in the hold menu, and works.
        openPicker(Pane.SPEED)
        click("pick_fullscreen")
        awaitGone("picker_sheet")
        mode(Pane.SPEED, CardMode.Fullscreen)
        near("fullscreen fills the height", rule.onRoot().h().value, slot(Pane.SPEED).h().value)
    }

    @Test fun thePickerIsATwoColumnGridInPortrait() {
        goPortrait()
        openPicker(Pane.MEDIA)
        awaitSheet("picker_grid")
        // Two tiles per row: the first two share a top edge, the third is below them.
        val first = tag("pick_MAP").top()
        near("map and media share a row", first, tag("pick_MEDIA").top(), 1f)
        assertTrue("the third tile starts a new row", tag("pick_SPEED").top() > first + 10f)
        assertEquals("no maximize in portrait", 0, count("pick_maximize"))
        click("pick_MAP") // swap places with the map
        awaitGone("picker_sheet")
    }

    @Test fun fullscreenInPortraitHasNoMaximizeAndExitsByItsButton() {
        goPortrait()
        assertEquals(0, count("maximize_SPEED"))
        click("fullscreen_SPEED")
        mode(Pane.SPEED, CardMode.Fullscreen)
        near("fills the screen", rule.onRoot().h().value, slot(Pane.SPEED).h().value)
        click("fullscreen_SPEED")
        rule.waitUntil(10_000) { abs(slot(Pane.SPEED).h().value - rule.onRoot().h().value / 3f) < 1.5f }
    }

    @Test fun settingsHasOneColumnAnOrientationRowAndItChangesTheScreen() {
        goPortrait()
        click("settings_open"); awaitSheet("settings_sheet")
        click("settings_cat_LAYOUT")
        tag("settings_orientation_PORTRAIT").performScrollTo().assertIsDisplayed()
        tag("settings_orientation_AUTO").assertIsDisplayed()
        tag("settings_orientation_hint").assertIsDisplayed()
        // One column: the two rows are stacked, not side by side.
        near("rows share a left edge", tag("settings_orientation_LANDSCAPE").getBoundsInRoot().left.value, tag("settings_drag_hold_1").getBoundsInRoot().left.value, 2f)
        click("settings_orientation_LANDSCAPE")
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        rule.waitForIdle()
    }

    @Test fun dialogsOpenAsBottomSheetsInPortrait() {
        goPortrait()
        click("settings_open"); awaitSheet("settings_sheet")
        click("settings_cat_TRIP_GARAGE")
        click("settings_trip_options")
        awaitSheet("trip_options")
        // A bottom sheet: full width, hanging from the bottom edge, one column.
        // (the dialog is its own window: measure its Done button on the screen, not inside the dashboard's root)
        val density = rule.activity.resources.displayMetrics.density
        val screenW = rule.activity.resources.displayMetrics.widthPixels / density
        val done = tag("settings_trip_options_done").fetchSemanticsNode()
        val doneRight = (done.positionOnScreen.x + done.size.width) / density
        near("a full-width sheet: Done is 20 dp from the right edge", screenW - 20f, doneRight, 4f)
        val screenH = rule.activity.resources.displayMetrics.heightPixels / density
        assertTrue("the sheet hangs from the lower part of the screen", done.positionOnScreen.y / density > screenH * 0.1f)
        tag("settings_trip_options_done").assertIsDisplayed()
        click("settings_trip_options_done")
        awaitGone("trip_options")
    }
}

/** Past the default 1 s hold (12a) and the second step that arms the picker (0.7 s), 13a. */
private const val PICKER_MS = 2_100L
