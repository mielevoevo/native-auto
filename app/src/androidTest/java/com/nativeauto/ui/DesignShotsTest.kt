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
        listOf("TRIP_GARAGE", "DISPLAY", "LAYOUT", "OTHERS").forEach {
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
        click("fullscreen_MEDIA"); shot("look_media_fullscreen"); click("fullscreen_MEDIA")
        runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.LIGHT) }
        Thread.sleep(1_200)
        shot("look_media_expanded_light")
        runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.DARK) }
        click("maximize_MEDIA")
        settingsPages("look")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.close() }
    }

    /** 4.3c: the daily greeting, with a bike, landscape and portrait. */
    @Test fun greeting() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        runBlocking {
            val g = entry.garage()
            val id = g.addBike(
                com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L),
                odometerKm = 12_000.0,
            )
            g.setFuelLevel(id, 4, 6, 12_000.0)
        }
        fun greet(name: String) {
            runBlocking { entry.greetingStore().clear() }
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.waitUntil(5_000) { count("greeting_sheet") > 0 }
            shot(name)
            click("greeting_done")
            rule.waitUntil(5_000) { count("greeting_sheet") == 0 }
        }
        greet("greeting_land")
        runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        Thread.sleep(1_500)
        greet("greeting_port")
        runBlocking { entry.garageDao().clear(); entry.greetingStore().markGreeted(java.time.LocalDate.now()) }
    }

    /** 4.6.3: the portrait card shapes with real content: 4 rows, slim rows, big + two halves, 3 rows, 2 rows. */
    @Test fun portraitCards() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        runBlocking {
            val g = entry.garage()
            val id = g.addBike(
                com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L),
                odometerKm = 12_000.0,
            )
            g.setFuelLevel(id, 4, 6, 12_000.0)
            resetSettings.settings.setPaneWatermark(true)
            resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT)
        }
        com.nativeauto.media.NotificationAccess.grant()
        var player: com.nativeauto.media.TestPlayer? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = com.nativeauto.media.TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        Thread.sleep(1_500)
        fun layout(preset: String) {
            click("layouts_open")
            rule.waitUntil(5_000) { count("layouts_sheet") > 0 }
            rule.onNodeWithTag("preset_$preset", useUnmergedTree = true).performScrollTo()
            click("preset_$preset")
            rule.waitUntil(5_000) { count("layouts_sheet") == 0 }
            Thread.sleep(800)
        }
        fun put(shown: Pane, wanted: Pane) {
            rule.onNodeWithTag("header_${shown.name}", useUnmergedTree = true).performTouchInput {
                down(Offset(width * 0.1f, height / 2f))
                advanceEventTime(2_100)
                up()
            }
            rule.waitUntil(5_000) { count("picker_sheet") > 0 }
            click("pick_${wanted.name}")
            rule.waitUntil(5_000) { count("picker_sheet") == 0 }
            Thread.sleep(800)
        }
        try {
            layout("STACK3"); shot("pc_stack3")
            put(Pane.MEDIA, Pane.CLOCK); put(Pane.SPEED, Pane.GARAGE); shot("pc_stack3_clock_garage")
            click("maximize_CLOCK"); shot("pc_slim"); click("maximize_CLOCK")
            layout("STACK4"); shot("pc_stack4")
            layout("BIG_TWO"); shot("pc_bigtwo")
            put(Pane.SPEED, Pane.CLOCK); put(Pane.MEDIA, Pane.GARAGE); shot("pc_bigtwo_clock_garage")
            layout("STACK2"); put(Pane.SPEED, Pane.MEDIA); shot("pc_stack2_media")
            click("fullscreen_MEDIA"); shot("pc_media_fullscreen"); click("fullscreen_MEDIA")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player?.close() }
            runBlocking { entry.garageDao().clear() }
        }
    }

    /** 4.6.4: the settings dialogs in both orientations, then portrait cards and settings in the light theme. */
    @Test fun dialogsAndLight() {
        fun dialogs(prefix: String) {
            click("settings_open")
            rule.waitUntil(5_000) { count("settings_sheet") > 0 }
            listOf(
                listOf("TRIP_GARAGE", "settings_trip_automation", "trip_automation", "settings_trip_automation_done"),
                listOf("TRIP_GARAGE", "settings_trip_options", "trip_options", "settings_trip_options_done"),
                listOf("DISPLAY", "settings_colours", "colours_dialog", "colours_done"),
                listOf("DISPLAY", "settings_screen_options", "screen_options", "settings_screen_options_done"),
            ).forEach { (cat, row, dialog, done) ->
                rule.onNodeWithTag("settings_cat_$cat", useUnmergedTree = true).performScrollTo()
                click("settings_cat_$cat")
                rule.onNodeWithTag(row, useUnmergedTree = true).performScrollTo()
                click(row)
                rule.waitUntil(5_000) { count(dialog) > 0 }
                shot("${prefix}_$dialog")
                click(done)
                rule.waitUntil(5_000) { count(dialog) == 0 }
            }
            click("settings_done")
            rule.waitUntil(5_000) { count("settings_sheet") == 0 }
        }
        dialogs("dlg_land")
        runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
        rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        Thread.sleep(1_500)
        dialogs("dlg_port")
        runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.LIGHT) }
        Thread.sleep(1_500)
        shot("light_stack3")
        listOf("STACK4", "BIG_TWO").forEach {
            click("layouts_open")
            rule.waitUntil(5_000) { count("layouts_sheet") > 0 }
            rule.onNodeWithTag("preset_$it", useUnmergedTree = true).performScrollTo()
            click("preset_$it")
            rule.waitUntil(5_000) { count("layouts_sheet") == 0 }
            Thread.sleep(800)
            shot("light_$it")
        }
        dialogs("dlg_light")
    }

    /** 4.7: the bike editor (presets, gauge, fine-tuning), the gauge picker, the bike page and a trip's traffic line. */
    @Test fun garage47() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        val now = System.currentTimeMillis()
        val trip = runBlocking {
            val g = entry.garage()
            g.addBike(
                com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L, gaugeBars = 12, odoFactorManual = 0.985),
                12_345.0,
            )
            entry.tripDao().insertWithTrack(
                com.nativeauto.history.TripRecord(
                    startedAtMs = now - 2_400_000, endedAtMs = now - 600_000, durationMs = 1_800_000, movingMs = 1_550_000, distanceM = 9_234.0,
                    maxSpeedMps = 15f, startLat = -7.8, startLon = 110.37, endLat = -7.79, endLon = 110.37, track = "", stoppedMs = 250_000,
                    startAddress = "Jalan A", endAddress = "Jalan B", loadStops = 14, loadCrawlS = 380.0, loadCrawlM = 1_240.0, loadCrawlClimbM = 3.0,
                ),
                emptyList(),
            )
        }
        fun scroll(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).performScrollTo()
        fun pages(p: String) {
            click("settings_open")
            rule.waitUntil(5_000) { count("settings_sheet") > 0 }
            click("settings_cat_TRIP_GARAGE")
            scroll("settings_garage"); click("settings_garage")
            rule.waitUntil(5_000) { count("garage_edit") > 0 }
            shot("${p}_bike")
            scroll("garage_edit"); click("garage_edit")
            rule.waitUntil(5_000) { count("garage_editor") > 0 }
            shot("${p}_editor")
            scroll("garage_tuning"); click("garage_tuning")
            rule.waitForIdle()
            scroll("garage_odo_factor_learned")
            shot("${p}_editor_tuning")
            scroll("garage_model"); click("garage_model")
            rule.waitUntil(5_000) { count("garage_model_VARIO_125") > 0 }
            shot("${p}_editor_models")
            click("garage_model_VARIO_125")
            rule.waitForIdle()
            click("garage_save")
            rule.waitUntil(5_000) { count("garage_editor") == 0 }
            runBlocking { entry.garageDao().activeBike()!!.let { entry.garageDao().updateBike(it.copy(model = "NMAX_GEN1", tankLitres = 6.6, reserveLitres = 1.3, gaugeBars = 12)) } }
            scroll("garage_set_level"); click("garage_set_level")
            rule.waitUntil(5_000) { count("garage_level_dialog") > 0 }
            click("garage_level_bar_7")
            shot("${p}_level")
            click("garage_level_save")
            rule.waitUntil(5_000) { count("garage_level_dialog") == 0 }
            click("garage_back")
            rule.waitUntil(5_000) { count("garage_screen") == 0 }
            click("settings_open")
            rule.waitUntil(5_000) { count("settings_sheet") > 0 }
            click("settings_cat_TRIP_GARAGE")
            scroll("settings_history"); click("settings_history")
            rule.waitUntil(5_000) { count("history_trip") > 0 }
            click("history_trip")
            rule.waitUntil(5_000) { count("history_traffic") > 0 }
            shot("${p}_trip")
            scroll("history_traffic")
            shot("${p}_trip_traffic")
            rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(5_000) { count("history_traffic") == 0 }
            rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(5_000) { count("history_trip") == 0 }
        }
        try {
            pages("g47_land")
            runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
            rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            Thread.sleep(1_500)
            pages("g47_port")
        } finally {
            runBlocking { entry.tripDao().delete(trip); entry.garageDao().clear() }
        }
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
