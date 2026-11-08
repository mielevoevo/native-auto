package com.nativeauto.nav

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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Finding destinations by name, and naming a point. */
interface PlaceSearch {
    /** @throws IOException when offline or the service fails. */
    suspend fun search(query: String, near: GeoPoint?): List<Place>

    /** Null when nothing is known there or the lookup fails. */
    suspend fun reverse(point: GeoPoint): Place?
}

/**
 * Photon (komoot's public geocoder over OpenStreetMap): free, no key, and its data matches the
 * map and the router. Fair use only, so the UI searches on a pause in typing, not per key.
 */
@Singleton
class PhotonPlaceSearch @Inject constructor(private val http: OkHttpClient) : PlaceSearch {

    override suspend fun search(query: String, near: GeoPoint?): List<Place> {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegments("api/")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "8")
            .apply {
                // Bias towards where the rider is, so "Monas" means the one in this city.
                near?.let {
                    addQueryParameter("lat", it.latitude.toString())
                    addQueryParameter("lon", it.longitude.toString())
                }
            }
            .build()
        return parsePhoton(get(url.toString()))
    }

    override suspend fun reverse(point: GeoPoint): Place? = runCatching {
        val url = BASE.toHttpUrl().newBuilder()
            .addPathSegments("reverse")
            .addQueryParameter("lat", point.latitude.toString())
            .addQueryParameter("lon", point.longitude.toString())
            .addQueryParameter("limit", "1")
            .build()
        parsePhoton(get(url.toString())).firstOrNull()?.copy(point = point)
    }.getOrNull()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Photon HTTP ${res.code}")
            res.body.string()
        }
    }

    private companion object {
        const val BASE = "https://photon.komoot.io/"
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** Photon's GeoJSON → places. Pure, so it's unit-tested without the network. */
internal fun parsePhoton(body: String): List<Place> =
    json.parseToJsonElement(body).jsonObject["features"]?.jsonArray.orEmpty().mapNotNull { feature ->
        val obj = feature.jsonObject
        val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
        val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val p = obj["properties"]?.jsonObject ?: JsonObject(emptyMap())
        fun prop(key: String) = p[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val street = listOfNotNull(prop("street"), prop("housenumber")).joinToString(" ").ifBlank { null }
        val name = prop("name") ?: street ?: return@mapNotNull null
        val detail = listOfNotNull(
            street.takeIf { it != name },
            prop("district") ?: prop("locality"),
            prop("city") ?: prop("county"),
            prop("state"),
        ).distinct().joinToString(", ").ifBlank { null }
        Place(name = name, detail = detail, point = GeoPoint(lat, lon))
    }
