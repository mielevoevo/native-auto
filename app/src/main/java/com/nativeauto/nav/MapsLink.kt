package com.nativeauto.nav

import java.net.URLDecoder

/**
 * Google Maps links → a destination (Rider-Feedback "Decided", option C reworked, 10b). Pure, so
 * it's unit-tested without the network; [MapsLinkResolver] adds the redirects and the search.
 */
sealed interface LinkTarget {
    /** The link carries a position. [name] when the link or the shared text names the place. */
    data class At(val point: GeoPoint, val name: String?) : LinkTarget

    /** Only a name or address: it goes through the search service. */
    data class Query(val text: String) : LinkTarget
}

private val URL = Regex("""(?i)\b(?:https?://\S+|geo:\S+)""")
private val MAPS_HOST = Regex("""(?i)^https?://(?:maps\.app\.goo\.gl|goo\.gl/maps|(?:www\.|maps\.)?google\.[a-z.]+/maps|maps\.google\.[a-z.]+)""")
private val SHORT_HOST = Regex("""(?i)^https?://(?:maps\.app\.goo\.gl|goo\.gl/maps)/""")
private val PIN = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
private val AT = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
private val LATLON = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
private val PLACE_SEGMENT = Regex("""/maps/place/([^/@?]+)""")

/** The first maps link (Google Maps or `geo:`) in shared or pasted text, or null. */
fun findMapsUrl(text: String): String? =
    URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }
        .firstOrNull { it.startsWith("geo:", ignoreCase = true) || MAPS_HOST.containsMatchIn(it) }

/** `maps.app.goo.gl/...` and `goo.gl/maps/...` only redirect; they must be expanded first. */
fun isShortMapsLink(url: String) = SHORT_HOST.containsMatchIn(url)

/**
 * What a (full) maps link points at. The place pin (`!3d…!4d…`) beats the camera centre (`@lat,lon`);
 * then `q` / `query` / `destination` / `daddr` / `ll`, which may be coordinates or a name.
 * [sharedText] is the whole shared message: Google Maps puts the place name on its first line.
 */
fun parseMapsLink(url: String, sharedText: String? = null): LinkTarget? {
    val name = sharedName(sharedText, url) ?: placeSegment(url)
    if (url.startsWith("geo:", ignoreCase = true)) return parseGeo(url, name)

    PIN.find(url)?.let { return LinkTarget.At(point(it), name) }
    val params = queryParams(url)
    for (key in listOf("q", "query", "destination", "daddr", "ll")) {
        val v = params[key]?.takeIf { it.isNotBlank() } ?: continue
        LATLON.find(v)?.let { return LinkTarget.At(point(it), name) }
        // Google sometimes adds a "loc:" prefix or a name in parentheses after the address.
        return LinkTarget.Query(name ?: v.removePrefix("loc:").trim())
    }
    AT.find(url)?.let { return LinkTarget.At(point(it), name) }
    return name?.let(LinkTarget::Query)
}

/** `geo:lat,lon`, `geo:0,0?q=lat,lon(Name)` or `geo:0,0?q=Some+address`. */
private fun parseGeo(url: String, name: String?): LinkTarget? {
    val body = url.substringAfter(':')
    val q = queryParams(url)["q"]
    if (q != null) {
        val label = Regex("""\(([^)]*)\)""").find(q)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val bare = q.substringBefore('(')
        LATLON.find(bare)?.let { return LinkTarget.At(point(it), label ?: name) }
        return LinkTarget.Query(label ?: bare.trim()).takeIf { it.text.isNotBlank() }
    }
    val p = LATLON.find(body.substringBefore('?').substringBefore(';')) ?: return null
    val point = point(p)
    if (point.latitude == 0.0 && point.longitude == 0.0) return null
    return LinkTarget.At(point, name)
}

/** "Malioboro Mall\nJl. Malioboro No.52\nhttps://maps.app.goo.gl/…" → "Malioboro Mall". */
internal fun sharedName(sharedText: String?, url: String): String? =
    sharedText?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.contains(url) && URL.find(it) == null }
        ?.takeIf { it.length <= 120 }

/** The second non-link line of a Google Maps share (usually the address), for the place's detail. */
fun sharedDetail(sharedText: String?, url: String): String? =
    sharedText?.lineSequence()?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.contains(url) && URL.find(it) == null }
        ?.drop(1)?.firstOrNull()

private fun placeSegment(url: String): String? =
    PLACE_SEGMENT.find(url)?.groupValues?.get(1)?.let { decode(it) }?.takeIf { it.isNotBlank() }

private fun queryParams(url: String): Map<String, String> =
    url.substringAfter('?', "").substringBefore('#').split('&').mapNotNull { pair ->
        val k = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        k to decode(pair.substringAfter('='))
    }.toMap()

private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

private fun point(m: MatchResult) = GeoPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
