package com.nativeauto.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortAddressTest {

    @Test fun `street and area`() {
        assertEquals("Jalan Malioboro, Gedong Tengen", shortAddress("Jalan Malioboro", "Gedong Tengen", line = "ignored"))
    }

    @Test fun `street alone when the area is unknown, blank or the same`() {
        assertEquals("Jalan Malioboro", shortAddress("Jalan Malioboro", null, null))
        assertEquals("Jalan Malioboro", shortAddress("Jalan Malioboro", "  ", null))
        assertEquals("Yogyakarta", shortAddress("Yogyakarta", "Yogyakarta", null))
    }

    @Test fun `parts are trimmed`() {
        assertEquals("Jalan Kaliurang, Yogyakarta", shortAddress("  Jalan Kaliurang ", " Yogyakarta ", null))
    }

    @Test fun `a plus code or bare house number falls back to the address line`() {
        val line = "Jl. Malioboro No.12, Sosromenduran, Gedong Tengen, Yogyakarta City, Special Region of Yogyakarta 55271, Indonesia"
        assertEquals("Jl. Malioboro No.12, Sosromenduran", shortAddress("QX2C+9F", "Sosromenduran", line))
        assertEquals("Jl. Malioboro No.12, Sosromenduran", shortAddress("12", "Sosromenduran", line))
        assertEquals("Jl. Malioboro No.12, Sosromenduran", shortAddress("12 14", "Sosromenduran", line))
    }

    @Test fun `plus codes in the address line are skipped too`() {
        assertEquals("Sosromenduran, Gedong Tengen", shortAddress(null, null, "QX2C+9F, Sosromenduran, Gedong Tengen, Yogyakarta"))
    }

    @Test fun `only the area when there's no street and no line`() {
        assertEquals("Yogyakarta", shortAddress(null, "Yogyakarta", null))
        assertEquals("Yogyakarta", shortAddress("QX2C+9F", "Yogyakarta", ""))
    }

    @Test fun `nothing known gives null`() {
        assertNull(shortAddress(null, null, null))
        assertNull(shortAddress("", "", ""))
        assertNull(shortAddress("QX2C+9F", null, "QX2C+9F"))
    }
}
