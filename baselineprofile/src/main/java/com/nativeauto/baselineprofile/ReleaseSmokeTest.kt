package com.nativeauto.baselineprofile

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The minified **release-style** app (`benchmarkRelease`: same R8 setup as release), driven from
 * outside through every screen. Added after v3.2's release APK crashed at start (R8 moved Kotlin
 * classes away from their package) while every device test passed on the debug build. Run before
 * handing out an APK:
 *
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.nativeauto.baselineprofile.ReleaseSmokeTest`
 *
 * Fails on any crash of the app process (Android's crash log), not only on a missing screen.
 * Search and routing need the network; without it those steps are skipped.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReleaseSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun shell(cmd: String): String = device.executeShellCommand(cmd)

    private fun find(tag: String, timeoutMs: Long = WAIT_MS): UiObject2 =
        device.wait(Until.findObject(By.res(tag)), timeoutMs) ?: fail("No '$tag' on screen")

    private fun has(tag: String, timeoutMs: Long = 2_000) = device.wait(Until.hasObject(By.res(tag)), timeoutMs) == true

    private fun tap(tag: String) {
        find(tag).click()
        device.waitForIdle()
    }

    private fun gone(tag: String) {
        assertTrue("'$tag' still on screen", device.wait(Until.gone(By.res(tag)), WAIT_MS))
    }

    private fun type(tag: String, text: String) {
        find(tag).text = text
        device.waitForIdle()
    }

    /** Scrolls the first scrollable container down until [tag] shows, then taps it. */
    private fun scrollTap(tag: String) {
        if (!has(tag, 1_000)) {
            val scroller = device.findObject(By.scrollable(true)) ?: fail("Nothing scrolls to reach '$tag'")
            scroller.scrollUntil(Direction.DOWN, Until.findObject(By.res(tag)))
        }
        tap(tag)
    }

    private fun fail(message: String): Nothing {
        assertNoCrash()
        throw AssertionError(message)
    }

    private fun assertNoCrash() {
        val crashes = shell("logcat -d -b crash")
        val ours = crashes.lines().dropWhile { !it.contains("Process: $PACKAGE,") }.take(25).joinToString("\n")
        assertEquals("The app crashed:\n$ours", "", ours)
    }

    private fun launch() {
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(PACKAGE)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.context.startActivity(intent)
        find("settings_open", 30_000)
    }

    @Before fun freshInstall() {
        shell("am force-stop $PACKAGE")
        shell("pm clear $PACKAGE")
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { shell("pm grant $PACKAGE $it") }
        shell("cmd notification allow_listener $PACKAGE/$PACKAGE.media.MediaListenerService")
        shell("logcat -b crash -c")
    }

    @Test fun everyScreenOpensWithoutACrash() {
        launch()

        // Settings, trip options, theme (restyles the map), back to dark.
        tap("settings_open")
        find("settings_sheet")
        tap("settings_theme_LIGHT")
        tap("settings_trip_options")
        tap("settings_trip_options_done")
        tap("settings_theme_DARK")
        tap("settings_done")
        gone("settings_sheet")

        // Garage: a bike, a fill-up, a service item marked done.
        tap("settings_open")
        tap("settings_garage")
        find("garage_screen")
        tap("garage_add_NMAX_GEN1")
        find("garage_editor")
        type("garage_name", "Gen 1")
        type("garage_odo_input", "12345")
        tap("garage_save")
        gone("garage_editor")
        find("garage_bike_Gen 1")
        scrollTap("garage_refuel")
        find("garage_refuel_dialog")
        type("garage_refuel_amount", "50000")
        tap("garage_refuel_save")
        gone("garage_refuel_dialog")
        scrollTap("garage_service_add")
        find("garage_service_editor")
        type("garage_service_name", "Engine oil")
        type("garage_service_km", "2000")
        tap("garage_service_save")
        gone("garage_service_editor")
        scrollTap("garage_service_done_Engine oil")
        find("garage_service_done_dialog")
        tap("garage_service_done_save")
        gone("garage_service_done_dialog")
        device.pressBack()
        gone("garage_screen")

        // A trip: start, pause, resume, end (two taps), summary, dismiss.
        find("speed_faces").swipe(Direction.LEFT, 0.8f)
        tap("log_start")
        find("log_pause").let { device.wait(Until.hasObject(By.res("log_pause").enabled(true)), WAIT_MS); it.click() }
        device.waitForIdle()
        tap("log_start")
        tap("log_end")
        tap("log_end")
        tap("trip_summary")
        gone("trip_summary")

        // History, and the backup (Android's share sheet opens over it).
        tap("settings_open")
        tap("settings_history")
        find("history_screen")
        tap("history_backup")
        device.wait(Until.hasObject(By.pkg("android").depth(0)), 5_000)
        if (!has("history_screen", 3_000)) device.pressBack()
        find("history_screen")
        tap("history_back")
        gone("history_screen")

        // Refresh-rate choices (12a).
        tap("settings_open")
        tap("settings_screen_options")
        find("screen_options")
        tap("settings_refresh_POWER_SAVER")
        tap("settings_refresh_SMOOTH")
        tap("settings_refresh_AUTO")
        tap("settings_drag_hold_2")
        tap("settings_drag_hold_1")
        tap("settings_screen_options_done")
        gone("screen_options")
        tap("settings_done")
        gone("settings_sheet")

        // Every layout, then the Garage pane via the picker, maximized and back.
        listOf("QUAD", "ROWS", "SIDE_BY_SIDE", "DUO").forEach { preset ->
            tap("layouts_open")
            tap("preset_$preset")
            gone("layouts_sheet")
        }
        find("header_MEDIA").let { header ->
            val b = header.visibleBounds
            // A hold past 12a's 1 s lift (a swipe of ~5 ms per step), let go without moving: the picker.
            device.swipe(b.left + b.width() / 10, b.centerY(), b.left + b.width() / 10, b.centerY(), 350)
        }
        tap("pick_GARAGE")
        gone("picker_sheet")
        find("garage_card_value")
        tap("maximize_GARAGE")
        find("garage_card_full")
        tap("maximize_GARAGE")

        // Search and a route preview (network permitting), then cancel.
        tap("map_search")
        type("search_field", "Malioboro")
        if (has("search_result", 20_000)) {
            tap("search_result")
            if (has("nav_start", 30_000)) {
                tap("nav_start")
                device.waitForIdle()
                if (has("nav_end", 5_000)) tap("nav_end")
            } else if (has("nav_cancel", 1_000)) {
                tap("nav_cancel")
            }
        } else {
            device.pressBack()
        }

        // Still alive, and nothing crashed along the way.
        device.waitForIdle()
        assertTrue("The app is gone", shell("pidof $PACKAGE").isNotBlank())
        assertNoCrash()
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
