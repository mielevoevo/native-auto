package com.nativeauto.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RiderPhrasingTest {
    @Test fun bearBecomesSlight() {
        assertEquals("Slight left onto Jalan Malioboro.", riderPhrasing("Bear left onto Jalan Malioboro."))
        assertEquals("In 200 meters, slight right.", riderPhrasing("In 200 meters, bear right."))
    }

    @Test fun onlyWholeWords() {
        assertEquals("Forbear left alone", riderPhrasing("Forbear left alone"))
        assertEquals("Bear leftover", riderPhrasing("Bear leftover"))
        assertEquals("Turn left", riderPhrasing("Turn left"))
    }

    @Test fun ssmlKeepsItsMarkup() {
        assertEquals(
            "<speak>Slight right onto <say-as>Jalan Solo</say-as></speak>",
            riderPhrasing("<speak>Bear right onto <say-as>Jalan Solo</say-as></speak>"),
        )
    }

    @Test fun thenContinuesAManeuverInLowerCase() {
        assertEquals("Then turn left", thenLine("Turn left"))
        assertEquals("Then slight right", thenLine("Slight right"))
        assertEquals("Then Jalan Cornelis Simanjuntak", thenLine("Jalan Cornelis Simanjuntak"))
        assertEquals("Then Route 12", thenLine("Route 12"))
    }
}
