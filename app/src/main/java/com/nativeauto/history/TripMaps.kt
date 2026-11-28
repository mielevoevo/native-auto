package com.nativeauto.history

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.toColorInt
import com.nativeauto.nav.GeoPoint
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.ui.map.MapLook
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max

/** A small overview map per trip: the path from start (text colour) to end (red), in the theme's map style. */
interface TripMaps {
    /** [widthDp] × [heightDp] at screen density; null when it can't be drawn (offline, first time). */
    suspend fun overview(trip: TripRecord, widthDp: Int, heightDp: Int, dark: Boolean): Bitmap?

    /** 4.8: the same with the route in shades of [accentArgb] by speed; null for a trip without a saved track (or offline). */
    suspend fun speedOverview(trip: TripRecord, widthDp: Int, heightDp: Int, dark: Boolean, accentArgb: Int, unit: SpeedUnit): Bitmap? = null

    suspend fun forget(tripId: Long)
}

/**
 * MapLibre's snapshotter with the dashboard's own OpenFreeMap style, whichever engine draws the
 * live map. It renders off screen (no MapView), so rule #3's single live map is untouched.
 * Each picture is drawn once and cached as a PNG; one snapshot at a time.
 */
@Singleton
class SnapshotTripMaps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TripDao,
) : TripMaps {

    private val lock = Mutex()
    private val dir get() = File(context.cacheDir, "trip_maps")

    override suspend fun overview(trip: TripRecord, widthDp: Int, heightDp: Int, dark: Boolean): Bitmap? = lock.withLock {
        val look = MapLook.of(dark)
        cached(File(dir, "${trip.id}_${widthDp}x${heightDp}_${if (dark) "dark" else "light"}.png")) {
            val points = Polyline.decode(trip.track)
            if (points.size < 2) null else withContext(Dispatchers.Main) { snapshot(points, route(points, look), paddedBounds(points), widthDp, heightDp, look) }
        }
    }

    override suspend fun speedOverview(trip: TripRecord, widthDp: Int, heightDp: Int, dark: Boolean, accentArgb: Int, unit: SpeedUnit): Bitmap? =
        lock.withLock {
            val look = MapLook.of(dark)
            val hex = String.format(Locale.US, "%06x", accentArgb and 0xFFFFFF)
            cached(File(dir, "${trip.id}_${widthDp}x${heightDp}_${if (dark) "dark" else "light"}_speed_${hex}_${unit.name.lowercase(Locale.US)}.png")) {
                val bands = withContext(Dispatchers.Default) { speedBands(dao.points(trip.id), unit) }
                if (bands.isEmpty()) {
                    null
                } else {
                    val shades = speedShades(accentArgb, dark).map { String.format(Locale.US, "#%06X", it and 0xFFFFFF) }
                    val lines = FeatureCollection.fromFeatures(
                        bands.map { b ->
                            Feature.fromGeometry(LineString.fromLngLats(b.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
                                .apply { addStringProperty(COLOR, shades[b.step]) }
                        },
                    )
                    val points = bands.flatMap { it.points }
                    withContext(Dispatchers.Main) { snapshot(points, lines, paddedBounds(points, legend = true), widthDp, heightDp, look) }
                }
            }
        }

    /** The picture from [file], else drawn and kept there. */
    private suspend fun cached(file: File, draw: suspend () -> Bitmap?): Bitmap? {
        withContext(Dispatchers.IO) { file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }?.let { return it }
        val bitmap = draw() ?: return null
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return bitmap
    }

    private fun route(points: List<GeoPoint>, look: MapLook): FeatureCollection = FeatureCollection.fromFeature(
        Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })).apply { addStringProperty(COLOR, look.route) },
    )

    override suspend fun forget(tripId: Long) = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.startsWith("${tripId}_") }?.forEach { it.delete() }
        Unit
    }

    private suspend fun snapshot(points: List<GeoPoint>, line: FeatureCollection, bounds: LatLngBounds, widthDp: Int, heightDp: Int, look: MapLook): Bitmap? =
        suspendCancellableCoroutine { cont ->
            MapLibre.getInstance(context)
            val ends = FeatureCollection.fromFeatures(
                listOf(
                    Feature.fromGeometry(Point.fromLngLat(points.first().longitude, points.first().latitude)).apply { addStringProperty("kind", "start") },
                    Feature.fromGeometry(Point.fromLngLat(points.last().longitude, points.last().latitude)).apply { addStringProperty("kind", "end") },
                ),
            )
            val style = org.maplibre.android.maps.Style.Builder()
                .fromUri(look.styleUrl)
                .withSource(GeoJsonSource(TRACK, line))
                .withSource(GeoJsonSource(ENDS, ends))
                .withLayer(
                    LineLayer("$TRACK-casing", TRACK).withProperties(
                        PropertyFactory.lineColor(look.routeCasing), PropertyFactory.lineWidth(8f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                .withLayer(
                    LineLayer("$TRACK-line", TRACK).withProperties(
                        PropertyFactory.lineColor(Expression.toColor(Expression.get(COLOR))), PropertyFactory.lineWidth(4.5f),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    ),
                )
                .withLayer(
                    CircleLayer(ENDS, ENDS).withProperties(
                        PropertyFactory.circleRadius(6.5f),
                        PropertyFactory.circleColor(
                            Expression.match(Expression.get("kind"), Expression.literal("start"), Expression.color(look.start.toColorInt()), Expression.color(look.destination.toColorInt())),
                        ),
                        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
            val options = MapSnapshotter.Options(widthDp, heightDp)
                .withStyleBuilder(style)
                .withRegion(bounds)
                .withPixelRatio(context.resources.displayMetrics.density)
                .withLogo(false) // the history screen carries the OpenStreetMap credit instead
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.setObserver(object : MapSnapshotter.Observer {
                override fun onDidFinishLoadingStyle() = look.applyTo(snapshotter::getLayer)
                override fun onStyleImageMissing(imageName: String) = Unit
            })
            snapshotter.start(
                { snap -> if (cont.isActive) cont.resume(snap.bitmap) },
                { _ -> if (cont.isActive) cont.resume(null) },
            )
            cont.invokeOnCancellation { snapshotter.cancel() }
        }

    private companion object {
        const val TRACK = "trip-track"
        const val ENDS = "trip-ends"
        const val COLOR = "color"
    }
}

/** The path's bounds plus a margin, never tighter than ~400 m so a short hop isn't a blur. [legend]: room along the top for it. */
internal fun paddedBounds(points: List<GeoPoint>, legend: Boolean = false): LatLngBounds {
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val padLat = max((maxLat - minLat) * 0.18, 0.002)
    val padLon = max((maxLon - minLon) * 0.18, 0.002)
    return LatLngBounds.Builder()
        .include(LatLng(minLat - padLat, minLon - padLon))
        .include(LatLng(maxLat + padLat + if (legend) (maxLat - minLat + 2 * padLat) * 0.2 else 0.0, maxLon + padLon))
        .build()
}

