package com.nativeauto.ui.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapSleepTest {

    @Test fun `off screen past the grace period, the map sleeps`() = runTest {
        val sleep = MapSleep(backgroundScope) { 30_000 }
        assertTrue(sleep.awake.value)
        sleep.setOnScreen(false)
        advanceTimeBy(29_999); runCurrent()
        assertTrue("still within the grace period", sleep.awake.value)
        advanceTimeBy(1); runCurrent()
        assertFalse(sleep.awake.value)
    }

    @Test fun `a quick peek keeps it awake`() = runTest {
        val sleep = MapSleep(backgroundScope) { 30_000 }
        sleep.setOnScreen(false)
        advanceTimeBy(20_000); runCurrent()
        sleep.setOnScreen(true)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(sleep.awake.value)
        // And the next time off screen gets the full grace period again.
        sleep.setOnScreen(false)
        advanceTimeBy(29_000); runCurrent()
        assertTrue(sleep.awake.value)
    }

    @Test fun `back on screen wakes it at once`() = runTest {
        val sleep = MapSleep(backgroundScope) { 30_000 }
        sleep.setOnScreen(false)
        advanceTimeBy(31_000); runCurrent()
        assertFalse(sleep.awake.value)
        sleep.setOnScreen(true)
        assertTrue(sleep.awake.value)
    }

    @Test fun `repeated reports don't restart the timer`() = runTest {
        val sleep = MapSleep(backgroundScope) { 30_000 }
        sleep.setOnScreen(false)
        advanceTimeBy(20_000); runCurrent()
        sleep.setOnScreen(false) // e.g. recomposition with the same value
        advanceTimeBy(10_000); runCurrent()
        assertFalse(sleep.awake.value)
    }
}
