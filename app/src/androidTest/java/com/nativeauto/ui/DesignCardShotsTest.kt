package com.nativeauto.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nativeauto.history.TripOutcome
import com.nativeauto.history.TripRecord
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.Instruction
import com.nativeauto.nav.ManeuverKind
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Place
import com.nativeauto.nav.TurnDirection
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.TripState
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.nav.TurnFace
import com.nativeauto.ui.speed.SpeedCardContent
import com.nativeauto.ui.speed.SpeedometerUiState
import com.nativeauto.ui.speed.TripLogActions
import com.nativeauto.ui.speed.TripLogUi
import com.nativeauto.ui.theme.NativeAutoTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 4.3: single cards in a landscape frame, for the `DESIGN.md` review (see [DesignShotsTest]). */
@RunWith(AndroidJUnit4::class)
class DesignCardShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("designShots") == "1")
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
        Thread.sleep(2_000)
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_200)
        val p = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/design/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { it.readBytes() }
    }

    private fun frame(w: Dp, h: Dp, content: @Composable () -> Unit) = rule.setContent {
        NativeAutoTheme {
            Box(Modifier.background(Color.Black).padding(8.dp).size(w, h)) {
                // The card's own surface gives the text its colour (the dashboard's DashboardCard does the same).
                androidx.compose.material3.Surface(color = Color.Black, contentColor = com.nativeauto.ui.theme.AppTheme.colors.text) { content() }
            }
        }
    }

    private val trip = TripRecord(
        id = 7, startedAtMs = 0, endedAtMs = 600_000, durationMs = 600_000, movingMs = 500_000, distanceM = 8_000.0,
        maxSpeedMps = 15f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "",
    )

    @Test fun rowsSummaryWithTheDashTile() {
        val ended = SpeedometerUiState.Empty.copy(tripState = TripState.Ended, distance = 8.0, unit = SpeedUnit.KMH)
        frame(860.dp, 180.dp) {
            SpeedCardContent(
                CardMode.Wide, DashboardLayoutState(), ended,
                TripLogUi(outcome = TripOutcome.Saved(trip), bikeName = "Gen 1"),
                TripLogActions.None,
            )
        }
        shot("card_rows_summary")
    }

    @Test fun fullscreenNavigation() {
        val nav = NavState.Navigating(
            destination = Place("Malioboro", null, GeoPoint(-7.7926, 110.3655)),
            geometry = emptyList(),
            instruction = Instruction("Jalan Cornelis Simanjuntak", ManeuverKind.Turn, TurnDirection.Left),
            nextInstruction = Instruction("Turn left", ManeuverKind.Turn, TurnDirection.Left),
            distanceToManeuverM = 60.0,
            distanceRemainingM = 150.0,
            durationRemainingS = 60.0,
            position = null,
            offRoute = false,
            rerouting = false,
        )
        frame(860.dp, 340.dp) { TurnFace(nav, SpeedUnit.KMH, CardMode.Fullscreen, portrait = false, onEnd = {}) }
        shot("card_nav_fullscreen")
    }
}
