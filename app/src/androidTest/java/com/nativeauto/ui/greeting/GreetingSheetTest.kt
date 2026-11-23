package com.nativeauto.ui.greeting

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.di.SettingsEntryPoint
import com.nativeauto.garage.BikeDraft
import com.nativeauto.garage.BikeModel
import com.nativeauto.garage.DEFAULT_KM_PER_L
import com.nativeauto.garage.FuelType
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 4.3c: the daily greeting on the real activity. "A new day" = the stored day cleared, "an app open" = back to STARTED. */
@RunWith(AndroidJUnit4::class)
class GreetingSheetTest {
    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()
    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    private val entry = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        SettingsEntryPoint::class.java,
    )

    @After fun cleanUp() = runBlocking {
        entry.garageDao().clear()
        entry.greetingStore().markGreeted(java.time.LocalDate.now())
    }

    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(t: String) { rule.onNodeWithTag(t, useUnmergedTree = true).performClick(); rule.waitForIdle() }

    /** Leave and come back, as switching apps does. */
    private fun reopen() {
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitForIdle()
    }

    private fun newDay() = runBlocking { entry.greetingStore().clear() }

    @Test fun greetsOnceADayWithAWayIntoTheGarageWhenThereIsNoBike() {
        assertEquals("today was marked greeted", 0, count("greeting_sheet"))
        newDay()
        reopen()
        rule.waitUntil(5_000) { count("greeting_sheet") == 1 }
        assertEquals(1, count("greeting_add_bike"))
        assertEquals(0, count("greeting_range"))
        click("greeting_done")
        rule.waitUntil(5_000) { count("greeting_sheet") == 0 }
        // The same day: not again.
        reopen()
        Thread.sleep(1_000)
        rule.waitForIdle()
        assertEquals(0, count("greeting_sheet"))
    }

    @Test fun withABikeItLeadsWithTheRange() {
        runBlocking {
            entry.garage().addBike(BikeDraft("Gen 1", BikeModel.NMAX_GEN1, 6.6, 1.3, FuelType.PERTAMAX, DEFAULT_KM_PER_L), odometerKm = 12_000.0)
        }
        newDay()
        reopen()
        rule.waitUntil(5_000) { count("greeting_range") == 1 }
        listOf("greeting_title", "greeting_bike", "greeting_odometer", "greeting_week").forEach { assertEquals(it, 1, count(it)) }
        assertEquals(0, count("greeting_add_bike"))
        click("greeting_done")
        rule.waitUntil(5_000) { count("greeting_sheet") == 0 }
    }

    @Test fun turnedOffItStaysAway() {
        runBlocking { resetSettings.settings.setDailyGreeting(false) }
        rule.waitUntil(5_000) { runBlocking { !resetSettings.settings.dailyGreeting.first() } }
        newDay()
        reopen()
        Thread.sleep(1_000)
        rule.waitForIdle()
        assertEquals(0, count("greeting_sheet"))
        assertTrue(runBlocking { entry.greetingStore().lastDay() } == null)
    }
}
