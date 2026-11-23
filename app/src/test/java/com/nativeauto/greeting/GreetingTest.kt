package com.nativeauto.greeting

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    private val today = LocalDate.of(2026, 11, 24)

    @Test fun greetsOnTheFirstOpenOfTheDayOnly() {
        assertTrue(shouldGreet(today, lastDay = null, enabled = true, busy = false))
        assertTrue(shouldGreet(today, lastDay = today.minusDays(1), enabled = true, busy = false))
        assertFalse("same day", shouldGreet(today, lastDay = today, enabled = true, busy = false))
    }

    @Test fun waitsWhileBusyAndStaysQuietWhenOff() {
        assertFalse("trip or navigation", shouldGreet(today, today.minusDays(1), enabled = true, busy = true))
        assertFalse("turned off", shouldGreet(today, today.minusDays(1), enabled = false, busy = false))
    }

    @Test fun aClockGoingBackDoesNotRepeatTheGreetingButANewDayDoes() {
        // A day stored "in the future" (clock changed) is just another day: today still greets once.
        assertTrue(shouldGreet(today, lastDay = today.plusDays(3), enabled = true, busy = false))
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
