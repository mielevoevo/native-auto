package com.nativeauto.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelfCloseTest {

    @Test fun `ended from the notification while hidden closes after the delay`() = runTest {
        val close = SelfClose(backgroundScope)
        var closes = 0
        backgroundScope.launch { close.closeRequests.collect { closes++ } }
        close.onVisible(); close.onHidden(); runCurrent()
        close.arm()
        advanceTimeBy(SelfClose.DELAY_MS - 1); runCurrent()
        assertEquals(0, closes)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun `coming back to the app cancels it`() = runTest {
        val close = SelfClose(backgroundScope)
        var closes = 0
        backgroundScope.launch { close.closeRequests.collect { closes++ } }
        close.onHidden(); close.arm()
        advanceTimeBy(60_000)
        close.onVisible()
        close.onHidden() // leaving again doesn't re-arm: the app was used
        advanceTimeBy(SelfClose.DELAY_MS * 2); runCurrent()
        assertEquals(0, closes)
    }

    @Test fun `ended while visible waits until the app is hidden`() = runTest {
        val close = SelfClose(backgroundScope)
        var closes = 0
        backgroundScope.launch { close.closeRequests.collect { closes++ } }
        close.onVisible(); close.arm()
        advanceTimeBy(SelfClose.DELAY_MS * 2); runCurrent()
        assertEquals(0, closes)
        close.onHidden()
        advanceTimeBy(SelfClose.DELAY_MS + 1); runCurrent()
        assertEquals(1, closes)
    }

    @Test fun `a new trip disarms it`() = runTest {
        val close = SelfClose(backgroundScope)
        var closes = 0
        backgroundScope.launch { close.closeRequests.collect { closes++ } }
        close.onHidden(); close.arm()
        close.disarm()
        advanceTimeBy(SelfClose.DELAY_MS * 2); runCurrent()
        assertEquals(0, closes)
    }
}
