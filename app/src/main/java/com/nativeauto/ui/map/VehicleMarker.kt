package com.nativeauto.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.core.graphics.createBitmap
import com.nativeauto.trip.SpeedSample
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The vehicle on the MapLibre map, drawn as our own symbol layer rather than MapLibre's
 * location component. We feed every fix ourselves anyway, and the component's puck didn't
 * draw reliably (it never showed on the emulator and flickered for the user).
 *
 * A blue dot, with a heading chevron while moving with a known course. Between fixes it glides
 * for the same second, with the same linear easing, as the follow camera, so while following
 * it stays put in the middle of the map instead of jumping ahead once a second.
 */
internal class VehicleMarker(density: Float) {
    private val chevron = chevronBitmap(density)
    private var shown: Shown? = null
    private val progress = Animatable(1f)

    private data class Shown(val lat: Double, val lon: Double, val bearing: Float?)

    fun install(style: Style) {
        style.addImage(CHEVRON, chevron)
        style.addSource(GeoJsonSource(SOURCE))
        // The disc is two circle layers, the heading a symbol on top. Circles render on every
        // GL stack; symbols don't on the emulator's SwiftShader, so there the dot still shows.
        // Added last: above the route, the labels and everything else.
        style.addLayer(
            CircleLayer(RIM, SOURCE).withProperties(
                PropertyFactory.circleRadius(RIM_RADIUS_DP),
                PropertyFactory.circleColor(OUTLINE_HEX),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeColor(SHADOW_HEX),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        style.addLayer(
            CircleLayer(CORE, SOURCE).withProperties(
                PropertyFactory.circleRadius(CORE_RADIUS_DP),
                PropertyFactory.circleColor(VEHICLE_HEX),
                PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
        style.addLayer(
            SymbolLayer(HEADING, SOURCE).withProperties(
                PropertyFactory.iconImage(CHEVRON),
                PropertyFactory.iconRotate(Expression.get(BEARING)),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ).withFilter(Expression.has(BEARING)),
        )
        shown?.let { draw(style, it) }
    }

    fun hide(style: Style) {
        if (!style.isFullyLoaded) return
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Glides from where the marker is to [fix]; jumps when [jump] (first fix, recentre from far). */
    suspend fun moveTo(style: Style, fix: SpeedSample, jump: Boolean) {
        val target = Shown(fix.latitude, fix.longitude, fix.bearingDeg?.takeIf { fix.speedMps >= MIN_ARROW_SPEED_MPS })
        val from = shown
        if (from == null || jump) {
            shown = target
            draw(style, target)
            return
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(FOLLOW_ANIMATION_MS, easing = LinearEasing)) {
            val t = value
            val now = Shown(
                lat = from.lat + (target.lat - from.lat) * t,
                lon = from.lon + (target.lon - from.lon) * t,
                bearing = target.bearing?.let { to -> from.bearing?.let { lerpAngle(it, to, t) } ?: to },
            )
            shown = now
            draw(style, now)
        }
    }

    private fun draw(style: Style, s: Shown) {
        // A theme change swaps the style while a glide is under way: the old one is then
        // invalid (MapLibre throws), and the new one redraws the marker once it's installed.
        if (!style.isFullyLoaded) return
        val feature = Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat))
        s.bearing?.let { feature.addNumberProperty(BEARING, it) }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(feature)
    }

    private companion object {
        const val SOURCE = "vehicle"
        const val RIM = "vehicle-rim"
        const val CORE = "vehicle-core"
        const val HEADING = "vehicle-heading"
        const val CHEVRON = "vehicle-chevron"
        const val RIM_RADIUS_DP = 13f
        const val CORE_RADIUS_DP = 10f
        const val BEARING = "bearing"
        /** Same threshold as the heading-up camera: below it, GPS course is noise. */
        const val MIN_ARROW_SPEED_MPS = FollowCamera.MIN_HEADING_SPEED_MPS
    }
}

private fun lerpAngle(from: Float, to: Float, t: Float): Float {
    val d = ((to - from + 540f) % 360f) - 180f
    return (from + d * t + 360f) % 360f
}

private const val VEHICLE_HEX = "#3FC8E0" // the theme cyan (ui/theme/Color.kt)
private const val OUTLINE_HEX = "#FFFFFF"
private const val SHADOW_HEX = "rgba(0, 0, 0, 0.4)" // CSS colour: #66000000 would read as RRGGBBAA

/** A white chevron pointing up (north before rotation), sized to sit inside the disc. */
private fun chevronBitmap(density: Float): Bitmap {
    val size = (20 * density).toInt()
    val b = createBitmap(size, size)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    val r = size / 2f
    val u = size / 20f
    Canvas(b).drawPath(
        Path().apply {
            moveTo(r, r - 8 * u)
            lineTo(r + 6.5f * u, r + 7 * u)
            lineTo(r, r + 3.5f * u)
            lineTo(r - 6.5f * u, r + 7 * u)
            close()
        },
        p,
    )
    return b
}
