package com.nativeauto.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsLinkTest {

    @Test fun `finds the maps link in a google maps share`() {
        val text = "Malioboro Mall\nJl. Malioboro No.52-58, Yogyakarta\nhttps://maps.app.goo.gl/AbCdEf123"
        val url = findMapsUrl(text)!!
        assertEquals("https://maps.app.goo.gl/AbCdEf123", url)
        assertTrue(isShortMapsLink(url))
        assertEquals("Malioboro Mall", sharedName(text, url))
        assertEquals("Jl. Malioboro No.52-58, Yogyakarta", sharedDetail(text, url))
    }

    @Test fun `ignores links that aren't maps`() {
        assertNull(findMapsUrl("look https://example.com/maps/x"))
        assertNull(findMapsUrl("no link here"))
        assertFalse(isShortMapsLink("https://www.google.com/maps/place/X/@1,2,3z"))
    }

    @Test fun `the place pin beats the camera centre`() {
        val url = "https://www.google.com/maps/place/Stasiun+Yogyakarta/@-7.7891,110.3608,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d-7.7893711!4d110.3633527"
        val t = parseMapsLink(url) as LinkTarget.At
        assertEquals(-7.7893711, t.point.latitude, 1e-9)
        assertEquals(110.3633527, t.point.longitude, 1e-9)
        assertEquals("Stasiun Yogyakarta", t.name)
    }

    @Test fun `camera centre when there is no pin`() {
        val t = parseMapsLink("https://www.google.com/maps/@-6.1754,106.8272,15z") as LinkTarget.At
        assertEquals(-6.1754, t.point.latitude, 1e-9)
        assertNull(t.name)
    }

    @Test fun `q with coordinates or a name`() {
        val at = parseMapsLink("https://maps.google.com/?q=-7.7956,110.3695", "Kos Putri Melati\nhttps://maps.google.com/?q=-7.7956,110.3695") as LinkTarget.At
        assertEquals(110.3695, at.point.longitude, 1e-9)
        assertEquals("Kos Putri Melati", at.name)

        val q = parseMapsLink("https://www.google.com/maps/search/?api=1&query=Bengkel+Motor+Jaya")
        assertEquals(LinkTarget.Query("Bengkel Motor Jaya"), q)

        val dest = parseMapsLink("https://www.google.com/maps/dir/?api=1&destination=-6.2088%2C106.8456") as LinkTarget.At
        assertEquals(-6.2088, dest.point.latitude, 1e-9)
    }

    @Test fun `geo uris`() {
        assertEquals(GeoPoint(-7.8, 110.4), (parseMapsLink("geo:-7.8,110.4") as LinkTarget.At).point)
        val labelled = parseMapsLink("geo:0,0?q=-7.8,110.4(Warung Bu Ning)") as LinkTarget.At
        assertEquals("Warung Bu Ning", labelled.name)
        assertEquals(LinkTarget.Query("Jalan Kaliurang 5"), parseMapsLink("geo:0,0?q=Jalan+Kaliurang+5"))
        assertNull(parseMapsLink("geo:0,0"))
    }

    @Test fun `only a place segment searches by name`() {
        assertEquals(LinkTarget.Query("Gudeg Yu Djum"), parseMapsLink("https://www.google.com/maps/place/Gudeg+Yu+Djum"))
    }
}
