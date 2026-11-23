package com.nativeauto.ui

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.settings.OrientationMode
import com.nativeauto.ui.dashboard.Pane
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 4.3: screenshots for the `DESIGN.md` review, not a check. Runs only when asked:
 * `-Pandroid.testInstrumentationRunnerArguments.designShots=1`. Each shot is a `screencap` (run as the shell user)
 * into `/data/local/tmp/design/`, pulled afterwards with `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class DesignShotsTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("designShots") == "1")
        shell("mkdir -p /data/local/tmp/design")
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700) // sheets and springs settle
        val p = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/design/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { it.readBytes() }
    }

    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).performClick()

    private fun settingsPages(prefix: String) {
        click("settings_open")
        rule.waitUntil(5_000) { count("settings_sheet") > 0 }
        listOf("TRIP_GARAGE", "DISPLAY", "LAYOUT", "NAVIGATION", "AUDIO", "BACKUP", "PRIVACY").forEach {
            rule.onNodeWithTag("settings_cat_$it", useUnmergedTree = true).performScrollTo()
            click("settings_cat_$it")
            shot("${prefix}_settings_$it")
        }
        click("settings_done")
        rule.waitUntil(5_000) { count("settings_sheet") == 0 }
    }

    private fun picker(prefix: String, pane: Pane) {
        rule.onNodeWithTag("header_${pane.name}", useUnmergedTree = true).performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            advanceEventTime(2_100)
            up()
        }
        rule.waitUntil(5_000) { count("picker_sheet") > 0 }
        shot("${prefix}_picker_${pane.name}")
        click("pick_${pane.name}")
        rule.waitUntil(5_000) { count("picker_sheet") == 0 }
    }

    @Test fun landscape() {
        shot("land_dashboard")
        settingsPages("land")
        picker("land", Pane.SPEED)
    }

    /** 4.3b: icon titles, faint card icons, plated media buttons; dark and light. */
    @Test fun look() {
        runBlocking {
            resetSettings.settings.setCardTitleStyle(com.nativeauto.settings.CardTitleStyle.ICON)
            resetSettings.settings.setPaneWatermark(true)
        }
        com.nativeauto.media.NotificationAccess.grant()
        var player: com.nativeauto.media.TestPlayer? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = com.nativeauto.media.TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        rule.waitUntil(10_000) { count("media_next") > 0 }
        Thread.sleep(800)
        shot("look_dashboard_icons")
        click("maximize_MEDIA")
        shot("look_media_expanded_dark")
        runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.LIGHT) }
        Thread.sleep(1_200)
        shot("look_media_expanded_light")
        runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.DARK) }
        click("maximize_MEDIA")
        settingsPages("look")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.close() }
    }

    @Test fun portrait() {
        runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        Thread.sleep(1_500)
        shot("port_dashboard")
        settingsPages("port")
        picker("port", Pane.SPEED)
    }
}
