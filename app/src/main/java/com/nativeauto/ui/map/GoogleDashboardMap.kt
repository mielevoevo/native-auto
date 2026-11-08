package com.nativeauto.ui.map

import android.location.Location
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapEffect
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MapsComposeExperimentalApi
import com.google.maps.android.compose.rememberCameraPositionState
import com.nativeauto.BuildConfig
import com.nativeauto.R
import com.nativeauto.location.toLocation
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.SpeedSample
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Polyline
import kotlinx.coroutines.withContext

/** [MapEngine.Google]: maps-compose with our dark style and live traffic. Needs a key. */
@OptIn(MapsComposeExperimentalApi::class)
@Composable
internal fun GoogleDashboardMap(
    fix: SpeedSample?,
    locationGranted: Boolean,
    showTraffic: Boolean,
    following: Boolean,
    onUserMovedCamera: () -> Unit,
    route: RouteOverlay,
    onLongPress: (GeoPoint) -> Unit,
    memory: MapCameraMemory,
    look: MapLook,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val camera = rememberCameraPositionState {
        // Waking from sleep: where it was; otherwise the whole world until the first fix.
        position = memory.saved?.let {
            CameraPosition.Builder().target(LatLng(it.latitude, it.longitude)).zoom(it.zoom).bearing(it.bearing).tilt(it.tilt).build()
        } ?: CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }
    DisposableEffect(camera) {
        onDispose {
            val p = camera.position
            memory.saved = SavedCamera(p.target.latitude, p.target.longitude, p.zoom, p.bearing, p.tilt)
        }
    }
    val dot = remember { FixLocationSource() }
    val userMoved by rememberUpdatedState(onUserMovedCamera)

    val properties = remember(locationGranted, showTraffic, look) {
        MapProperties(
            // The SDK throws if the layer is enabled without location permission.
            isMyLocationEnabled = locationGranted,
            isTrafficEnabled = showTraffic,
            isBuildingEnabled = true,
            // Light: Google's own style, already made for daylight.
            mapStyleOptions = if (look.dark) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark) else null,
        )
    }
    val uiSettings = remember {
        MapUiSettings(
            compassEnabled = false,        // heading-up while following; recenter restores it
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false, // our recenter button replaces it
            zoomControlsEnabled = false,
            indoorLevelPickerEnabled = false,
        )
    }

    // A gesture (pan, pinch, rotate, tilt) hands the camera to the user. Keyed on the move
    // reason, not isMoving: while following, a glide runs almost all the time, so isMoving
    // barely ever flips and a pan mid-glide would go unnoticed.
    LaunchedEffect(camera.cameraMoveStartedReason) {
        if (camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) userMoved()
    }

    var navZoomPending by remember { mutableStateOf(false) }
    LaunchedEffect(route.navigating) { navZoomPending = route.navigating }

    // Each new fix moves the dot; while following, the camera glides after it. A newer fix
    // cancels the glide in progress and starts from wherever the camera is.
    LaunchedEffect(fix, following) {
        val f = fix ?: return@LaunchedEffect
        // The Maps SDK throws off the main thread. Effects normally run there, but not under
        // the v2 Compose test dispatcher, so pin it.
        withContext(AndroidUiDispatcher.Main) {
            dot.push(f)
            if (following) {
                camera.follow(f, firstFix = camera.position.zoom < FollowCamera.MIN_FOLLOW_ZOOM, forceZoom = navZoomPending)
                navZoomPending = false
            }
        }
    }

    val framePaddingPx = with(LocalDensity.current) { ROUTE_FRAME_PADDING.roundToPx() }
    LaunchedEffect(route.line, route.frame) {
        val line = route.line?.takeIf { route.frame && it.size >= 2 } ?: return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            val bounds = LatLngBounds.builder().apply { line.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, framePaddingPx), FOLLOW_ANIMATION_MS) }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        // Dark before the first tiles arrive (and without a valid key), instead of the
        // SDK's light beige flash.
        googleMapOptionsFactory = { GoogleMapOptions().backgroundColor(look.background) },
        properties = properties,
        uiSettings = uiSettings,
        locationSource = dot,
        mapColorScheme = if (look.dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
        // Keep the Google logo and the camera's centre clear of the floating header.
        // While previewing, also clear of the preview card, so the framed route sits above it.
        contentPadding = PaddingValues(top = MAP_TOP_PADDING, bottom = if (route.frame) ROUTE_PREVIEW_CLEARANCE else 0.dp),
        onMapLoaded = { if (BuildConfig.DEBUG) Log.d(MAP_TAG, "google map loaded") },
        onMapLongClick = { onLongPress(GeoPoint(it.latitude, it.longitude)) },
    ) {
        route.line?.takeIf { it.size >= 2 }?.let { line ->
            Polyline(points = line.map { LatLng(it.latitude, it.longitude) }, color = Color(look.routeCasing.toColorInt()), width = 30f, jointType = JointType.ROUND)
            Polyline(points = line.map { LatLng(it.latitude, it.longitude) }, color = Color(look.route.toColorInt()), width = 18f, jointType = JointType.ROUND)
        }
        route.destination?.let {
            Circle(center = LatLng(it.latitude, it.longitude), radius = 12.0, fillColor = Color(look.destination.toColorInt()), strokeColor = Color.White, strokeWidth = 6f)
        }
        MapEffect(Unit) {
            // Runs once per map instance: a second count means the map was recreated.
            val n = MapDiagnostics.created(context)
            if (BuildConfig.DEBUG) Log.d(MAP_TAG, "map instance #$n for this activity (Google)")
        }
    }
}

/** First fix: jump (no slow fly-in from the whole world). Then: a ~1 s glide per fix at 1 Hz. */
private suspend fun CameraPositionState.follow(fix: SpeedSample, firstFix: Boolean, forceZoom: Boolean) {
    val t = FollowCamera.target(
        latitude = fix.latitude,
        longitude = fix.longitude,
        speedMps = fix.speedMps,
        courseDeg = fix.bearingDeg,
        currentZoom = if (forceZoom) 0f else position.zoom,
        currentBearing = position.bearing,
    )
    val update = CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder().target(LatLng(t.latitude, t.longitude)).zoom(t.zoom).tilt(t.tilt).bearing(t.bearing).build(),
    )
    if (firstFix && !forceZoom) move(update) else animate(update, durationMs = FOLLOW_ANIMATION_MS)
}

/** Feeds our fixes (real or fake) to the map's my-location dot, instead of its own GPS. */
private class FixLocationSource : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var last: Location? = null

    override fun activate(listener: LocationSource.OnLocationChangedListener) {
        this.listener = listener
        last?.let(listener::onLocationChanged)
    }

    override fun deactivate() {
        listener = null
    }

    fun push(fix: SpeedSample) {
        val location = fix.toLocation()
        last = location
        listener?.onLocationChanged(location)
    }
}
