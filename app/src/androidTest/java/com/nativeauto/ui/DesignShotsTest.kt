package com.nativeauto.ui

import android.Manifest
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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

    /** 4.7.2: the odometer dialog with "Recorded on another phone", as a correction and as the prompt after days away. */
    @Test fun odometer472() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        val draft = com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L)
        fun dialogs(p: String) {
            val long = runBlocking {
                entry.garageDao().clear()
                entry.garage().addBike(draft, 12_345.0)
                val id = entry.garage().addBike(draft.copy(name = "Work scooter Gen 2"), 30_000.0)
                entry.garageDao().updateBike(entry.garageDao().bike(id)!!.copy(lastUsedAtMs = System.currentTimeMillis() - 8 * 86_400_000L))
                id
            }
            click("settings_open")
            rule.waitUntil(5_000) { count("settings_sheet") > 0 }
            click("settings_cat_TRIP_GARAGE")
            rule.onNodeWithTag("settings_garage", useUnmergedTree = true).performScrollTo()
            click("settings_garage")
            rule.waitUntil(5_000) { count("garage_correct") > 0 }
            rule.onNodeWithTag("garage_correct", useUnmergedTree = true).performScrollTo()
            click("garage_correct")
            rule.waitUntil(5_000) { count("garage_odo_other_phone") > 0 }
            click("garage_odo_other_phone")
            shot("${p}_correct")
            click("garage_odo_input")
            Thread.sleep(1_500)
            shot("${p}_correct_keyboard")
            click("garage_odo_skip")
            rule.waitUntil(5_000) { count("garage_odometer_dialog") == 0 }
            runBlocking { entry.garage().activate(long) }
            rule.waitUntil(5_000) { count("garage_odo_other_phone") > 0 }
            shot("${p}_away")
            click("garage_odo_skip")
            rule.waitUntil(5_000) { count("garage_odometer_dialog") == 0 }
            click("garage_back")
            rule.waitUntil(5_000) { count("garage_screen") == 0 }
        }
        try {
            dialogs("g472_land")
            runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
            rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            Thread.sleep(1_500)
            dialogs("g472_port")
            runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.LIGHT) }
            Thread.sleep(800)
            dialogs("g472_light")
        } finally {
            runBlocking { entry.garageDao().clear() }
        }
    }

    /** 4.8: fill-up correction and review, own fuels, dash figures, the backup dialog, the shaded route. Landscape, portrait, light. */
    @Test fun garage48() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        fun scrollClick(t: String) { rule.onNodeWithTag(t, useUnmergedTree = true).performScrollTo(); click(t) }
        fun first(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true)[0]
        fun gone(t: String) = rule.waitUntil(5_000) { count(t) == 0 }
        /** Back, also past the keyboard. */
        fun back(t: String) = repeat(3) {
            if (count(t) > 0) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                runCatching { rule.waitUntil(2_500) { count(t) == 0 } }
            }
        }
        fun there(t: String) = rule.waitUntil(5_000) { count(t) > 0 }
        val now = System.currentTimeMillis()
        val trip = runBlocking {
            entry.garageDao().clear()
            entry.garage().saveCustomFuel(null, "Shell V-Power", 16_500.0)
            val id = entry.garage().addBike(
                com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L),
                12_300.0,
            )
            listOf(12_000.0 to 4.665, 12_206.0 to 5.56).forEachIndexed { i, (km, l) ->
                entry.garageDao().insertRefuel(
                    com.nativeauto.garage.RefuelRecord(
                        bikeId = id, timeMs = now - (3 - i) * 86_400_000L, odometerKm = km, litres = l, pricePerL = 15_950.0, totalRp = l * 15_950,
                        full = true, fuelType = com.nativeauto.garage.FuelType.PERTAMAX.key,
                    ),
                )
            }
            repeat(7) { entry.garageDao().insertDashKmPerL(com.nativeauto.garage.DashKmPerL(bikeId = id, timeMs = now - 40_000_000L + it, kmPerL = 36.4 + it / 10.0, tripKm = 8.2)) }
            // A made-up loop in the middle of town at 10 to 110 km/h.
            var along = 0.0
            val points = List(500) { i ->
                val v = 18 + 13 * kotlin.math.sin(i / 60.0)
                along += v
                val a = along / 1_400
                com.nativeauto.history.TrackPointRecord(
                    tripId = 0, timeMs = now - 600_000 + i * 1_000L, latitude = -7.79 + 0.012 * kotlin.math.sin(a), longitude = 110.37 + 0.02 * (1 - kotlin.math.cos(a)) * (1 - i / 900.0),
                    speedMps = v.toFloat(), bearingDeg = 0f, accuracyM = 4f,
                )
            }
            entry.tripDao().insertWithTrack(
                com.nativeauto.history.TripRecord(
                    startedAtMs = now - 600_000, endedAtMs = now, durationMs = 600_000, movingMs = 560_000, stoppedMs = 40_000, distanceM = along, maxSpeedMps = 31f,
                    startLat = -7.79, startLon = 110.37, endLat = -7.78, endLon = 110.38, startAddress = "Jalan Example 1, Yogyakarta", endAddress = "Jalan Example 2, Yogyakarta",
                    track = com.nativeauto.history.Polyline.encode(points.filterIndexed { i, _ -> i % 5 == 0 }.map { com.nativeauto.nav.GeoPoint(it.latitude, it.longitude) }), bikeId = id,
                ),
                points,
            )
        }
        fun openTripPage(row: String) {
            click("settings_open")
            there("settings_sheet")
            click("settings_cat_TRIP_GARAGE")
            rule.onNodeWithTag(row, useUnmergedTree = true).performScrollTo()
            click(row)
        }
        fun pages(p: String) {
            openTripPage("settings_garage")
            there("garage_dash_row")
            shot("${p}_top")
            rule.onNodeWithTag("garage_fuel_add", useUnmergedTree = true).performScrollTo()
            shot("${p}_prices")
            rule.onNodeWithTag("garage_dash_all", useUnmergedTree = true).performScrollTo()
            shot("${p}_dash")
            first("garage_dash_row").performScrollTo().performClick()
            there("kmpl_dialog"); shot("${p}_kmpl"); click("kmpl_skip"); gone("kmpl_dialog")
            first("garage_refuel_row").performScrollTo().performClick()
            there("garage_refuel_dialog"); shot("${p}_refuel_edit")
            rule.onNodeWithTag("garage_refuel_fuel_row", useUnmergedTree = true).performScrollTo()
            click("garage_refuel_fuel_row"); there("garage_refuel_fuel_tiles"); shot("${p}_fuels")
            click("garage_refuel_fuel_PERTAMAX"); gone("garage_refuel_fuel_tiles")
            rule.onNodeWithTag("garage_refuel_amount", useUnmergedTree = true).performScrollTo().performTextReplacement("2.1")
            click("garage_refuel_save")
            there("garage_review_issue"); shot("${p}_review")
            click("garage_review_fix")
            back("garage_refuel_dialog")
            scrollClick("garage_fuel_add"); there("garage_fuel_dialog"); shot("${p}_fuel_add")
            back("garage_fuel_dialog")
            scrollClick("garage_edit"); there("garage_editor")
            rule.onNodeWithTag("garage_caution_NORMAL", useUnmergedTree = true).performScrollTo()
            shot("${p}_editor")
            back("garage_editor")
            click("garage_back"); gone("garage_screen")
            openTripPage("settings_history")
            there("history_backup")
            click("history_backup"); there("backup_dialog"); shot("${p}_backup")
            click("backup_protect"); there("backup_password")
            rule.onNodeWithTag("backup_password", useUnmergedTree = true).performTextReplacement("gas pol 2026")
            shot("${p}_backup_password")
            back("backup_dialog")
            first("history_trip").performClick()
            there("history_export")
            runCatching { rule.waitUntil(30_000) { count("history_speed_legend") > 0 } }
            shot("${p}_route")
            click("history_back"); click("history_back")
            gone("history_screen")
        }
        try {
            pages("g48_land")
            runBlocking { resetSettings.settings.setOrientationMode(OrientationMode.PORTRAIT) }
            rule.waitUntil(20_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            Thread.sleep(1_500)
            pages("g48_port")
            runBlocking { resetSettings.settings.setThemeMode(com.nativeauto.settings.ThemeMode.LIGHT) }
            Thread.sleep(800)
            pages("g48_light")
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
