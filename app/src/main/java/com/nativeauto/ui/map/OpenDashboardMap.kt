package com.nativeauto.ui.map

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nativeauto.BuildConfig
import com.nativeauto.ui.components.LocalMotionDemand
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.SpeedSample
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * [MapEngine.OpenFreeMap]: MapLibre drawing OpenFreeMap's free OpenStreetMap tiles. No key,
 * no account, no traffic. MapLibre credits OpenStreetMap through its "i" button, as the
 * tile terms require; keep it.
 */
@Composable
internal fun OpenDashboardMap(
    fix: SpeedSample?,
    locationGranted: Boolean,
    following: Boolean,
    onUserMovedCamera: () -> Unit,
    route: RouteOverlay,
    onLongPress: (GeoPoint) -> Unit,
    camera: MapCameraMemory,
    look: MapLook,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val topPaddingPx = with(LocalDensity.current) { MAP_TOP_PADDING.toPx().toDouble() }
    val userMoved by rememberUpdatedState(onUserMovedCamera)
    val longPress by rememberUpdatedState(onLongPress)
    // 12a (R4): the camera moving (a glide while following, a pan) asks for the fastest refresh rate.
    val motion = LocalMotionDemand.current
    val framePaddingPx = with(LocalDensity.current) { ROUTE_FRAME_PADDING.toPx().toInt() }
    val previewClearancePx = with(LocalDensity.current) { ROUTE_PREVIEW_CLEARANCE.toPx().toInt() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val density = LocalDensity.current.density
    val vehicle = remember { VehicleMarker(density) }
    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(
            context,
            MapLibreMapOptions.createFromAttributes(context)
                // A TextureView, so the map clips to the card's rounded corners and resizes
                // smoothly with the layout (a SurfaceView punches through both).
                .textureMode(true)
                .foregroundLoadColor(look.background)
                .compassEnabled(false) // heading-up while following; recenter restores it
                .apply { camera.saved?.let { camera(it.toMapLibre()) } }, // waking from sleep
        )
    }
    MapViewLifecycle(mapView)

    DisposableEffect(mapView) {
        mapView.getMapAsync { m ->
            // Once per map instance: a second count means the map was recreated.
            val n = MapDiagnostics.created(context)
            if (BuildConfig.DEBUG) Log.d(MAP_TAG, "map instance #$n for this activity (OpenFreeMap)")
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMoved()
            }
            m.addOnCameraMoveStartedListener { motion.set(MAP_MOTION, true) }
            m.addOnCameraIdleListener { motion.set(MAP_MOTION, false) }
            m.addOnMapLongClickListener { latLng ->
                longPress(GeoPoint(latLng.latitude, latLng.longitude))
                true
            }
            map = m
        }
        // Released (map sleep) or recreated: remember where the camera was. This runs before
        // MapViewLifecycle's onDispose, while the view is still alive.
        onDispose {
            map?.cameraPosition?.let { camera.saved = it.toSaved() }
            motion.set(MAP_MOTION, false)
        }
    }

    // The style, again on each theme change (the camera and the view stay). Effects keyed on
    // `style` then redraw the route and the marker on the new one.
    LaunchedEffect(map, look) {
        val m = map ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            style = null
            m.setStyle(Style.Builder().fromUri(look.styleUrl)) { loaded ->
                look.applyTo(loaded::getLayer)
                loaded.addRouteLayers(look)
                vehicle.install(loaded)
                if (BuildConfig.DEBUG) Log.d(MAP_TAG, "openfreemap style loaded (dark=${look.dark})")
                style = loaded
            }
        }
    }

    // The vehicle marker: our own layer, fed from our own fixes (real, fake or simulated).
    LaunchedEffect(style, locationGranted) {
        val s = style ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            if (!locationGranted) vehicle.hide(s) else fix?.let { vehicle.moveTo(s, it, jump = true) }
        }
    }

    // Starting guidance zooms in to riding level once, even from the preview's overview.
    var navZoomPending by remember { mutableStateOf(false) }
    LaunchedEffect(route.navigating) { navZoomPending = route.navigating }

    // Each new fix: the marker and (while following) the camera glide to it together, over the
    // same second with the same linear easing, so the marker holds still in the middle. A newer
    // fix cancels both and starts from wherever they are.
    LaunchedEffect(fix, following, map, style) {
        val f = fix ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) { // MapLibre is main-thread only (see the Google map)
            var jump = false
            if (following) {
                jump = m.follow(f, topPaddingPx, forceZoom = navZoomPending)
                navZoomPending = false
            }
            val s = style
            if (s != null && locationGranted) vehicle.moveTo(s, f, jump)
        }
    }

    // The route line and the destination pin.
    LaunchedEffect(style, route.line, route.destination) {
        val s = style ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) { s.showRoute(route.line, route.destination) }
    }

    // Previewing: frame the whole route, clear of the header and the preview card.
    LaunchedEffect(map, route.line, route.frame) {
        val m = map ?: return@LaunchedEffect
        val line = route.line?.takeIf { route.frame && it.size >= 2 } ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            val bounds = LatLngBounds.Builder().includes(line.map { LatLng(it.latitude, it.longitude) }).build()
            // Flat and north-up: an overview, not the tilted heading-up follow view.
            val padding = intArrayOf(framePaddingPx, topPaddingPx.toInt() + framePaddingPx / 2, framePaddingPx, previewClearancePx)
            m.getCameraForLatLngBounds(bounds, padding, 0.0, 0.0)?.let {
                m.easeCamera(CameraUpdateFactory.newCameraPosition(it), FOLLOW_ANIMATION_MS)
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun SavedCamera.toMapLibre(): CameraPosition = CameraPosition.Builder()
    .target(LatLng(latitude, longitude))
    .zoom(zoom - ZOOM_OFFSET)
    .bearing(bearing.toDouble())
    .tilt(tilt.toDouble())
    .build()

private fun CameraPosition.toSaved(): SavedCamera? {
    val t = target ?: return null
    return SavedCamera(t.latitude, t.longitude, (zoom + ZOOM_OFFSET).toFloat(), bearing.toFloat(), tilt.toFloat())
}

/**
 * MapLibre's tiles are 512 px against Google's 256, so the same view sits one zoom level
 * lower. [FollowCamera] thinks in Google levels; convert at the edges.
 */
private const val ZOOM_OFFSET = 1.0

/** First fix: jump (no slow fly-in from the whole world). Then: a ~1 s ease per fix at 1 Hz. */
/** Returns true when the camera jumped instead of gliding (the marker should jump too). */
private fun MapLibreMap.follow(fix: SpeedSample, topPaddingPx: Double, forceZoom: Boolean): Boolean {
    val current = cameraPosition
    val currentZoom = if (forceZoom) 0f else (current.zoom + ZOOM_OFFSET).toFloat()
    val t = FollowCamera.target(
        latitude = fix.latitude,
        longitude = fix.longitude,
        speedMps = fix.speedMps,
        courseDeg = fix.bearingDeg,
        currentZoom = currentZoom,
        currentBearing = current.bearing.toFloat(),
    )
    val update = CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(LatLng(t.latitude, t.longitude))
            .zoom(t.zoom - ZOOM_OFFSET)
            .tilt(t.tilt.toDouble())
            .bearing(t.bearing.toDouble())
            .padding(0.0, topPaddingPx, 0.0, 0.0) // centre the car below the floating header
            .build(),
    )
    // Linear, like the marker's glide; MapLibre's default ease-in-out would make it wobble.
    return when {
        forceZoom -> { easeCamera(update, FOLLOW_ANIMATION_MS, false); false }
        currentZoom < FollowCamera.MIN_FOLLOW_ZOOM -> { moveCamera(update); true }
        else -> { easeCamera(update, FOLLOW_ANIMATION_MS, false); false }
    }
}

/** Route line (dark casing under a bright line) and destination pin, under the labels and the dot. */
private fun Style.addRouteLayers(look: MapLook) {
    addSource(GeoJsonSource(ROUTE_SOURCE))
    addSource(GeoJsonSource(DESTINATION_SOURCE))
    val casing = LineLayer("$ROUTE_SOURCE-casing", ROUTE_SOURCE).withProperties(
        PropertyFactory.lineColor(look.routeCasing), PropertyFactory.lineWidth(11f),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    )
    val line = LineLayer("$ROUTE_SOURCE-line", ROUTE_SOURCE).withProperties(
        PropertyFactory.lineColor(look.route), PropertyFactory.lineWidth(7f),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    )
    val pin = CircleLayer(DESTINATION_SOURCE, DESTINATION_SOURCE).withProperties(
        PropertyFactory.circleRadius(10f),
        PropertyFactory.circleColor(look.destination),
        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
        PropertyFactory.circleStrokeWidth(3f),
    )
    // Above every road and building, below the labels that follow them (the first label layer
    // comes early, under the roads, so "below the first label" would hide the route). The
    // location dot is added later, on top of all of it.
    var at = layers.indexOfLast { it !is SymbolLayer } + 1
    listOf(casing, line, pin).forEach { addLayerAt(it, at++) }
}

private fun Style.showRoute(line: List<GeoPoint>?, destination: GeoPoint?) {
    if (!isFullyLoaded) return // replaced by a theme change; the new style draws the route
    getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(
        if (line != null && line.size >= 2) {
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            FeatureCollection.fromFeatures(emptyList())
        },
    )
    getSourceAs<GeoJsonSource>(DESTINATION_SOURCE)?.setGeoJson(
        destination?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
            ?: FeatureCollection.fromFeatures(emptyList()),
    )
}

private const val ROUTE_SOURCE = "nav-route"
private const val DESTINATION_SOURCE = "nav-destination"

/** MapView needs the host's lifecycle forwarded by hand; it isn't a lifecycle observer itself. */
@Composable
private fun MapViewLifecycle(mapView: MapView) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var reached = Lifecycle.State.INITIALIZED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                Lifecycle.Event.ON_ANY -> Unit
            }
            reached = event.targetState
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving composition before the host is destroyed: wind the map down ourselves.
            if (reached.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (reached.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            if (reached.isAtLeast(Lifecycle.State.CREATED)) mapView.onDestroy()
        }
    }
}
