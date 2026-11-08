package com.nativeauto.ui.dashboard

import app.cash.turbine.test
import com.nativeauto.FakeSettingsRepository
import com.nativeauto.device.BatteryMonitor
import com.nativeauto.device.BatteryStatus
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.Place
import com.nativeauto.settings.SavedLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val main = StandardTestDispatcher()
    private val settings = FakeSettingsRepository()

    private class FakeBattery : BatteryMonitor {
        val level = MutableStateFlow(BatteryStatus(80, charging = false))
        var collecting = false
        override val status = level.onStart { collecting = true }.onCompletion { collecting = false }
    }

    private val battery = FakeBattery()

    private class FakeNavigator : Navigator {
        override val state = MutableStateFlow<NavState>(NavState.Idle)
        override fun routeTo(place: Place) = Unit
        override fun start() = Unit
        override fun retry() = Unit
        override fun stop() { state.value = NavState.Idle }
        override fun rename(place: Place) = Unit
    }

    private val navigator = FakeNavigator()
    private fun vm() = DashboardViewModel(settings, battery, navigator)

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun startsFromTheSavedLayout() = runTest(main) {
        val quad = SavedLayout(bigOnLeft = false, split = 0.6f, preset = "QUAD", slots = LayoutCodec.encode(DefaultAssignments))
        settings.savedLayout.value = quad
        val vm = vm()
        assertNull("nothing until DataStore answers", vm.initialLayout.value)
        runCurrent()
        assertEquals(quad, vm.initialLayout.value)

        // Later saves don't move the starting point of a running screen.
        val duo = quad.copy(bigOnLeft = true, split = 0.4f, preset = "DUO")
        vm.saveLayout(duo)
        runCurrent()
        assertEquals(duo, settings.savedLayout.value)
        assertEquals(quad, vm.initialLayout.value)
    }

    @Test fun aBrokenSplitIsRepaired() {
        assertEquals(0.7f, SavedLayout(true, 5f).sanitized().split)
        assertEquals(0.3f, SavedLayout(true, -1f).sanitized().split)
        assertEquals(0.5f, SavedLayout(true, Float.NaN).sanitized().split)
    }

    @Test fun aDamagedLayoutIsRepaired() {
        val fixed = SavedLayout(true, 0.5f, preset = "TRIPLE", slots = "DUO=MAP,MAP,SPEED").sanitized()
        assertEquals("DUO", fixed.preset)
        assertEquals(LayoutCodec.encode(DefaultAssignments), fixed.slots)
    }

    @Test fun theSavedLayoutBecomesTheStartingState() {
        val custom = DefaultAssignments + (LayoutPreset.QUAD to NavQuad)
        val state = SavedLayout(false, 0.4f, "QUAD", LayoutCodec.encode(custom)).toLayoutState()
        assertEquals(LayoutPreset.QUAD, state.preset)
        assertEquals(custom, state.assignments)
        assertFalse(state.bigOnLeft)
        assertEquals(0.4f, state.split, 0f)
        assertNull(state.override)
        // And back: only the user's own layout is saved, never an auto override.
        state.applyAuto(LayoutView(LayoutPreset.DUO, DefaultAssignments.getValue(LayoutPreset.DUO)))
        assertEquals(SavedLayout(false, 0.4f, "QUAD", LayoutCodec.encode(custom)), state.toSaved())
    }

    @Test fun navigationActiveFollowsTheNavigator() = runTest(main) {
        val vm = vm()
        vm.navigationActive.test {
            assertFalse(awaitItem())
            navigator.state.value = NavState.Routing(Place("Home", null, GeoPoint(-6.2, 106.8)))
            assertTrue(awaitItem())
            navigator.stop()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun batteryIsReadOnlyWhileTheIndicatorIsShown() = runTest(main) {
        val vm = vm()
        vm.battery.test {
            assertNull(awaitItem())
            assertEquals(BatteryStatus(80, false), awaitItem())
            assertTrue(battery.collecting)

            battery.level.value = BatteryStatus(79, charging = true)
            assertEquals(BatteryStatus(79, true), awaitItem())

            settings.showBattery.value = false
            assertNull(awaitItem())
            assertFalse("receiver released when hidden", battery.collecting)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
