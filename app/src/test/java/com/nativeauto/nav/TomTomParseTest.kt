package com.nativeauto.nav

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TomTomParseTest {

    // Trimmed from TomTom's documented response shape (search/2/search).
    private val search = """
        {"summary":{"query":"malioboro"},"results":[
          {"type":"POI","poi":{"name":"Malioboro Mall"},
           "address":{"streetName":"Jalan Malioboro","streetNumber":"52-58","municipalitySubdivision":"Suryatmajan",
                      "municipality":"Yogyakarta","countrySubdivision":"Daerah Istimewa Yogyakarta","freeformAddress":"Jalan Malioboro 52-58"},
           "position":{"lat":-7.79222,"lon":110.36612}},
          {"type":"Street","address":{"streetName":"Jalan Malioboro","municipality":"Yogyakarta","freeformAddress":"Jalan Malioboro, Yogyakarta"},
           "position":{"lat":-7.7929,"lon":110.3658}},
          {"type":"Geography","address":{"municipality":"Yogyakarta","freeformAddress":"Yogyakarta"},"position":{"lat":-7.79,"lon":110.37}},
          {"type":"POI","poi":{"name":"No position"}}
        ]}
    """.trimIndent()

    @Test fun `pois are named by the poi, streets by the street, places by the town`() {
        val places = parseTomTomSearch(search)
        assertEquals(listOf("Malioboro Mall", "Jalan Malioboro", "Yogyakarta"), places.map { it.name })
        assertEquals("Jalan Malioboro 52-58, Suryatmajan, Yogyakarta, Daerah Istimewa Yogyakarta", places[0].detail)
        assertEquals("Yogyakarta", places[1].detail)
        assertEquals(-7.79222, places[0].point.latitude, 1e-9)
        assertEquals(110.36612, places[0].point.longitude, 1e-9)
    }

    @Test fun `no results is an empty list`() {
        assertEquals(emptyList<Place>(), parseTomTomSearch("""{"summary":{},"results":[]}"""))
    }

    @Test fun `reverse names the street and area`() {
        val body = """{"addresses":[{"address":{"streetName":"Jalan Kaliurang","streetNumber":"5",
            "municipalitySubdivision":"Caturtunggal","municipality":"Yogyakarta","freeformAddress":"Jalan Kaliurang 5, Yogyakarta"},
            "position":"-7.76,110.38"}]}"""
        val p = parseTomTomReverse(body)!!
        assertEquals("Jalan Kaliurang 5", p.name)
        assertEquals("Caturtunggal, Yogyakarta", p.detail)
        assertNull(parseTomTomReverse("""{"addresses":[]}"""))
    }

    @Test fun `the fallback answers when the primary is off or fails`() = runTest {
        assertEquals("photon", withFallback(false, { "tomtom" }) { "photon" })
        assertEquals("tomtom", withFallback(true, { "tomtom" }) { "photon" })
        assertEquals("photon", withFallback(true, { throw IOException("HTTP 403") }) { "photon" })
    }
}
