package com.nativeauto.ui.speed

import com.nativeauto.TestClock
import com.nativeauto.core.WallClock
import com.nativeauto.history.AddressResolver
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripRecord
import com.nativeauto.history.TripRecorder
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripLogViewModelTest {
    private object Silent : SpeedSampleSource { override val samples: Flow<SpeedSample> = emptyFlow() }

    private class Dao : TripDao {
        val trips = MutableStateFlow<List<TripRecord>>(emptyList())
        override fun observeAll() = trips
        override fun observe(id: Long) = trips.map { l -> l.find { it.id == id } }
        override suspend fun insert(trip: TripRecord): Long {
            trips.value += trip.copy(id = 1L)
            return 1L
        }
        override suspend fun setAddresses(id: Long, start: String?, end: String?) = Unit
        override suspend fun missingAddresses() = emptyList<TripRecord>()
        override suspend fun insertPoints(points: List<com.nativeauto.history.TrackPointRecord>) = Unit
        override suspend fun points(tripId: Long) = emptyList<com.nativeauto.history.TrackPointRecord>()
        override suspend fun deleteTrip(id: Long) = Unit
        override suspend fun deletePoints(tripId: Long) = Unit
    }

    private val main = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.rig(): Pair<TripTracker, TripLogViewModel> {
        val clock = TestClock(testScheduler)
        val tracker = TripTracker(Silent, clock, backgroundScope)
        val resolver = object : AddressResolver {
            override suspend fun addressOf(point: GeoPoint): String? = null
        }
        val recorder = TripRecorder(tracker, Dao(), resolver, { null }, clock, WallClock { 0L }, backgroundScope).also { it.start() }
        return tracker to TripLogViewModel(tracker, recorder, Dao(), com.nativeauto.FakeSettingsRepository())
    }

    @Test fun `end needs two taps within 3 s`() = runTest(main) {
        val (tracker, vm) = rig()
        tracker.start(); runCurrent()
        assertFalse(vm.onEndTapped())
        assertTrue(vm.endArmed.value)
        assertEquals(TripState.Running, tracker.state.value)
        advanceTimeBy(1_000)
        assertTrue(vm.onEndTapped())
        assertEquals(TripState.Ended, tracker.state.value)
        assertFalse(vm.endArmed.value)
    }

    @Test fun `an armed end expires`() = runTest(main) {
        val (tracker, vm) = rig()
        tracker.start(); runCurrent()
        vm.onEndTapped()
        advanceTimeBy(TripLogViewModel.ARM_MS + 1); runCurrent()
        assertFalse(vm.endArmed.value)
        assertFalse("a late tap only re-arms", vm.onEndTapped())
        assertEquals(TripState.Running, tracker.state.value)
    }

    @Test fun `end does nothing without a trip`() = runTest(main) {
        val (tracker, vm) = rig()
        assertFalse(vm.onEndTapped())
        assertFalse(vm.endArmed.value)
        assertEquals(TripState.Idle, tracker.state.value)
    }

    @Test fun `dismissing the summary zeroes the trip`() = runTest(main) {
        val (tracker, vm) = rig()
        tracker.start(); runCurrent()
        advanceTimeBy(10_000)
        tracker.end(); runCurrent()
        vm.dismissSummary()
        assertEquals(TripState.Idle, tracker.state.value)
        assertNull(tracker.stats.value.startedAtNanos)
    }
}
