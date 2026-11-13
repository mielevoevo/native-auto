package com.nativeauto.ui.map

import com.nativeauto.ui.components.LocalPaneMotion
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.BuildConfig
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.NavState
import com.nativeauto.nav.destination
import com.nativeauto.nav.routeGeometry
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.SpeedSample
import com.nativeauto.ui.nav.DestinationSearchSheet
import com.nativeauto.ui.nav.NavStatusCard
import com.nativeauto.ui.nav.NavigationBanner
import com.nativeauto.ui.nav.NavigationSummary
import com.nativeauto.ui.nav.RoutePreviewCard
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import java.util.WeakHashMap

/**
 * The map pane: a dark map with the car's position and a follow camera (centred, tilted,
 * heading-up). Panning, zooming or rotating by hand stops following and shows a recenter
 * button. The map itself comes from [MapEngine.current].
 *
 * Rule #3: this composable never leaves composition while the app is up. Hidden, it sits at
 * ~0 px; moved, it stays the same child of the dashboard. Either way the same map instance,
 * tiles and camera survive. [MapDiagnostics] counts instances so tests can check that.
 *
 * The one exception is **map sleep** (Phase 8b, [MapSleep]): off screen ([onScreen] false)
 * for 30 s, the map view itself is released and a plain background stands in. The follow
 * state lives here and the camera in the ViewModel, so on wake it comes back where it was,
 * fades in, and resumes following with the next fix.
 */
@Composable
fun MapPane(layout: DashboardLayoutState, onScreen: Boolean, viewModel: MapViewModel = hiltViewModel()) {
    LaunchedEffect(onScreen) { viewModel.setOnScreen(onScreen) }
    val awake by viewModel.awake.collectAsStateWithLifecycle()
    val nav by viewModel.nav.collectAsStateWithLifecycle()

    var following by rememberSaveable { mutableStateOf(true) }
    // Previewing frames the route; starting guidance, or leaving the preview, follows again.
    val navPhase = nav::class
    LaunchedEffect(navPhase) {
        following = nav !is NavState.Preview
    }
    // Only a map that has slept fades in; the first one appears as before.
    var slept by remember { mutableStateOf(false) }
    LaunchedEffect(awake) { if (!awake) slept = true }

    DashboardCard(
        title = "Map",
        pane = Pane.MAP,
        mode = layout.modeOf(Pane.MAP),
        layout = layout,
        color = AppTheme.colors.card,
        chromeOverContent = true,
    ) {
        val area = Modifier.weight(1f).fillMaxWidth()
        if (awake) {
            AwakeMap(
                viewModel = viewModel,
                nav = nav,
                following = following,
                onFollowingChange = { following = it },
                // The Navigation pane shows the turn and the ETA; the map then shows just the route.
                showNavPanels = !layout.isOnScreen(Pane.NAV),
                // 13a: a long-press drops a pin only in fullscreen; elsewhere the card is held like the others.
                pinEnabled = layout.fullscreen == Pane.MAP,
                fadeIn = slept,
                modifier = area,
            )
        } else {
            Box(area.background(AppTheme.colors.mapBackground).testTag("map_asleep"))
        }
    }
}

/** The live map and everything over it. Only composed while awake, so asleep nothing is collected. */
@Composable
private fun AwakeMap(
    viewModel: MapViewModel,
    nav: NavState,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    showNavPanels: Boolean,
    pinEnabled: Boolean,
    fadeIn: Boolean,
    modifier: Modifier,
) {
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val granted by viewModel.locationGranted.collectAsStateWithLifecycle()
    val traffic by viewModel.showTraffic.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val voice by viewModel.voiceGuidance.collectAsStateWithLifecycle()

    Box(modifier) {
        DashboardMap(
            fix = fix,
            locationGranted = granted,
            showTraffic = traffic,
            onToggleTraffic = { viewModel.setShowTraffic(!traffic) },
            nav = nav,
            unit = unit,
            voice = voice,
            following = following,
            onFollowingChange = onFollowingChange,
            showNavPanels = showNavPanels,
            pinEnabled = pinEnabled,
            camera = viewModel.camera,
            actions = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
        if (fadeIn) {
            // A cover in the map's own colour that fades away: works over both engines' views.
            val cover = remember { Animatable(1f) }
            LaunchedEffect(Unit) { cover.animateTo(0f, tween(durationMillis = WAKE_FADE_MS)) }
            Box(Modifier.matchParentSize().graphicsLayer { alpha = cover.value }.background(AppTheme.colors.mapBackground))
        }
    }
}

/** What the map engines draw for navigation. */
@Immutable
internal data class RouteOverlay(
    val line: List<GeoPoint>?,
    val destination: GeoPoint?,
    /** Previewing: frame the whole route instead of following. */
    val frame: Boolean,
    val navigating: Boolean,
)

/**
 * Which map draws the pane, fixed per build. Google needs a key (and a billing account);
 * without one the app falls back to MapLibre with OpenFreeMap's free OpenStreetMap tiles,
 * which has no live traffic.
 */
internal enum class MapEngine(val hasTraffic: Boolean, val bottomClearance: Dp) {
    // Cards at the bottom-left stay clear of the engine's credit, which the terms require visible.
    Google(hasTraffic = true, bottomClearance = 40.dp),
    OpenFreeMap(hasTraffic = false, bottomClearance = 44.dp);

    companion object {
        val current: MapEngine = if (BuildConfig.HAS_GOOGLE_MAPS_KEY) Google else OpenFreeMap
    }
}

@Composable
private fun DashboardMap(
    fix: SpeedSample?,
    locationGranted: Boolean,
    showTraffic: Boolean,
    onToggleTraffic: () -> Unit,
    nav: NavState,
    unit: SpeedUnit,
    voice: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    showNavPanels: Boolean,
    pinEnabled: Boolean,
    camera: MapCameraMemory,
    actions: MapViewModel,
    modifier: Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val stopFollowing = { onFollowingChange(false) }
    val navPhase = nav::class
    val look = MapLook.of(AppTheme.colors.isDark)

    val overlay = remember(nav.routeGeometry, nav.destination, navPhase) {
        RouteOverlay(
            line = nav.routeGeometry,
            destination = nav.destination?.point?.takeIf { nav !is NavState.Idle },
            frame = nav is NavState.Preview,
            navigating = nav is NavState.Navigating,
        )
    }
    // Long-press drops a destination pin (fullscreen only, 13a), except mid-guidance (too easy to do by accident).
    val onLongPress: (GeoPoint) -> Unit = { p -> if (pinEnabled && nav !is NavState.Navigating) actions.routeToPoint(p) }

    Box(modifier) {
        val motion = LocalPaneMotion.current
        val mapModifier = Modifier.fillMaxSize().holdSizeWhile { motion(Pane.MAP) }.testTag("map")
        when (MapEngine.current) {
            MapEngine.Google -> GoogleDashboardMap(fix, locationGranted, showTraffic, following, stopFollowing, overlay, onLongPress, camera, look, mapModifier)
            MapEngine.OpenFreeMap -> OpenDashboardMap(fix, locationGranted, following, stopFollowing, overlay, onLongPress, camera, look, mapModifier)
        }

        // Top: the next turn, or the planning / failure / arrival status.
        val top = Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = MAP_TOP_PADDING + 4.dp).widthIn(max = 560.dp)
        when (nav) {
            is NavState.Navigating -> if (showNavPanels) NavigationBanner(nav, unit, top)
            is NavState.Routing, is NavState.Failed, is NavState.Arrived ->
                if (showNavPanels) NavStatusCard(nav, onRetry = actions::retryRoute, onDismiss = actions::stopNavigation, modifier = top)
            else -> Unit
        }

        // Bottom: the route preview (full width), or what's left of the trip (left of the buttons).
        when (nav) {
            is NavState.Preview -> RoutePreviewCard(
                nav, unit,
                onStart = actions::startNavigation,
                onCancel = actions::stopNavigation,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = MapEngine.current.bottomClearance).widthIn(max = 560.dp),
            )
            is NavState.Navigating -> if (showNavPanels) NavigationSummary(
                nav, unit, nowMs = System.currentTimeMillis(),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = MapEngine.current.bottomClearance),
            )
            else -> Unit
        }

        if (nav !is NavState.Preview) {
            MapButtons(
                showTraffic = showTraffic,
                onToggleTraffic = onToggleTraffic.takeIf { MapEngine.current.hasTraffic },
                following = following,
                onRecenter = { onFollowingChange(true) }, // the follow effect restarts and glides to the latest fix
                navigating = nav is NavState.Navigating,
                voice = voice,
                onToggleVoice = { actions.setVoiceGuidance(!voice) },
                onSearch = { searching = true }.takeIf { nav is NavState.Idle || nav is NavState.Arrived || nav is NavState.Failed },
                onEndNavigation = actions::stopNavigation,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }

    if (searching) {
        DestinationSearchSheet(
            onPick = { place ->
                searching = false
                actions.routeTo(place)
            },
            onDismiss = { searching = false },
        )
    }
}

/** Full-size (56 dp) buttons, stacked bottom-right: big enough to hit with gloves on. */
@Composable
private fun MapButtons(
    showTraffic: Boolean,
    onToggleTraffic: (() -> Unit)?,
    following: Boolean,
    onRecenter: () -> Unit,
    navigating: Boolean,
    voice: Boolean,
    onToggleVoice: () -> Unit,
    onSearch: (() -> Unit)?,
    onEndNavigation: () -> Unit,
    modifier: Modifier,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (onSearch != null) {
            FloatingActionButton(
                onClick = { haptics.tap(); onSearch() },
                modifier = Modifier.testTag("map_search"),
                containerColor = AppTheme.colors.buttonQuiet,
                contentColor = AppTheme.colors.onButtonQuiet,
            ) {
                Icon(AppIcons.Search, contentDescription = "Navigate to…")
            }
        }
        if (navigating) {
            FloatingActionButton(
                onClick = { haptics.toggle(on = !voice); onToggleVoice() },
                modifier = Modifier.testTag("nav_voice").semantics {
                    role = Role.Switch
                    stateDescription = if (voice) "On" else "Off"
                },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(if (voice) AppIcons.VolumeUp else AppIcons.VolumeOff, contentDescription = "Voice guidance")
            }
            FloatingActionButton(
                onClick = { haptics.confirm(); onEndNavigation() },
                modifier = Modifier.testTag("nav_end"),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Icon(AppIcons.Close, contentDescription = "End navigation")
            }
        }
        if (onToggleTraffic != null) {
            FloatingActionButton(
                onClick = {
                    haptics.toggle(on = !showTraffic)
                    onToggleTraffic()
                },
                modifier = Modifier.testTag("map_traffic").semantics {
                    role = Role.Switch
                    stateDescription = if (showTraffic) "On" else "Off"
                },
                // On: navy fill, cyan icon. Off: quiet like the others.
                containerColor = if (showTraffic) AppTheme.colors.navy else AppTheme.colors.buttonQuiet,
                contentColor = if (showTraffic) AppTheme.colors.cyan else AppTheme.colors.onButtonQuiet,
            ) {
                Icon(AppIcons.Traffic, contentDescription = "Traffic")
            }
        }
        if (!following) {
            FloatingActionButton(
                onClick = {
                    haptics.tap()
                    onRecenter()
                },
                modifier = Modifier.testTag("map_recenter"),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(AppIcons.Navigation, contentDescription = "Recenter map")
            }
        }
    }
}

/**
 * Test hook for rule #3: map instances created per activity (weakly held). Anything above 1
 * for one activity means a layout change recreated the map.
 */
internal object MapDiagnostics {
    private val perActivity = WeakHashMap<Context, Int>()

    @Synchronized fun created(host: Context): Int = (perActivity.getOrDefault(host, 0) + 1).also { perActivity[host] = it }

    @Synchronized fun instances(host: Context): Int = perActivity.getOrDefault(host, 0)
}

internal const val MAP_TAG = "MapPane"
internal const val FOLLOW_ANIMATION_MS = 1_000
/** How long a map waking from sleep takes to fade in. */
internal const val WAKE_FADE_MS = 400
/** Room at the top for the card's floating header (title and buttons). */
internal val MAP_TOP_PADDING = 48.dp

/** Margin around a route framed for preview. */
internal val ROUTE_FRAME_PADDING = 40.dp

/** Room under a framed route for the preview card and the map credit. */
internal val ROUTE_PREVIEW_CLEARANCE = 150.dp

/** The map's key in [com.nativeauto.ui.components.MotionDemand]. */
internal const val MAP_MOTION = "map"
