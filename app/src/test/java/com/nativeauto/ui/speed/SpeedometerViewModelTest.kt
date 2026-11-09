package com.nativeauto.ui.speed

import app.cash.turbine.test
import com.nativeauto.TestClock
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.settings.SavedLayout
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import com.nativeauto.location.LiveLocation
import com.nativeauto.trip.TripState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.nativeauto.settings.ClockFace
import com.nativeauto.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedometerViewModelTest {

    private class ManualSource : SpeedSampleSource {
        val fixes = MutableSharedFlow<SpeedSample>(extraBufferCapacity = 16)
        override val samples: Flow<SpeedSample> = fixes
    }

    private class FakeSettings : SettingsRepository {
        override val speedUnit = MutableStateFlow(SpeedUnit.KMH)
        override suspend fun setSpeedUnit(unit: SpeedUnit) { speedUnit.value = unit }
        override val useFakeGps = MutableStateFlow(false)
        override suspend fun setUseFakeGps(enabled: Boolean) { useFakeGps.value = enabled }
        override val showTraffic = MutableStateFlow(false)
        override suspend fun setShowTraffic(enabled: Boolean) { showTraffic.value = enabled }
        override val showBattery = MutableStateFlow(true)
        override suspend fun setShowBattery(enabled: Boolean) { showBattery.value = enabled }
        override val savedLayout = MutableStateFlow(SavedLayout.Default)
        override suspend fun saveLayout(layout: SavedLayout) { savedLayout.value = layout }
        override val voiceGuidance = MutableStateFlow(true)
        override suspend fun setVoiceGuidance(enabled: Boolean) { voiceGuidance.value = enabled }
        override val themeMode = MutableStateFlow(ThemeMode.DARK)
        override suspend fun setThemeMode(mode: ThemeMode) { themeMode.value = mode }
        override val clockFace = MutableStateFlow(ClockFace.DIGITAL)
        override suspend fun setClockFace(face: ClockFace) { clockFace.value = face }
        override val clockSeconds = MutableStateFlow(false)
        override suspend fun setClockSeconds(enabled: Boolean) { clockSeconds.value = enabled }
        override val autoPauseMinutes = MutableStateFlow(5)
        override suspend fun setAutoPauseMinutes(minutes: Int) { autoPauseMinutes.value = minutes }
        override val autoEndMinutes = MutableStateFlow(30)
        override suspend fun setAutoEndMinutes(minutes: Int) { autoEndMinutes.value = minutes }
        override val autoLayout = MutableStateFlow(true)
        override suspend fun setAutoLayout(enabled: Boolean) { autoLayout.value = enabled }
        override val simulateNavigation = MutableStateFlow(false)
        override suspend fun setSimulateNavigation(enabled: Boolean) { simulateNavigation.value = enabled }
    }

    private class FakePermission(granted: Boolean = true) : LocationPermissionState {
        override val granted = MutableStateFlow(granted)
        override fun refresh() = Unit
    }

    private val permission = FakePermission()

    private val main = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    /** runTest picks up the Main test dispatcher's scheduler, so viewModelScope shares virtual time. */
    private fun test(block: suspend TestScope.(ManualSource, FakeSettings, SpeedometerViewModel, TestClock) -> Unit) =
        runTest {
            val clock = TestClock(testScheduler)
            val source = ManualSource()
            val settings = FakeSettings()
            val tracker = TripTracker(source, clock, backgroundScope)
            tracker.start()
            runCurrent() // let the tracker subscribe before tests emit (SharedFlow drops unobserved fixes)
            block(source, settings, SpeedometerViewModel(tracker, clock, settings, permission, LiveLocation(tracker, source, backgroundScope)), clock)
        }

    @Test fun `with no trip the hero shows the live speed and the trip stays at zero`() = runTest {
        val clock = TestClock(testScheduler)
        val source = ManualSource()
        val tracker = TripTracker(source, clock, backgroundScope) // never started: Idle
        val vm = SpeedometerViewModel(tracker, clock, FakeSettings(), permission, LiveLocation(tracker, source, backgroundScope))
        vm.ui.test {
            runCurrent()
            advanceTimeBy(1_000)
            source.fixes.emit(fixAt(clock, 25f)); runCurrent()
            val ui = expectMostRecentItem()
            assertEquals(TripState.Idle, ui.tripState)
            assertEquals(90, ui.speed) // 25 m/s
            assertEquals(0.0, ui.distance, 0.0)
            assertEquals(0, ui.maxSpeed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun fixAt(clock: TestClock, speed: Float) = SpeedSample(clock.nowNanos(), speed, 0.3f, 4f, 51.0, 0.0)

    @Test fun `shows live speed then dashes once the GPS goes quiet`() = test { source, _, vm, clock ->
        vm.ui.test {
            runCurrent()
            val searching = expectMostRecentItem()
            assertNull(searching.speed)
            assertEquals(GpsStatus.Searching, searching.gps)

            source.fixes.emit(fixAt(clock, 27.78f)); runCurrent()
            val live = expectMostRecentItem()
            assertEquals(100, live.speed)
            assertEquals(GpsStatus.Fix, live.gps)
            assertEquals(true, live.isTracking)

            // No fixes for ~4 s: the 1 Hz ticker (aligned to trip seconds, so it fires at
            // 1.001 s, 2.001 s, …) re-evaluates and applies the stale rule.
            advanceTimeBy(4_100); runCurrent()
            val stale = expectMostRecentItem()
            assertNull(stale.speed)
            assertEquals(GpsStatus.Stale, stale.gps)
            assertEquals(4.seconds, stale.elapsed)

            source.fixes.emit(fixAt(clock, 13.89f)); runCurrent()
            val back = expectMostRecentItem()
            assertEquals(50, back.speed)
            assertEquals(GpsStatus.Fix, back.gps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `elapsed ticks once per second without new fixes`() = test { _, _, vm, _ ->
        vm.ui.test {
            advanceTimeBy(2_500); runCurrent()
            assertEquals(2.seconds, expectMostRecentItem().elapsed)
            advanceTimeBy(1_000); runCurrent()
            assertEquals(3.seconds, expectMostRecentItem().elapsed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `revoked access shows Denied and blanks the speed`() = test { source, _, vm, clock ->
        vm.ui.test {
            source.fixes.emit(fixAt(clock, 27.78f)); runCurrent()
            assertEquals(100, expectMostRecentItem().speed)
            permission.granted.value = false; runCurrent()
            val denied = expectMostRecentItem()
            assertNull(denied.speed)
            assertEquals(GpsStatus.PermissionDenied, denied.gps)
            permission.granted.value = true
            source.fixes.emit(fixAt(clock, 27.78f)); runCurrent()
            assertEquals(GpsStatus.Fix, expectMostRecentItem().gps)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `switching the unit re-renders in mph`() = test { source, settings, vm, clock ->
        vm.ui.test {
            source.fixes.emit(fixAt(clock, 27.78f)); runCurrent()
            assertEquals(100, expectMostRecentItem().speed)
            settings.setSpeedUnit(SpeedUnit.MPH); runCurrent()
            val mph = expectMostRecentItem()
            assertEquals(62, mph.speed)
            assertEquals(SpeedUnit.MPH, mph.unit)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
