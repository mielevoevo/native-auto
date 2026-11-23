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

    /** 4.3d: the riding-load note under the range (Garage detail). */
    @Test fun loadNote() {
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            com.nativeauto.di.SettingsEntryPoint::class.java,
        )
        val day = 86_400_000L
        val t0 = System.currentTimeMillis() - 10 * day
        val trips = mutableListOf<Long>()
        runBlocking {
            val id = entry.garage().addBike(
                com.nativeauto.garage.BikeDraft("Gen 1", com.nativeauto.garage.BikeModel.NMAX_GEN1, 6.6, 1.3, com.nativeauto.garage.FuelType.PERTAMAX, com.nativeauto.garage.DEFAULT_KM_PER_L),
                odometerKm = 800.0,
            )
            fun energy(v2: Double) = com.nativeauto.garage.LoadSums(1000.0, v2 * 1000).energyPerKm!!
            fun trip(end: Long, km: Double, v2: Double) = com.nativeauto.history.TripRecord(
                startedAtMs = end - 3_600_000, endedAtMs = end, durationMs = 3_600_000, movingMs = 3_600_000, distanceM = km * 1000,
                maxSpeedMps = 20f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "", bikeId = id,
                loadIndex = energy(v2), loadDistanceM = km * 1000, loadAero = v2 * km * 1000, loadAccel = 0.0,
            )
            fun fill(i: Int, litres: Double) = com.nativeauto.garage.RefuelRecord(
                bikeId = id, timeMs = t0 + i * day, odometerKm = i * 200.0, litres = litres, pricePerL = 12_500.0,
                totalRp = litres * 12_500, full = true, fuelType = "PERTAMAX",
            )
            entry.garageDao().insertRefuel(fill(0, 5.0))
            listOf(100.0, 200.0, 300.0, 400.0).forEachIndexed { i, v2 ->
                trips += entry.tripDao().insert(trip(t0 + i * day + day / 2, 200.0, v2))
                entry.garageDao().insertRefuel(fill(i + 1, 200.0 / (48 - 0.015 * energy(v2))))
            }
            trips += entry.tripDao().insert(trip(t0 + 4 * day + 60_000, 20.0, 400.0))
        }
        click("settings_open")
        rule.waitUntil(5_000) { count("settings_sheet") > 0 }
        click("settings_garage")
        rule.waitUntil(5_000) { count("garage_load_note") > 0 }
        shot("load_note_garage")
        runBlocking { trips.forEach { entry.tripDao().delete(it) }; entry.garageDao().clear() }
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
