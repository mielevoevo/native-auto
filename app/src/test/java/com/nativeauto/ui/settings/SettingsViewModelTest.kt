package com.nativeauto.ui.settings

import com.nativeauto.FakeSettingsRepository
import com.nativeauto.TestClock
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private object SilentSource : SpeedSampleSource {
        override val samples: Flow<SpeedSample> = emptyFlow()
    }

    private val main = StandardTestDispatcher()
    private val settings = FakeSettingsRepository()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.runningTracker() = TripTracker(SilentSource, TestClock(testScheduler), backgroundScope).apply {
        advanceTimeBy(5_000)
        start()
        runCurrent()
    }

    @Test fun resetNeedsTwoTapsWithinThreeSeconds() = runTest(main) {
        val tracker = runningTracker()
        val startedAt = tracker.stats.value.startedAtNanos
        assertNotNull(startedAt)
        val vm = SettingsViewModel(settings, tracker)

        assertFalse("first tap only arms", vm.onResetTripTapped())
        assertTrue(vm.resetArmed.value)
        assertEquals(startedAt, tracker.stats.value.startedAtNanos)

        advanceTimeBy(1_000)
        assertTrue("second tap resets", vm.onResetTripTapped())
        assertFalse(vm.resetArmed.value)
        // 9a: Reset discards the trip; nothing runs until the next Start.
        assertNull(tracker.stats.value.startedAtNanos)
        assertEquals(TripState.Idle, tracker.state.value)
    }

    @Test fun anArmedResetExpires() = runTest(main) {
        val tracker = runningTracker()
        val startedAt = tracker.stats.value.startedAtNanos
        val vm = SettingsViewModel(settings, tracker)

        vm.onResetTripTapped()
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse("disarmed after 3 s", vm.resetArmed.value)
        assertFalse("a late tap only re-arms", vm.onResetTripTapped())
        assertEquals(startedAt, tracker.stats.value.startedAtNanos)
    }

    @Test fun settingsAreWrittenThrough() = runTest(main) {
        val vm = SettingsViewModel(settings, runningTracker())
        vm.setSpeedUnit(SpeedUnit.MPH)
        vm.setShowBattery(false)
        runCurrent()
        assertEquals(SpeedUnit.MPH, settings.speedUnit.value)
        assertFalse(settings.showBattery.value)
    }

    @Test fun resetOnAnIdleTrackerStaysIdle() = runTest(main) {
        val tracker = TripTracker(SilentSource, TestClock(testScheduler), backgroundScope)
        val vm = SettingsViewModel(settings, tracker)
        vm.onResetTripTapped(); vm.onResetTripTapped()
        assertEquals(TripState.Idle, tracker.state.value)
        assertNull(tracker.stats.value.startedAtNanos)
    }
}
