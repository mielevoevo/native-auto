package com.nativeauto.ui.setup

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.di.SettingsEntryPoint
import com.nativeauto.settings.OrientationMode
import com.nativeauto.trip.AutoStartMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 4.2: the first-run setup pages on the real activity (reopened by the test; the rule marks them done otherwise). Every page
 * must fit with Back and Next in view in both orientations, the choices must write the real settings, and finishing must
 * leave the dashboard.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowTest {

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
    private fun await(t: String) = rule.waitUntil(15_000) { count(t) > 0 }
    private fun awaitGone(t: String) = rule.waitUntil(15_000) { count(t) == 0 }
    private fun SemanticsNodeInteraction.bounds() = getBoundsInRoot()

    private fun reopen() {
        runBlocking {
            EntryPointAccessors.fromApplication(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                SettingsEntryPoint::class.java,
            ).setupState().reopen()
        }
        await("setup_screen")
    }

    private fun turn(mode: OrientationMode, portrait: Boolean) {
        runBlocking { resetSettings.settings.setOrientationMode(mode) }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == if (portrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE }
        rule.waitForIdle()
    }

    /** Back (when there is one) and Next / Start riding are fully inside the screen: nothing needs scrolling. */
    private fun assertFits(page: Int) {
        await("setup_page_$page")
        val root = rule.onRoot().bounds()
        val next = tag(if (page == SETUP_PAGES - 1) "setup_finish" else "setup_next").bounds()
        assertTrue("page $page: Next inside the screen", next.bottom <= root.bottom && next.top >= root.top && next.right <= root.right)
        if (page > 0) {
            val back = tag("setup_back").bounds()
            assertTrue("page $page: Back inside the screen", back.bottom <= root.bottom && back.top >= root.top)
            assertEquals("page $page: Back and Next share a row", back.top.value, next.top.value, 2f)
        }
    }

    private fun walkThrough() {
        for (page in 0 until SETUP_PAGES - 1) {
            assertFits(page)
            click("setup_next")
            // The permissions page asks once when something is still off.
            if (page == 2 && count("setup_confirm") > 0) click("setup_confirm_continue")
        }
        assertFits(SETUP_PAGES - 1)
        click("setup_finish")
        awaitGone("setup_screen")
        await("settings_open")
    }

    @Test fun everyPageFitsInLandscapeAndFinishLeavesTheDashboard() {
        reopen()
        walkThrough()
    }

    @Test fun everyPageFitsInPortrait() {
        turn(OrientationMode.PORTRAIT, portrait = true)
        reopen()
        walkThrough()
    }

    @Test fun backGoesOnePageBackAndThePageDotsStayPut() {
        reopen()
        click("setup_next"); await("setup_page_1")
        click("setup_next"); await("setup_page_2")
        click("setup_back"); await("setup_page_1")
        assertEquals("page 1 has Back", 1, count("setup_back"))
        click("setup_back"); await("setup_page_0")
        assertEquals("page 0 has no Back", 0, count("setup_back"))
    }

    @Test fun pickingPortraitOnTheAppearancePageTurnsTheScreenAtOnce() {
        reopen()
        click("setup_next"); await("setup_page_1")
        click("setup_orientation")
        click("setup_orientation_PORTRAIT")
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        assertEquals(OrientationMode.PORTRAIT, runBlocking { resetSettings.settings.orientationMode.first() })
        assertFits(1)
    }

    @Test fun ridingChoicesWriteTheRealSettings() {
        reopen()
        repeat(5) { click("setup_next"); if (count("setup_confirm") > 0) click("setup_confirm_continue") }
        await("setup_page_5")
        click("setup_auto_start")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.autoStartMode.first() } == AutoStartMode.TRIP }
        click("setup_start_after"); click("setup_start_after_45")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.autoStartHoldS.first() } == 45 }
        click("setup_voice")
        rule.waitUntil(5_000) { !runBlocking { resetSettings.settings.voiceGuidance.first() } }
    }

    @Test fun aBikeAddedOnTheBikePageShowsAsACardAndInTheSummary() {
        reopen()
        repeat(4) { click("setup_next"); if (count("setup_confirm") > 0) click("setup_confirm_continue") }
        await("setup_page_4")
        click("setup_add_bike")
        await("garage_editor")
        tag("garage_name").performTextReplacement("Gen 1")
        click("garage_save")
        await("setup_bike_Gen 1")
        assertTrue("the button now adds another", count("setup_add_bike") == 1)
        click("setup_next"); await("setup_page_5")
        click("setup_next"); await("setup_page_6")
        await("setup_ready_bike")
    }
}
