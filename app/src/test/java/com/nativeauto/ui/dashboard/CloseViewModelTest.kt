package com.nativeauto.ui.dashboard

import com.nativeauto.TestClock
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloseViewModelTest {
    private object Silent : SpeedSampleSource { override val samples: Flow<SpeedSample> = emptyFlow() }
    private val main = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `first back shows the hint, a second within 2 s closes`() = runTest(main) {
        val vm = CloseViewModel(TripTracker(Silent, TestClock(testScheduler), backgroundScope))
        assertEquals(BackStep.ShowHint, vm.onBack())
        assertTrue(vm.hint.value)
        advanceTimeBy(1_500)
        assertEquals(BackStep.Close, vm.onBack())
        assertFalse(vm.hint.value)
    }

    @Test fun `the hint expires, and a late press only shows it again`() = runTest(main) {
        val vm = CloseViewModel(TripTracker(Silent, TestClock(testScheduler), backgroundScope))
        vm.onBack()
        advanceTimeBy(CloseViewModel.WINDOW_MS + 1); runCurrent()
        assertFalse(vm.hint.value)
        assertEquals(BackStep.ShowHint, vm.onBack())
    }

    @Test fun `with a trip running or paused it asks to save`() = runTest(main) {
        val tracker = TripTracker(Silent, TestClock(testScheduler), backgroundScope)
        val vm = CloseViewModel(tracker)
        tracker.start(); runCurrent()
        vm.onBack()
        assertEquals(BackStep.AskToSave, vm.onBack())
        tracker.pause()
        vm.onBack()
        assertEquals(BackStep.AskToSave, vm.onBack())
        vm.save()
        assertEquals(TripState.Ended, tracker.state.value)
    }
}
