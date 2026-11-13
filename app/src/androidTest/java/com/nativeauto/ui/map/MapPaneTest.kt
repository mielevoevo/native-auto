package com.nativeauto.ui.map

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.ui.dashboard.LayoutPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

/** Phase 5: rule #3 (the map is never recreated by a layout change) and follow / recenter. */
@RunWith(AndroidJUnit4::class)
class MapPaneTest {

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
    private fun mapInstances() = MapDiagnostics.instances(rule.activity)

    /**
     * Compose's synthetic swipe reaches the Google map (a Compose wrapper) but not MapLibre's
     * gesture detector on a plain View; Espresso injects real input events, which it accepts.
     */
    private fun pan() = when (MapEngine.current) {
        MapEngine.Google -> tag("map").performTouchInput { swipeLeft() }
        MapEngine.OpenFreeMap -> {
            Espresso.onView(ViewMatchers.isAssignableFrom(MapView::class.java)).perform(ViewActions.swipeLeft())
            rule.waitForIdle()
        }
    }

    private fun awaitMap() = rule.waitUntil(timeoutMillis = 20_000) { mapInstances() >= 1 }

    @Test fun mapSurvivesEveryLayoutTransition() {
        awaitMap()
        assertEquals(1, mapInstances())

        click("swap_MAP")                      // map to the right
        click("fullscreen_SPEED"); click("fullscreen_SPEED")
        click("maximize_MEDIA"); click("maximize_MEDIA")
        click("maximize_SPEED")
        click("maximize_SPEED")
        click("fullscreen_MAP")
        Espresso.pressBack(); rule.waitForIdle()
        click("settings_open")                 // map width presets live in settings
        listOf(40, 60, 50).forEach { click("settings_split_$it") }
        click("settings_done")
        rule.waitUntil(5_000) { count("settings_sheet") == 0 }
        click("swap_MAP")                      // and back to the left

        assertEquals("map recreated by a layout change", 1, mapInstances())
        assertEquals(1, count("map"))
    }

    @Test fun trafficToggleFlipsAndIsRemembered() {
        assumeTrue("traffic needs the Google map (a MAPS_API_KEY)", MapEngine.current.hasTraffic)
        awaitMap()
        fun traffic() = tag("map_traffic").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val before = traffic()
        click("map_traffic")
        rule.waitUntil(timeoutMillis = 5_000) { traffic() != before }
        val after = traffic()

        rule.activityRule.scenario.recreate()
        awaitMap()
        assertEquals("traffic setting survives a restart", after, traffic())

        click("map_traffic") // leave the device as we found it
        rule.waitUntil(timeoutMillis = 5_000) { traffic() == before }
    }

    @Test fun noTrafficButtonWithoutTraffic() {
        assumeTrue(!MapEngine.current.hasTraffic)
        awaitMap()
        assertEquals(0, count("map_traffic"))
    }

    @Test fun panningStopsFollowingAndRecenterResumesIt() {
        awaitMap()
        assertEquals(0, count("map_recenter"))
        pan()
        rule.waitUntil(timeoutMillis = 5_000) { count("map_recenter") == 1 }
        click("map_recenter")
        rule.waitUntil(timeoutMillis = 5_000) { count("map_recenter") == 0 }
    }

    private fun switchLayout(preset: LayoutPreset) {
        click("layouts_open")
        rule.waitUntil(5_000) { count("layouts_sheet") == 1 }
        click("preset_${preset.name}")
        rule.waitUntil(5_000) { count("layouts_sheet") == 0 }
    }

    @After fun restoreGrace() { MapSleep.defaultGraceMs = MapSleep.GRACE_MS }

    /** Rule #3 still holds for anything shorter than the grace period. */
    @Test fun aQuickLayoutPeekKeepsTheMap() {
        awaitMap()
        switchLayout(LayoutPreset.SIDE_BY_SIDE) // no map in this layout
        switchLayout(LayoutPreset.DUO)
        assertEquals("map recreated by a quick layout switch", 1, mapInstances())
        assertEquals(0, count("map_asleep"))
    }

    /** The documented exception: off screen past the grace period, the map view is released. */
    @Test fun anOffScreenMapSleepsAndWakes() {
        MapSleep.defaultGraceMs = 1_000
        awaitMap()
        switchLayout(LayoutPreset.SIDE_BY_SIDE)
        rule.waitUntil(10_000) { count("map_asleep") == 1 }
        assertEquals("map view released", 0, count("map"))

        switchLayout(LayoutPreset.DUO)
        rule.waitUntil(20_000) { mapInstances() == 2 }
        assertEquals(1, count("map"))
        assertEquals(0, count("map_asleep"))
    }
}
