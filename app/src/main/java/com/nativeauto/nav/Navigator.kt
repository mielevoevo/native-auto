package com.nativeauto.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.nativeauto.BuildConfig
import com.nativeauto.di.ApplicationScope
import com.nativeauto.location.LiveLocation
import com.nativeauto.location.toLocation
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.SpeedSample
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.stadiamaps.ferrostar.core.withJsonOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.TripState
import uniffi.ferrostar.VisualInstructionContent
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.WellKnownRouteProvider
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Turn-by-turn for the map pane: pick a place → preview the route → navigate → arrive. */
interface Navigator {
    val state: StateFlow<NavState>

    /** Plans a route from the current position. Replaces any preview or navigation. */
    fun routeTo(place: Place)

    /** Starts guidance on the previewed route. No-op unless previewing. */
    fun start()

    /** Plans again to the same place after a failure. */
    fun retry()

    /** Back to idle from any state: cancels planning, the preview or the guidance. */
    fun stop()

    /** A better name for the current destination (a dropped pin's street). Ignored if the destination changed. */
    fun rename(place: Place)
}

/**
 * [Navigator] on Ferrostar (open-source navigation core) with Valhalla's **motorcycle** costing
 * on the public FOSSGIS server. Motorcycle costing honours OpenStreetMap's `motorcycle=no`, which
 * keeps Indonesian toll roads (closed to motorcycles) out of routes. Narration is English: Valhalla
 * has no Indonesian.
 *
 * Location comes from [LiveLocation], so guidance, the map dot and the trip share one GPS
 * subscription. Guidance runs while the app is open (the dashboard keeps the screen on); it
 * doesn't start a foreground service of its own.
 */
@Singleton
class FerrostarNavigator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val live: LiveLocation,
    private val settings: SettingsRepository,
    private val recents: RecentPlaces,
    private val simulation: RouteSimulation,
    http: OkHttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) : Navigator {

    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    override val state: StateFlow<NavState> = _state.asStateFlow()

    private val httpProvider = http.toOkHttpClientProvider()
    private val location = LiveLocationProvider(live)
    private var core: FerrostarCore? = null
    private var coreUnit: SpeedUnit? = null
    private var previewRoute: Route? = null
    private var job: Job? = null
    private var tts: AndroidTtsObserver? = null

    override fun routeTo(place: Place) {
        endGuidance()
        _state.value = NavState.Routing(place)
        job = scope.launch { plan(place) }
    }

    override fun retry() {
        val failed = _state.value as? NavState.Failed ?: return
        routeTo(failed.destination)
    }

    override fun start() {
        val preview = _state.value as? NavState.Preview ?: return
        val route = previewRoute ?: return
        val core = core ?: return
        job?.cancel()
        job = scope.launch {
            recents.remember(preview.destination)
            if (BuildConfig.DEBUG && settings.simulateNavigation.first()) {
                simulation.route.value = route.geometry.map { GeoPoint(it.lat, it.lng) }
            }
            // 13a: muted means no TTS and no audio focus at all. Ferrostar's observer asks for audio
            // focus *before* it checks its mute flag, so a muted observer still ducked the music;
            // while muted it is detached from the core instead (and created only when needed).
            fun applyVoice(on: Boolean) {
                if (on) {
                    val speaker = voice()
                    speaker.setMuted(false)
                    core.spokenInstructionObserver = speaker
                } else {
                    core.spokenInstructionObserver = null
                    tts?.setMuted(true) // stops a sentence already being spoken
                }
            }
            applyVoice(settings.voiceGuidance.first()) // before the first instruction can fire
            // The mute button can flip the setting mid-route.
            val mute = launch { settings.voiceGuidance.drop(1).collect(::applyVoice) }
            core.startNavigation(route)
            // Until arrival; then guidance stops and "Arrived" stays up until dismissed.
            core.state.first { nav ->
                val mapped = nav.toNavState(currentDestination(preview.destination), location.lastFix)
                if (mapped != null) _state.value = mapped
                mapped is NavState.Arrived
            }
            mute.cancel()
            endGuidance(keepState = true)
        }
    }

    override fun stop() {
        endGuidance()
        _state.value = NavState.Idle
    }

    override fun rename(place: Place) {
        _state.update { s ->
            if (s.destination?.point != place.point) return@update s
            when (s) {
                is NavState.Routing -> s.copy(destination = place)
                is NavState.Preview -> s.copy(destination = place)
                is NavState.Navigating -> s.copy(destination = place)
                is NavState.Arrived -> s.copy(destination = place)
                is NavState.Failed -> s.copy(destination = place)
                NavState.Idle -> s
            }
        }
    }

    /** The destination as last named (a rename may land after guidance started). */
    private fun currentDestination(fallback: Place): Place =
        _state.value.destination?.takeIf { it.point == fallback.point } ?: fallback

    private suspend fun plan(place: Place) {
        val origin = live.latest ?: withTimeoutOrNull(LOCATION_WAIT_MS) { live.fixes.first() }
        if (origin == null) {
            _state.value = NavState.Failed(place, NavFailure.NoLocation)
            return
        }
        val core = core(settings.speedUnit.first())
        val routes = try {
            core.getRoutes(
                origin.toLocation().toUserLocation(),
                listOf(Waypoint(GeographicCoordinate(place.point.latitude, place.point.longitude), WaypointKind.BREAK)),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "route request failed", e)
            _state.value = NavState.Failed(place, NavFailure.Offline)
            return
        } catch (e: Exception) {
            // Valhalla answers "no route" (e.g. across the sea) as an error the adapter rethrows.
            if (BuildConfig.DEBUG) Log.w(TAG, "no route", e)
            _state.value = NavState.Failed(place, NavFailure.NoRoute)
            return
        }
        val route = routes.firstOrNull()
        if (route == null) {
            _state.value = NavState.Failed(place, NavFailure.NoRoute)
            return
        }
        previewRoute = route
        _state.value = NavState.Preview(
            place,
            RoutePlan(route.geometry.map { GeoPoint(it.lat, it.lng) }, route.distance, route.steps.sumOf { it.duration }),
        )
    }

    /** One core per unit: the unit only changes the narration ("in 300 metres"). */
    private fun core(unit: SpeedUnit): FerrostarCore {
        core?.takeIf { coreUnit == unit }?.let { return it }
        val provider = WellKnownRouteProvider.Valhalla(VALHALLA_URL, "motorcycle").withJsonOptions(
            mapOf(
                "units" to if (unit == SpeedUnit.MPH) "miles" else "kilometers",
                "language" to "en-US",
            ),
        )
        return FerrostarCore(
            wellKnownRouteProvider = provider,
            httpClient = httpProvider,
            locationProvider = location,
            navigationControllerConfig = NavigationControllerConfig(
                WaypointAdvanceMode.WaypointWithinRange(ARRIVAL_RADIUS_M),
                stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
                stepAdvanceDistanceToEndOfStep(10u, 32u),
                // 25 m off the line (with a fix better than 20 m) counts as a wrong turn.
                RouteDeviationTracking.StaticThreshold(20u, 25.0),
                CourseFiltering.SNAP_TO_ROUTE,
            ),
        ).also {
            // FOSSGIS allows ~1 request/s per user; keep reroutes well apart.
            it.minimumTimeBeforeRecalculation = 8.seconds
            core = it
            coreUnit = unit
        }
    }

    private fun voice(): AndroidTtsObserver = tts ?: AndroidTtsObserver(
        context,
        statusObserver = object : AndroidTtsStatusListener {
            // The narration is English; don't let an Indonesian voice read it.
            override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
                tts?.language = Locale.US
            }
            override fun onTtsShutdownAndRelease() = Unit
            override fun onTtsSpeakError(utteranceId: String, status: Int) = Unit
        },
    ).also {
        it.start()
        tts = it
    }

    private fun endGuidance(keepState: Boolean = false) {
        if (!keepState) {
            job?.cancel()
            job = null
        }
        core?.stopNavigation()
        tts?.shutdown()
        tts = null
        simulation.route.value = null
        previewRoute = null
    }

    private companion object {
        const val TAG = "Navigator"
        const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
        const val LOCATION_WAIT_MS = 10_000L
        const val ARRIVAL_RADIUS_M = 40.0
    }
}

/** Ferrostar reads the shared fixes; it never opens its own GPS subscription. */
private class LiveLocationProvider(private val live: LiveLocation) : NavigationLocationProviding {
    /** The fix Ferrostar is working on, to pair with the state it produces. */
    @Volatile var lastFix: SpeedSample? = null
        private set

    override suspend fun lastLocation() = live.latest?.toLocation()

    override fun locationUpdates(intervalMillis: Long): Flow<android.location.Location> =
        live.fixes.map { lastFix = it; it.toLocation() }
}

/** Ferrostar's state → ours. Null while Ferrostar is idle (e.g. between stop and start). */
internal fun NavigationState.toNavState(destination: Place, fix: SpeedSample?): NavState? =
    when (val trip = tripState) {
        is TripState.Idle -> null
        is TripState.Complete -> NavState.Arrived(destination)
        is TripState.Navigating -> {
            val snapped = trip.snappedUserLocation
            NavState.Navigating(
                destination = destination,
                geometry = routeGeometry.map { GeoPoint(it.lat, it.lng) },
                instruction = trip.visualInstruction?.primaryContent?.toInstruction(),
                nextInstruction = trip.remainingSteps.getOrNull(1)
                    ?.visualInstructions?.firstOrNull()?.primaryContent?.toInstruction(),
                distanceToManeuverM = trip.progress.distanceToNextManeuver,
                distanceRemainingM = trip.progress.distanceRemaining,
                durationRemainingS = trip.progress.durationRemaining,
                position = fix?.copy(
                    latitude = snapped.coordinates.lat,
                    longitude = snapped.coordinates.lng,
                    bearingDeg = snapped.courseOverGround?.degrees?.toFloat() ?: fix.bearingDeg,
                ),
                offRoute = trip.deviation is RouteDeviation.Deviation,
                rerouting = isCalculatingNewRoute,
            )
        }
    }

internal fun VisualInstructionContent.toInstruction() = Instruction(
    text = text,
    kind = when (maneuverType) {
        ManeuverType.DEPART -> ManeuverKind.Depart
        ManeuverType.ARRIVE -> ManeuverKind.Arrive
        ManeuverType.MERGE -> ManeuverKind.Merge
        ManeuverType.FORK -> ManeuverKind.Fork
        ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP -> ManeuverKind.Ramp
        ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN,
        ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY -> ManeuverKind.Roundabout
        ManeuverType.CONTINUE, ManeuverType.NEW_NAME, ManeuverType.NOTIFICATION -> ManeuverKind.Continue
        ManeuverType.TURN, ManeuverType.END_OF_ROAD, null -> ManeuverKind.Turn
    },
    direction = when (maneuverModifier) {
        ManeuverModifier.U_TURN -> TurnDirection.UTurn
        ManeuverModifier.SHARP_RIGHT -> TurnDirection.SharpRight
        ManeuverModifier.RIGHT -> TurnDirection.Right
        ManeuverModifier.SLIGHT_RIGHT -> TurnDirection.SlightRight
        ManeuverModifier.STRAIGHT -> TurnDirection.Straight
        ManeuverModifier.SLIGHT_LEFT -> TurnDirection.SlightLeft
        ManeuverModifier.LEFT -> TurnDirection.Left
        ManeuverModifier.SHARP_LEFT -> TurnDirection.SharpLeft
        null -> null
    },
    roundaboutExitDegrees = roundaboutExitDegrees?.toInt(),
)
