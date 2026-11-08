package com.nativeauto.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsePhotonTest {

    private fun feature(lon: Any?, lat: Any?, vararg props: Pair<String, String>): String {
        val coords = listOfNotNull(lon, lat).joinToString(",")
        val p = props.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        return """{"type":"Feature","geometry":{"type":"Point","coordinates":[$coords]},"properties":{$p}}"""
    }

    private fun collection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    @Test fun `a named place with its area`() {
        val body = collection(
            feature(
                110.3655, -7.7926,
                "name" to "Malioboro", "street" to "Jalan Malioboro", "district" to "Gedong Tengen",
                "city" to "Yogyakarta", "state" to "Special Region of Yogyakarta", "osm_key" to "highway",
            ),
        )
        assertEquals(
            listOf(
                Place(
                    name = "Malioboro",
                    detail = "Jalan Malioboro, Gedong Tengen, Yogyakarta, Special Region of Yogyakarta",
                    point = GeoPoint(-7.7926, 110.3655),
                ),
            ),
            parsePhoton(body),
        )
    }

    @Test fun `coordinates are lon, lat in GeoJSON`() {
        val place = parsePhoton(collection(feature(106.8272, -6.1754, "name" to "Monas"))).single()
        assertEquals(GeoPoint(-6.1754, 106.8272), place.point)
        assertNull(place.detail)
    }

    @Test fun `an address without a name is named by street and number`() {
        val place = parsePhoton(
            collection(feature(110.0, -7.0, "street" to "Jalan Kaliurang", "housenumber" to "12", "city" to "Yogyakarta")),
        ).single()
        assertEquals("Jalan Kaliurang 12", place.name)
        // The street isn't repeated in the detail when it's already the name.
        assertEquals("Yogyakarta", place.detail)
    }

    @Test fun `locality and county stand in for district and city`() {
        val place = parsePhoton(
            collection(feature(110.0, -7.0, "name" to "Warung", "locality" to "Sosromenduran", "county" to "Yogyakarta")),
        ).single()
        assertEquals("Sosromenduran, Yogyakarta", place.detail)
    }

    @Test fun `blank and repeated parts are left out of the detail`() {
        val place = parsePhoton(
            collection(feature(110.0, -7.0, "name" to "Tugu", "district" to " ", "city" to "Yogyakarta", "state" to "Yogyakarta")),
        ).single()
        assertEquals("Yogyakarta", place.detail)
    }

    @Test fun `features without a usable point or name are skipped`() {
        val body = collection(
            feature(null, null, "name" to "No coordinates"),
            feature(110.0, null, "name" to "Only one coordinate"),
            feature("\"x\"", "\"y\"", "name" to "Not numbers"),
            feature(110.0, -7.0, "city" to "Nameless, streetless"),
            feature(110.0, -7.0, "name" to "Kept"),
        )
        assertEquals(listOf("Kept"), parsePhoton(body).map { it.name })
    }

    @Test fun `a feature with no properties at all is skipped`() {
        val body = """{"features":[{"geometry":{"coordinates":[110.0,-7.0]}}]}"""
        assertEquals(emptyList<Place>(), parsePhoton(body))
    }

    @Test fun `no features gives an empty list`() {
        assertEquals(emptyList<Place>(), parsePhoton("""{"type":"FeatureCollection","features":[]}"""))
        assertEquals(emptyList<Place>(), parsePhoton("{}"))
    }

    @Test fun `order is kept`() {
        val body = collection(
            feature(110.0, -7.0, "name" to "First"),
            feature(111.0, -7.0, "name" to "Second"),
        )
        assertEquals(listOf("First", "Second"), parsePhoton(body).map { it.name })
    }
}
