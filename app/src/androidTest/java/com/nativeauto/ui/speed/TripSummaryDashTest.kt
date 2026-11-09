package com.nativeauto.ui.speed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.nativeauto.history.TripOutcome
import com.nativeauto.history.TripRecord
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.TotalPeriod
import com.nativeauto.trip.TripState
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.theme.NativeAutoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 12b: the summary's optional "Bike's km/L" and the log face's period total, on the stateless card. */
class TripSummaryDashTest {
    @get:Rule val rule = createComposeRule()

    private val trip = TripRecord(
        id = 7, startedAtMs = 0, endedAtMs = 600_000, durationMs = 600_000, movingMs = 500_000, distanceM = 8_000.0,
        maxSpeedMps = 15f, startLat = 0.0, startLon = 0.0, endLat = 0.0, endLon = 0.0, track = "",
    )

    @Test fun theSummaryAsksForTheBikesKmPerLOnlyWithABike() {
        var asked = 0
        var log by mutableStateOf(TripLogUi(outcome = TripOutcome.Saved(trip), bikeName = null))
        val ended = SpeedometerUiState.Empty.copy(tripState = TripState.Ended, distance = 8.0)
        rule.setContent {
            NativeAutoTheme {
                SpeedCardContent(
                    CardMode.Expanded, DashboardLayoutState(), ended, log,
                    TripLogActions({}, {}, {}, {}, {}, askDashKmPerL = { asked++ }),
                )
            }
        }
        rule.onAllNodesWithTag("trip_summary").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertEquals(0, rule.onAllNodesWithTag("sum_dash", useUnmergedTree = true).fetchSemanticsNodes().size)
        log = log.copy(bikeName = "Gen 1")
        rule.waitForIdle()
        rule.onNodeWithTag("sum_dash", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        // The button takes the tap; the summary isn't dismissed by it.
        assertEquals(1, asked)
        log = log.copy(dashKmPerL = 42.5)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("sum_dash_saved", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theLogFaceShowsThePeriodTotalInTheSpeedUnit() {
        val idle = SpeedometerUiState.Empty.copy(unit = SpeedUnit.KMH)
        var log by mutableStateOf(TripLogUi(period = TotalPeriod.WEEK, periodM = 42_300.0))
        rule.setContent {
            NativeAutoTheme { LogFace(CardMode.Expanded, idle, log, TripLogActions.None) }
        }
        rule.waitUntil(5_000) { text("log_period") == "This week 42.3 km" }
        log = log.copy(period = TotalPeriod.MONTH, periodM = 120_000.0)
        rule.waitUntil(5_000) { text("log_period") == "This month 120.0 km" }
    }

    private fun text(t: String): String? = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.config?.let { c -> if (androidx.compose.ui.semantics.SemanticsProperties.Text in c) c[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } else null }
}
