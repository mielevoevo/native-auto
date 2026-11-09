package com.nativeauto.nav

import com.nativeauto.TestClock
import com.nativeauto.location.LiveLocation
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripTracker
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsLinkResolverTest {
    private object Silent : SpeedSampleSource { override val samples: Flow<SpeedSample> = emptyFlow() }

    private class FakeSearch : PlaceSearch {
        var results: List<Place> = emptyList()
        var fail = false
        val asked = mutableListOf<String>()
        override suspend fun search(query: String, near: GeoPoint?): List<Place> {
            asked += query
            if (fail) throw IOException("offline")
            return results
        }
        override suspend fun reverse(point: GeoPoint): Place? = null
    }

    private fun TestScope.resolver(search: PlaceSearch): MapsLinkResolver {
        val tracker = TripTracker(Silent, TestClock(testScheduler), backgroundScope)
        return MapsLinkResolver(OkHttpClient(), search, LiveLocation(tracker, Silent, backgroundScope))
    }

    @Test fun `a link with a position needs no search`() = runTest {
        val search = FakeSearch()
        val text = "Warung Bu Ning\nJl. Kaliurang 5\nhttps://maps.google.com/?q=-7.76,110.38"
        val place = resolver(search).resolve(text)
        assertEquals("Warung Bu Ning", place.name)
        assertEquals("Jl. Kaliurang 5", place.detail)
        assertEquals(GeoPoint(-7.76, 110.38), place.point)
        assertEquals(emptyList<String>(), search.asked)
    }

    @Test fun `a name-only link and plain text go through search`() = runTest {
        val search = FakeSearch().apply { results = listOf(Place("Gudeg Yu Djum", null, GeoPoint(-7.77, 110.37))) }
        val r = resolver(search)
        assertEquals("Gudeg Yu Djum", r.resolve("https://www.google.com/maps/place/Gudeg+Yu+Djum").name)
        r.resolve("  \nJalan Kaliurang 5\nsecond line")
        assertEquals(listOf("Gudeg Yu Djum", "Jalan Kaliurang 5"), search.asked)
    }

    @Test(expected = LinkException::class)
    fun `nothing found is a readable failure`() = runTest {
        resolver(FakeSearch()).resolve("https://www.google.com/maps/search/?api=1&query=Nowhere")
    }

    @Test fun `search offline is a readable failure`() = runTest {
        val e = runCatching { resolver(FakeSearch().apply { fail = true }).resolve("Some address") }.exceptionOrNull()
        assertEquals("Search is offline", (e as LinkException).message)
    }

    @Test fun `short links are expanded through their redirects`() = runTest {
        val target = "https://www.google.com/maps/place/Tugu/@-7.78,110.36,17z/data=!3d-7.7829!4d110.3671"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hop1") { it.responseHeaders.add("Location", "/hop2"); it.sendResponseHeaders(302, -1); it.close() }
            createContext("/hop2") { it.responseHeaders.add("Location", target); it.sendResponseHeaders(301, -1); it.close() }
            start()
        }
        try {
            val expanded = resolver(FakeSearch()).expand("http://127.0.0.1:${server.address.port}/hop1")
            assertEquals(target, expanded)
            val at = parseMapsLink(expanded) as LinkTarget.At
            assertEquals(-7.7829, at.point.latitude, 1e-9)
        } finally {
            server.stop(0)
        }
    }
}
