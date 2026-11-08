package com.nativeauto.history

import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.METERS_PER_DEGREE_LAT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    /** The worked example from Google's "Encoded Polyline Algorithm Format" page. */
    private val googleExample = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
    private val googleEncoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test fun `encodes Google's reference example`() {
        assertEquals(googleEncoded, Polyline.encode(googleExample))
    }

    @Test fun `decodes Google's reference example`() {
        assertPointsEqual(googleExample, Polyline.decode(googleEncoded))
    }

    @Test fun `empty path round-trips`() {
        assertEquals("", Polyline.encode(emptyList()))
        assertEquals(emptyList<GeoPoint>(), Polyline.decode(""))
    }

    @Test fun `round trip keeps about a metre of precision, in every hemisphere`() {
        val path = listOf(
            GeoPoint(-7.782889, 110.367083),  // Yogyakarta
            GeoPoint(-7.789123, 110.361234),
            GeoPoint(51.5007, -0.1246),
            GeoPoint(-33.8568, 151.2153),
            GeoPoint(0.0, 0.0),
            GeoPoint(89.99999, -179.99999),
        )
        val back = Polyline.decode(Polyline.encode(path))
        assertEquals(path.size, back.size)
        path.zip(back).forEach { (a, b) ->
            assertEquals(a.latitude, b.latitude, 0.5e-5)
            assertEquals(a.longitude, b.longitude, 0.5e-5)
        }
    }

    @Test fun `a repeated point encodes as a zero step`() {
        val p = GeoPoint(-7.78, 110.37)
        assertPointsEqual(listOf(p, p), Polyline.decode(Polyline.encode(listOf(p, p))))
    }

    private fun assertPointsEqual(expected: List<GeoPoint>, actual: List<GeoPoint>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.latitude, a.latitude, 1e-9)
            assertEquals(e.longitude, a.longitude, 1e-9)
        }
    }
}

class SimplifyTest {

    private fun north(metres: Double, eastMetres: Double = 0.0) =
        GeoPoint(metres / METERS_PER_DEGREE_LAT, eastMetres / METERS_PER_DEGREE_LAT)

    @Test fun `fewer than three points are returned as they are`() {
        assertEquals(emptyList<GeoPoint>(), simplify(emptyList(), 4.0))
        val two = listOf(north(0.0), north(10.0))
        assertEquals(two, simplify(two, 4.0))
    }

    @Test fun `a straight line keeps only its ends`() {
        val line = (0..100).map { north(it * 10.0) }
        assertEquals(listOf(line.first(), line.last()), simplify(line, 4.0))
    }

    @Test fun `wobble under the tolerance is dropped, over it is kept`() {
        val small = listOf(north(0.0), north(50.0, eastMetres = 3.0), north(100.0))
        assertEquals(2, simplify(small, 4.0).size)
        val big = listOf(north(0.0), north(50.0, eastMetres = 5.0), north(100.0))
        assertEquals(big, simplify(big, 4.0))
    }

    @Test fun `a corner survives and the ends are always kept`() {
        // 500 m north, then 500 m east: an L with a point every 10 m.
        val up = (0..50).map { north(it * 10.0) }
        val across = (1..50).map { north(500.0, eastMetres = it * 10.0) }
        val path = up + across
        val out = simplify(path, 4.0)
        assertEquals(listOf(path.first(), north(500.0), path.last()), out)
    }

    @Test fun `a loop back to the start keeps its far point`() {
        // Out 200 m and straight back: the start and end coincide (zero-length base segment).
        val path = (0..20).map { north(it * 10.0) } + (19 downTo 0).map { north(it * 10.0) }
        val out = simplify(path, 4.0)
        assertTrue(north(200.0) in out)
        assertEquals(path.first(), out.first())
        assertEquals(path.last(), out.last())
    }
}
