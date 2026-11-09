package com.nativeauto.nav

import com.nativeauto.BuildConfig
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TomTom Search (Rider-Feedback §1/§7, 10a): fuzzy search over commercial place data, much more
 * forgiving than Photon with typos, partial names and small businesses. Free tier, key in
 * `local.properties` (`TOMTOM_API_KEY`); [available] is false until the rider adds one.
 */
@Singleton
class TomTomPlaceSearch @Inject constructor(private val http: OkHttpClient) : PlaceSearch {

    val available: Boolean get() = BuildConfig.HAS_TOMTOM_KEY

    override suspend fun search(query: String, near: GeoPoint?): List<Place> {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegments("search/2/search/")
            // The query is a path segment; TomTom wants it URL-encoded there, ".json" after it.
            .addEncodedPathSegment(URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20") + ".json")
            .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
            .addQueryParameter("limit", "8")
            .addQueryParameter("typeahead", "false")
            .apply {
                near?.let {
                    // Bias, not a filter: nearer results rank higher, far ones still appear.
                    addQueryParameter("lat", it.latitude.toString())
                    addQueryParameter("lon", it.longitude.toString())
                }
            }
            .build()
        return parseTomTomSearch(get(url.toString()))
    }

    override suspend fun reverse(point: GeoPoint): Place? = runCatching {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegments("search/2/reverseGeocode/")
            .addEncodedPathSegment("${point.latitude},${point.longitude}.json")
            .addQueryParameter("key", BuildConfig.TOMTOM_API_KEY)
            .build()
        parseTomTomReverse(get(url.toString()))?.copy(point = point)
    }.getOrNull()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        if (!available) throw IOException("No TomTom key")
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("TomTom HTTP ${res.code}")
            res.body.string()
        }
    }

    private companion object {
        const val BASE = "https://api.tomtom.com/"
    }
}

/**
 * Uses TomTom when it has a key, Photon otherwise; and Photon whenever TomTom fails (offline,
 * quota spent, key revoked), so search never gets worse than before 10a.
 */
@Singleton
class PreferredPlaceSearch @Inject constructor(
    private val tomTom: TomTomPlaceSearch,
    private val photon: PhotonPlaceSearch,
) : PlaceSearch {
    override suspend fun search(query: String, near: GeoPoint?): List<Place> =
        withFallback(tomTom.available, { tomTom.search(query, near) }) { photon.search(query, near) }

    override suspend fun reverse(point: GeoPoint): Place? =
        (if (tomTom.available) tomTom.reverse(point) else null) ?: photon.reverse(point)
}

/** [primary] when [usePrimary], else (or when it throws an IOException) [fallback]. */
internal suspend fun <T> withFallback(usePrimary: Boolean, primary: suspend () -> T, fallback: suspend () -> T): T {
    if (!usePrimary) return fallback()
    return try {
        primary()
    } catch (e: IOException) {
        fallback()
    }
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * TomTom search results → places. POIs are named by the POI; addresses by their street line.
 * Pure, so it's unit-tested without the network.
 */
internal fun parseTomTomSearch(body: String): List<Place> =
    json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
        val r = e.jsonObject
        val pos = r["position"]?.jsonObject ?: return@mapNotNull null
        val lat = pos["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = pos["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val a = r["address"]?.jsonObject ?: JsonObject(emptyMap())
        fun addr(k: String) = a[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val poi = r["poi"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val street = listOfNotNull(addr("streetName"), addr("streetNumber")).joinToString(" ").ifBlank { null }
        val name = poi ?: street ?: addr("municipalitySubdivision") ?: addr("municipality") ?: addr("freeformAddress")
            ?: return@mapNotNull null
        val detail = listOfNotNull(
            street.takeIf { it != name },
            addr("municipalitySubdivision").takeIf { it != name },
            addr("municipality").takeIf { it != name },
            addr("countrySubdivision"),
        ).distinct().joinToString(", ").ifBlank { null }
        Place(name = name, detail = detail, point = GeoPoint(lat, lon))
    }

/** TomTom reverse geocode → the nearest address (its position is the asked point; the caller sets it). */
internal fun parseTomTomReverse(body: String): Place? {
    val first = json.parseToJsonElement(body).jsonObject["addresses"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val a = first["address"]?.jsonObject ?: return null
    fun addr(k: String) = a[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val street = listOfNotNull(addr("streetName"), addr("streetNumber")).joinToString(" ").ifBlank { null }
    val name = street ?: addr("municipalitySubdivision") ?: addr("freeformAddress") ?: return null
    val detail = listOfNotNull(addr("municipalitySubdivision").takeIf { it != name }, addr("municipality"))
        .distinct().joinToString(", ").ifBlank { null }
    return Place(name = name, detail = detail, point = GeoPoint(0.0, 0.0))
}
