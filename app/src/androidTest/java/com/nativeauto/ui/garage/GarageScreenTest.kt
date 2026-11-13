package com.nativeauto.ui.garage

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.di.SettingsEntryPoint
import com.nativeauto.garage.AWAY_AFTER_MS
import com.nativeauto.garage.BikeDraft
import com.nativeauto.garage.BikeModel
import com.nativeauto.garage.DEFAULT_KM_PER_L
import com.nativeauto.garage.FuelType
import com.nativeauto.history.TripRecord
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 11a: the Garage on the real activity: adding a bike, the odometer, switching, history by bike. */
@RunWith(AndroidJUnit4::class)
class GarageScreenTest {

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
    private val seededTrips = mutableListOf<Long>()

    @After fun cleanUp() = runBlocking {
        seededTrips.forEach { entry.tripDao().delete(it) }
        entry.garageDao().clear()
        entry.garage().dismissPrompt()
    }

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true)
    private fun click(t: String) { tag(t).performClick(); rule.waitForIdle() }
    /** For buttons further down the bike detail (it scrolls): a tap off screen lands nowhere. */
    private fun scrollClick(t: String) { tag(t).performScrollTo().performClick(); rule.waitForIdle() }
    private fun await(t: String) = rule.waitUntil(5_000) { count(t).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGone(t: String) = rule.waitUntil(5_000) { count(t).fetchSemanticsNodes().isEmpty() }
    private fun awaitText(text: String) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun SemanticsNodeInteraction.fetchText(): String =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun SemanticsNodeInteraction.fieldText(): String =
        fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun openGarage() {
        click("settings_open")
        await("settings_sheet")
        click("settings_cat_TRIP_GARAGE")
        click("settings_garage")
        await("garage_screen")
    }

    private val nmax = BikeDraft("Gen 1", BikeModel.NMAX_GEN1, 6.6, 1.3, FuelType.PERTAMAX, DEFAULT_KM_PER_L)

    @Test fun addingTheFirstBikeSetsItsOdometerAndMakesItTheOneRidden() {
        openGarage()
        click("garage_add_NMAX_GEN1")
        await("garage_editor")
        assertEquals("6.6", tag("garage_tank").fieldText())
        tag("garage_name").performTextReplacement("Gen 1")
        tag("garage_odo_input").performTextReplacement("12.345") // Indonesian thousands
        click("garage_save")
        awaitGone("garage_editor")

        await("garage_bike_Gen 1")
        tag("garage_riding_Gen 1").assertIsDisplayed()
        rule.waitUntil(5_000) { tag("garage_odometer").fetchText() == "12,345 km" }
        count("garage_reading").assertCountEquals(1)

        // The settings row names the bike being ridden.
        click("garage_back")
        awaitGone("garage_screen")
        click("settings_open")
        await("settings_sheet")
        click("settings_cat_TRIP_GARAGE")
        rule.waitUntil(5_000) { tag("settings_garage_bike").fetchText() == "Riding Gen 1" }
    }

    @Test fun aCorrectionIsLoggedAndASwitchAfterDaysAsksForAwayKm() {
        val garage = entry.garage()
        val dao = entry.garageDao()
        val gen2 = runBlocking {
            garage.addBike(nmax, odometerKm = 12_000.0)
            val gen2 = garage.addBike(nmax.copy(name = "Gen 2", model = BikeModel.NMAX_GEN2), odometerKm = 30_000.0)
            // Gen 2 sat at home for a week.
            dao.updateBike(dao.bike(gen2)!!.copy(lastUsedAtMs = System.currentTimeMillis() - AWAY_AFTER_MS - 86_400_000))
            gen2
        }
        openGarage()
        await("garage_bike_Gen 1")

        // Correct Gen 1's reading.
        click("garage_correct")
        await("garage_odometer_dialog")
        tag("garage_odo_input").performTextReplacement("12100")
        click("garage_odo_save")
        awaitGone("garage_odometer_dialog")
        rule.waitUntil(5_000) { tag("garage_odometer").fetchText() == "12,100 km" }
        awaitText("Corrected")

        // Switch to Gen 2: it asks for its odometer, prefilled with the estimate.
        click("garage_bike_Gen 2")
        click("garage_ride")
        await("garage_odometer_dialog")
        assertEquals("30000", tag("garage_odo_input").fieldText())
        tag("garage_odo_input").performTextReplacement("30230")
        click("garage_odo_save")
        awaitGone("garage_odometer_dialog")
        tag("garage_riding_Gen 2").assertIsDisplayed()
        rule.waitUntil(5_000) { tag("garage_odometer").fetchText() == "30,230 km" }
        awaitText("Away +230 km")
        assertEquals("AWAY", runBlocking { dao.lastReading(gen2)!!.kind })
    }

    // ---- 11b ----

    @Test fun aFullFillUpStartsTheRangeAndCostsStayHiddenUntilAsked() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        await("garage_bike_Gen 1")
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "—" }

        click("garage_refuel")
        await("garage_refuel_dialog")
        // The odometer is prefilled with the estimate, the price with the fuel's default.
        assertEquals("12000", tag("garage_refuel_odo").fieldText())
        assertEquals("15950", tag("garage_refuel_price").fieldText())
        tag("garage_refuel_amount").performTextReplacement("50.000") // Rp, Indonesian thousands
        rule.waitUntil(5_000) { tag("garage_refuel_result").fetchText() == "= 3.1 L" }
        click("garage_refuel_save")
        awaitGone("garage_refuel_dialog")

        // Full tank: 6.6 L × 36 km/L.
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "235 km" }
        count("garage_refuel_row").assertCountEquals(1)
        // Accepting the prefilled odometer logs no correction.
        count("garage_reading").assertCountEquals(1)
        // No money anywhere until asked.
        count("garage_cost").assertCountEquals(0)
        scrollClick("garage_cost_toggle")
        await("garage_cost")
        rule.waitUntil(5_000) { tag("garage_refill_estimate").fetchText() == "Next refill ≈ 0.0 L × Rp 15.950 = Rp 0" }

        // Delete needs two taps.
        scrollClick("garage_refuel_delete")
        count("garage_refuel_row").assertCountEquals(1)
        scrollClick("garage_refuel_delete")
        rule.waitUntil(5_000) { count("garage_refuel_row").fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "—" }
    }

    @Test fun aPriceEditedInTheGarageIsTheNextRefuelsPrice() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        await("garage_bike_Gen 1")
        rule.waitUntil(5_000) { tag("garage_price_value_PERTAMAX_GREEN").fetchText() == "Rp 19.150 / L" }
        scrollClick("garage_price_PERTAMAX")
        await("garage_price_dialog")
        assertEquals("15950", tag("garage_price_input").fieldText())
        tag("garage_price_input").performTextReplacement("16.200")
        click("garage_price_save")
        awaitGone("garage_price_dialog")
        rule.waitUntil(5_000) { tag("garage_price_value_PERTAMAX").fetchText() == "Rp 16.200 / L" }
        scrollClick("garage_refuel")
        await("garage_refuel_dialog")
        assertEquals("16200", tag("garage_refuel_price").fieldText())
    }

    @Test fun theGaugeSetsALevelBeforeTheFirstFullFill() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "—" }
        scrollClick("garage_set_level")
        await("garage_level_dialog")
        // NMAX gen 1: six bars, 0 to 6; the odometer prefilled.
        count("garage_level_bar_6").assertCountEquals(1)
        count("garage_level_bar_7").assertCountEquals(0)
        assertEquals("12000", tag("garage_level_odo").fieldText())
        click("garage_level_bar_3")
        rule.waitUntil(5_000) { tag("garage_level_litres").fetchText().let { it.startsWith("About 2.") && it.endsWith(" of 6.6 L") } }
        click("garage_level_save")
        awaitGone("garage_level_dialog")
        // 2.75 L × 36 km/L = 99 km, rounded down to 95.
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "95 km" }
        rule.waitUntil(5_000) { tag("garage_fuel_line").fetchText().contains("from the gauge") }
    }

    @Test fun untrackedKmAddToTheOdometerOnceItIsSet() {
        runBlocking {
            entry.garage().addBike(nmax, odometerKm = 12_000.0)
            entry.garage().addBike(nmax.copy(name = "Gen 2"), odometerKm = null)
        }
        openGarage()
        await("garage_bike_Gen 1")
        click("garage_bike_Gen 2")
        rule.waitUntil(5_000) { tag("garage_odometer").fetchText() == "Not set" }
        count("garage_untracked").assertCountEquals(0)

        click("garage_bike_Gen 1")
        await("garage_untracked")
        click("garage_untracked")
        await("garage_untracked_dialog")
        tag("garage_untracked_input").performTextReplacement("42")
        click("garage_untracked_save")
        awaitGone("garage_untracked_dialog")
        rule.waitUntil(5_000) { tag("garage_odometer").fetchText() == "12,042 km" }
        awaitText("Untracked +42 km")
    }

    // ---- 11c ----

    /** A saved trip of [km] on [bikeId], ending just now (so it counts after any reading). */
    private fun seedTrip(bikeId: String, km: Double) = runBlocking {
        Thread.sleep(20)
        val now = System.currentTimeMillis()
        seededTrips += entry.tripDao().insertWithTrack(
            TripRecord(
                startedAtMs = now - 3_600_000, endedAtMs = now, durationMs = 3_600_000, movingMs = 3_600_000,
                distanceM = km * 1000, maxSpeedMps = 20f, startLat = -7.8, startLon = 110.37, endLat = -7.79, endLon = 110.37,
                track = "", bikeId = bikeId,
            ),
            emptyList(),
        )
    }

    @Test fun aServiceItemIsAddedComesDueAndIsMarkedDone() {
        val gen1 = runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        await("garage_bike_Gen 1")
        scrollClick("garage_service_add")
        await("garage_service_editor")
        tag("garage_service_name").performTextReplacement("Engine oil")
        tag("garage_service_km").performTextReplacement("2000")
        click("garage_service_save")
        awaitGone("garage_service_editor")
        await("garage_service_Engine oil")
        awaitText("due in 2,000 km")

        seedTrip(gen1, 1_950.0)
        awaitText("due in 50 km")

        scrollClick("garage_service_done_Engine oil")
        await("garage_service_done_dialog")
        assertEquals("13950", tag("garage_service_done_odo").fieldText())
        tag("garage_service_done_cost").performTextReplacement("55.000")
        click("garage_service_done_save")
        awaitGone("garage_service_done_dialog")
        awaitText("due in 2,000 km")
        await("garage_service_record")
        awaitText("Rp 55.000")
    }

    @Test fun aDueServiceShowsOnTheLogFace() {
        runBlocking {
            val gen1 = entry.garage().addBike(nmax, odometerKm = 12_000.0)
            entry.garage().addService(gen1, com.nativeauto.garage.ServiceDraft("Engine oil", com.nativeauto.garage.ServiceKind.DISTANCE, 2_000.0, null, null, null))
            gen1
        }.let { seedTrip(it, 1_950.0) }
        tag("speed_faces").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { count("service_reminder").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Engine oil due in 50 km", tag("service_reminder").fetchText())
    }

    @Test fun theGarageCardShowsRangeAndTheSpeedCardWarnsOfLowFuel() {
        runBlocking {
            val gen1 = entry.garage().addBike(nmax, odometerKm = 12_000.0)
            entry.garage().logRefuel(gen1, com.nativeauto.garage.RefuelDraft(true, 5.0, 15_900.0, 79_500.0, FuelType.PERTAMAX, 12_000.0))
            gen1
        }.let { seedTrip(it, 200.0) } // 6.6 L − 200 km ÷ 36 km/L = 1.04 L: the last segment
        // Duo shows no Garage card: the speed card carries the small red tank.
        rule.waitUntil(5_000) { count("low_fuel").fetchSemanticsNodes().isNotEmpty() }

        // Put the Garage card in Media's slot.
        tag("header_MEDIA").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            advanceEventTime(2_100) // past the 1 s hold and the 0.7 s arming (13a): let go without moving = the picker
            up()
        }
        await("picker_sheet")
        click("pick_GARAGE")
        awaitGone("picker_sheet")
        rule.waitUntil(5_000) { count("garage_card_value").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("35 km", tag("garage_card_value").fetchText()) // 37.6, rounded down to 5 km (12b)
        awaitText("Garage · Gen 1")
        await("garage_tank")
        // The card shows it now, so the speed card doesn't.
        awaitGone("low_fuel")

        // Maximized: the full set, and the choice of what the small card shows.
        click("maximize_GARAGE")
        await("garage_card_full")
        scrollClick("garage_value_ODOMETER")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.garageValue.first() } == com.nativeauto.settings.GarageValue.ODOMETER }
        // Refuel from the card.
        click("garage_card_refuel")
        await("garage_refuel_dialog")
        tag("garage_refuel_amount").performTextReplacement("88.000")
        click("garage_refuel_save")
        awaitGone("garage_refuel_dialog")
        click("maximize_GARAGE")
        rule.waitUntil(5_000) { count("garage_card_value").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { tag("garage_card_value").fetchText() == "12,200 km" }
    }

    @Test fun historyFiltersByBike() {
        val garage = entry.garage()
        val gen1: String
        val gen2: String
        runBlocking {
            gen1 = garage.addBike(nmax, odometerKm = null)
            gen2 = garage.addBike(nmax.copy(name = "Gen 2"), odometerKm = null)
            val now = System.currentTimeMillis()
            listOf(gen1, gen1, gen2).forEachIndexed { i, bike ->
                seededTrips += entry.tripDao().insertWithTrack(
                    TripRecord(
                        startedAtMs = now - 600_000 - i * 1_000_000L, endedAtMs = now - i * 1_000_000L, durationMs = 600_000,
                        movingMs = 600_000, distanceM = 5_000.0, maxSpeedMps = 15f, startLat = -7.8, startLon = 110.37,
                        endLat = -7.79, endLon = 110.37, track = "", bikeId = bike,
                    ),
                    emptyList(),
                )
            }
        }
        click("settings_open")
        await("settings_sheet")
        click("settings_cat_TRIP_GARAGE")
        click("settings_history")
        await("history_bike_all")
        rule.waitUntil(5_000) { count("history_trip").fetchSemanticsNodes().size >= 3 }
        val all = count("history_trip").fetchSemanticsNodes().size
        click("history_bike_Gen 2")
        rule.waitUntil(5_000) { count("history_trip").fetchSemanticsNodes().size == 1 }
        click("history_bike_all")
        rule.waitUntil(5_000) { count("history_trip").fetchSemanticsNodes().size == all }
    }

    // ---- 13a ----

    @Test fun addBikeIsTheLastCardOfTheList() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        await("garage_bike_Gen 1")
        await("garage_add")
        assert(tag("garage_add").getBoundsInRoot().top >= tag("garage_bike_Gen 1").getBoundsInRoot().bottom)
        click("garage_add")
        await("garage_editor")
    }

    @Test fun refuelHasOneFilledToTheTopSwitchAndLitresStopAtTheTank() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        openGarage()
        await("garage_bike_Gen 1")
        click("garage_refuel")
        await("garage_refuel_dialog")
        count("garage_refuel_partial").assertCountEquals(0)
        tag("garage_refuel_full").assertIsOn()
        click("garage_refuel_full")
        tag("garage_refuel_full").assertIsOff()
        click("garage_refuel_full")
        tag("garage_refuel_full").assertIsOn()

        click("garage_refuel_mode_LITRES")
        awaitText("Tank holds 6.6 L")
        tag("garage_refuel_amount").performTextReplacement("6.7")
        rule.waitForIdle()
        assertEquals("over the tank: no result", "Tank holds 6.6 L", tag("garage_refuel_result").fetchText())
        tag("garage_refuel_amount").performTextReplacement("6.6")
        rule.waitUntil(5_000) { tag("garage_refuel_result").fetchText().startsWith("= Rp") }
    }

    @Test fun aBigFillBeyondTheEstimatedRoomCapsTheLevelAtFull() {
        val gen1 = runBlocking {
            val gen1 = entry.garage().addBike(nmax, odometerKm = 12_000.0)
            entry.garage().logRefuel(gen1, com.nativeauto.garage.RefuelDraft(true, 5.0, 15_900.0, 79_500.0, FuelType.PERTAMAX, 12_000.0))
            gen1
        }
        openGarage()
        await("garage_bike_Gen 1")
        rule.waitUntil(5_000) { tag("garage_range").fetchText() == "235 km" }
        // Switch off "filled to the top", then enter 5 L although the tank was just filled.
        click("garage_refuel")
        await("garage_refuel_dialog")
        click("garage_refuel_full")
        tag("garage_refuel_full").assertIsOff()
        click("garage_refuel_mode_LITRES")
        tag("garage_refuel_amount").performTextReplacement("5")
        click("garage_refuel_save")
        awaitGone("garage_refuel_dialog")
        // It still counts as a full anchor: the level is capped, the range stays that of a full tank.
        rule.waitUntil(5_000) { count("garage_refuel_row").fetchSemanticsNodes().size == 2 }
        assertEquals("235 km", tag("garage_range").fetchText())
        val last = runBlocking { entry.garageDao().observeRefuels(gen1).first() }
        assert(last.all { it.full })
    }

    @Test fun thePlainTankIconIsAFixedPumpAndTheChoiceIsRemembered() {
        runBlocking { entry.garage().addBike(nmax, odometerKm = 12_000.0) }
        tag("header_MEDIA").performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            advanceEventTime(2_100)
            up()
        }
        await("picker_sheet")
        click("pick_GARAGE")
        awaitGone("picker_sheet")
        await("garage_tank")
        click("maximize_GARAGE")
        await("garage_card_full")
        scrollClick("garage_tank_icon_PLAIN")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.tankIcon.first() } == com.nativeauto.settings.TankIcon.PLAIN }
        await("garage_tank")
        scrollClick("garage_tank_icon_GAUGE")
        rule.waitUntil(5_000) { runBlocking { resetSettings.settings.tankIcon.first() } == com.nativeauto.settings.TankIcon.GAUGE }
    }
}
