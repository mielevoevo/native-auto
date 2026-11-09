package com.nativeauto.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under test. */
internal const val PACKAGE = "com.nativeauto"

private const val WAIT_MS = 10_000L

/**
 * Grants what the dashboard asks for on a fresh install, so no system dialog covers it: location,
 * notifications, and media-notification access.
 */
internal fun MacrobenchmarkScope.grantPermissions() {
    listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
    ).forEach { device.executeShellCommand("pm grant $PACKAGE $it") }
    device.executeShellCommand("cmd notification allow_listener $PACKAGE/$PACKAGE.media.MediaListenerService")
}

/** Starts the app and waits until the dashboard is drawn. */
internal fun MacrobenchmarkScope.startDashboard() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.res("settings_open")), WAIT_MS)
}

/**
 * Starts on the default Duo layout whatever was saved last, so every run measures the same moves
 * (the map's column resizes on maximize). Layout choices persist across launches.
 */
internal fun MacrobenchmarkScope.startDashboardInDuo() {
    startDashboard()
    if (!device.hasObject(By.res("maximize_SPEED"))) switchLayout("DUO")
}

private fun MacrobenchmarkScope.find(tag: String): UiObject2 =
    device.wait(Until.findObject(By.res(tag)), WAIT_MS) ?: error("No '$tag' on screen")

private fun MacrobenchmarkScope.tapAndSettle(tag: String) {
    find(tag).click()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.awaitGone(tag: String) {
    device.wait(Until.gone(By.res(tag)), WAIT_MS)
}

/** Maximize the speed card and back: the map's column resizes (Duo). */
internal fun MacrobenchmarkScope.maximizeAndBack() {
    tapAndSettle("maximize_SPEED")
    tapAndSettle("maximize_SPEED")
}

/** Swap the two stacked panes and back. */
internal fun MacrobenchmarkScope.swapAndBack() {
    tapAndSettle("swap_MEDIA")
    tapAndSettle("swap_MEDIA")
}

/** Open the settings sheet and close it. */
internal fun MacrobenchmarkScope.openAndCloseSettings() {
    tapAndSettle("settings_open")
    find("settings_done").click()
    awaitGone("settings_sheet")
}

/** Switch to [preset] through the layouts sheet. */
internal fun MacrobenchmarkScope.switchLayout(preset: String) {
    tapAndSettle("layouts_open")
    find("preset_$preset").click()
    awaitGone("layouts_sheet")
    device.waitForIdle()
}

/** Every move the rider makes at a stop, ending where it started (Duo). */
internal fun MacrobenchmarkScope.fullJourney() {
    maximizeAndBack()
    swapAndBack()
    openAndCloseSettings()
    switchLayout("QUAD")
    switchLayout("ROWS")
    switchLayout("DUO")
}
