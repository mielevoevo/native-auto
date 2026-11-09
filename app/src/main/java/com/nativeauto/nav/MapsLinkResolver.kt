package com.nativeauto.nav

import com.nativeauto.di.ApplicationScope
import com.nativeauto.location.LiveLocation
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a shared link couldn't become a destination; [message] is shown to the rider. */
class LinkException(message: String) : Exception(message)

/**
 * Turns shared or pasted text into a [Place] (10b): expands short Google Maps links (they only
 * redirect), reads the position from the link, and searches when the link holds only a name.
 * Plain text without a link is searched as it is (an address shared from a chat app).
 */
@Singleton
class MapsLinkResolver @Inject constructor(
    http: OkHttpClient,
    private val search: PlaceSearch,
    private val live: LiveLocation,
) {
    /** Redirects are followed by hand, so each hop's URL can be read (and stopped at a maps one). */
    private val noRedirects = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(text: String): Place {
        val url = findMapsUrl(text)
        val target = when {
            url == null -> text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let(LinkTarget::Query)
            isShortMapsLink(url) -> parseMapsLink(expand(url), text)
            else -> parseMapsLink(url, text)
        } ?: throw LinkException("Couldn't read a place from that link")
        val detail = url?.let { sharedDetail(text, it) }
        return when (target) {
            is LinkTarget.At -> Place(name = target.name ?: "Shared place", detail = detail, point = target.point)
            is LinkTarget.Query -> {
                val near = live.latest?.let { GeoPoint(it.latitude, it.longitude) }
                val found = try {
                    search.search(target.text, near)
                } catch (e: IOException) {
                    throw LinkException("Search is offline")
                }
                found.firstOrNull() ?: throw LinkException("Couldn't find \"${target.text}\"")
            }
        }
    }

    /**
     * Follows up to [MAX_HOPS] redirects until a full maps URL; Google's consent page hands over
     * its `continue` target.
     */
    internal suspend fun expand(start: String): String = withContext(Dispatchers.IO) {
        var url = start
        repeat(MAX_HOPS) {
            val next = try {
                noRedirects.newCall(Request.Builder().url(url).build()).execute().use { res ->
                    if (res.code !in 300..399) return@withContext url
                    res.header("Location")?.let { loc -> url.toHttpUrlOrNull()?.resolve(loc)?.toString() }
                }
            } catch (e: IOException) {
                throw LinkException("Offline: couldn't open the short link")
            } ?: return@withContext url
            url = next.toHttpUrlOrNull()?.takeIf { it.host.startsWith("consent.") }?.queryParameter("continue") ?: next
            // Done once it's a full maps URL; any other hop (tracking, consent) is followed on.
            if (findMapsUrl(url) != null && !isShortMapsLink(url)) return@withContext url
        }
        url
    }

    private companion object {
        const val MAX_HOPS = 5
    }
}

/** Where a shared link stands, for the dashboard's status pill. */
sealed interface LinkStatus {
    data object Resolving : LinkStatus
    data class Failed(val message: String) : LinkStatus
}

/**
 * Links shared to the app (share sheet, `geo:`) → a route preview. The rider still presses
 * Start, as with a searched place. Runs in the app scope, so a rotation doesn't drop it.
 */
@Singleton
class IncomingLinks @Inject constructor(
    private val resolver: MapsLinkResolver,
    private val navigator: Navigator,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow<LinkStatus?>(null)
    val status: StateFlow<LinkStatus?> = _status.asStateFlow()
    private var job: Job? = null

    fun open(text: String) {
        job?.cancel()
        job = scope.launch {
            _status.value = LinkStatus.Resolving
            try {
                val place = resolver.resolve(text)
                _status.value = null
                navigator.routeTo(place)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = LinkStatus.Failed(e.message ?: "Couldn't open that link")
                delay(FAILED_SHOWN_MS)
                _status.value = null
            }
        }
    }

    private companion object {
        const val FAILED_SHOWN_MS = 5_000L
    }
}
