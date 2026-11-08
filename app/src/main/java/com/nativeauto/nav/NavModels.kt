package com.nativeauto.nav

import com.nativeauto.trip.SpeedSample

/** Plain coordinates, so nothing outside `nav/` sees Ferrostar's or MapLibre's types. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** A destination: a search result, a recent place, or a pin dropped on the map. */
data class Place(val name: String, val detail: String?, val point: GeoPoint)

enum class ManeuverKind { Depart, Turn, Continue, Merge, Fork, Ramp, Roundabout, Arrive }

enum class TurnDirection { Straight, SlightLeft, Left, SharpLeft, SlightRight, Right, SharpRight, UTurn }

data class Instruction(
    val text: String,
    val kind: ManeuverKind,
    val direction: TurnDirection?,
    /** Roundabouts: where the exit is, degrees counter-clockwise from the entry (Valhalla's convention). */
    val roundaboutExitDegrees: Int? = null,
)

data class RoutePlan(val geometry: List<GeoPoint>, val distanceM: Double, val durationS: Double)

enum class NavFailure { NoLocation, Offline, NoRoute }

/** What the map pane shows. The navigator moves through these; the UI only draws them. */
sealed interface NavState {
    data object Idle : NavState

    data class Routing(val destination: Place) : NavState

    data class Preview(val destination: Place, val plan: RoutePlan) : NavState

    data class Navigating(
        val destination: Place,
        val geometry: List<GeoPoint>,
        val instruction: Instruction?,
        /** The maneuver after [instruction], for a "then" hint when they come close together. */
        val nextInstruction: Instruction?,
        val distanceToManeuverM: Double,
        val distanceRemainingM: Double,
        val durationRemainingS: Double,
        /** The latest fix, snapped to the route. Drives the dot and the camera while navigating. */
        val position: SpeedSample?,
        val offRoute: Boolean,
        val rerouting: Boolean,
    ) : NavState

    data class Arrived(val destination: Place) : NavState

    data class Failed(val destination: Place, val reason: NavFailure) : NavState
}

/** The route line, whichever state carries one. */
val NavState.routeGeometry: List<GeoPoint>?
    get() = when (this) {
        is NavState.Preview -> plan.geometry
        is NavState.Navigating -> geometry
        else -> null
    }

val NavState.destination: Place?
    get() = when (this) {
        NavState.Idle -> null
        is NavState.Routing -> destination
        is NavState.Preview -> destination
        is NavState.Navigating -> destination
        is NavState.Arrived -> destination
        is NavState.Failed -> destination
    }
