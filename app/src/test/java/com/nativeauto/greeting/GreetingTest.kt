package com.nativeauto.greeting

import com.nativeauto.settings.GreetingMode
import com.nativeauto.settings.greetingModeFrom
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    private val today = LocalDate.of(2026, 11, 24)

    private fun greet(lastDay: LocalDate?, mode: GreetingMode = GreetingMode.ONCE_A_DAY, busy: Boolean = false, freshOpen: Boolean = true) =
        shouldGreet(today, lastDay, mode, busy, freshOpen)

    @Test fun greetsOnTheFirstOpenOfTheDayOnly() {
        assertTrue(greet(lastDay = null))
        assertTrue(greet(lastDay = today.minusDays(1)))
        // The app left open overnight and looked at again: still the day's first open.
        assertTrue(greet(lastDay = today.minusDays(1), freshOpen = false))
        assertFalse("same day", greet(lastDay = today))
    }

    @Test fun waitsWhileBusyAndStaysQuietWhenOff() {
        assertFalse("trip or navigation", greet(today.minusDays(1), busy = true))
        assertFalse("turned off", greet(today.minusDays(1), GreetingMode.OFF))
        assertFalse("every open, riding", greet(today, GreetingMode.EVERY_OPEN, busy = true))
    }

    @Test fun everyOpenGreetsEachFreshOpenOfTheSameDay() {
        assertTrue(greet(today, GreetingMode.EVERY_OPEN))
        assertFalse("coming straight back is the same open", greet(today, GreetingMode.EVERY_OPEN, freshOpen = false))
    }

    @Test fun theOldSwitchCarriesOver() {
        assertEquals(GreetingMode.ONCE_A_DAY, greetingModeFrom(null, null))
        assertEquals(GreetingMode.ONCE_A_DAY, greetingModeFrom(null, true))
        assertEquals(GreetingMode.OFF, greetingModeFrom(null, false))
        assertEquals("a newer choice wins", GreetingMode.EVERY_OPEN, greetingModeFrom("EVERY_OPEN", false))
        assertEquals("damaged", GreetingMode.ONCE_A_DAY, greetingModeFrom("??", null))
    }

    @Test fun aClockGoingBackDoesNotRepeatTheGreetingButANewDayDoes() {
        // A day stored "in the future" (clock changed) is just another day: today still greets once.
        assertTrue(greet(lastDay = today.plusDays(3)))
    }

    @Test fun partOfTheDay() {
        assertEquals("Good evening", greetingFor(3))
        assertEquals("Good morning", greetingFor(4))
        assertEquals("Good morning", greetingFor(11))
        assertEquals("Good afternoon", greetingFor(12))
        assertEquals("Good afternoon", greetingFor(17))
        assertEquals("Good evening", greetingFor(18))
        assertEquals("Good evening", greetingFor(23))
    }
}
