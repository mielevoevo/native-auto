package com.nativeauto.history

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.PlaceSearch
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A short, human address for a trip's start or end ("Jalan Sudirman, Setiabudi"). */
interface AddressResolver {
    /** Null when offline or nothing is known there; callers retry later. */
    suspend fun addressOf(point: GeoPoint): String?
}

/**
 * The platform geocoder first (on Play devices it's Google's address data: free, no key, and
 * strong for Indonesian street names), then Photon/OpenStreetMap as a fallback.
 */
@Singleton
class GeocoderAddressResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val places: PlaceSearch,
) : AddressResolver {

    override suspend fun addressOf(point: GeoPoint): String? =
        withTimeoutOrNull(TIMEOUT_MS) { platform(point) }
            ?: places.reverse(point)?.let { shortAddress(street = it.name, area = it.detail?.substringBefore(", "), line = null) }

    private suspend fun platform(point: GeoPoint): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(
                    point.latitude, point.longitude, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
        return address?.let {
            shortAddress(
                street = it.thoroughfare ?: it.featureName,
                area = it.subLocality ?: it.locality ?: it.subAdminArea,
                line = it.getAddressLine(0),
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}

/**
 * "Street, Area" when known. Feature names that are just a house number or a plus code
 * ("QX2C+9F") say nothing to a rider and are skipped; then the first two parts of the full
 * address line stand in.
 */
internal fun shortAddress(street: String?, area: String?, line: String?): String? {
    val s = street?.trim()?.takeIf { it.isNotEmpty() && !it.contains('+') && !it.all { c -> c.isDigit() || c == ' ' } }
    val a = area?.trim()?.takeIf { it.isNotEmpty() && it != s }
    if (s != null) return listOfNotNull(s, a).joinToString(", ")
    val fromLine = line?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.contains('+') }?.take(2)
    if (!fromLine.isNullOrEmpty()) return fromLine.joinToString(", ")
    return a
}
