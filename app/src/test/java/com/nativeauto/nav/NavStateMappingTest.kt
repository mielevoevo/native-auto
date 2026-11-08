package com.nativeauto.nav

import com.nativeauto.trip.SpeedSample
import com.stadiamaps.ferrostar.core.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.CourseOverGround
import uniffi.ferrostar.DeviationKind
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.TripProgress
import uniffi.ferrostar.TripState
import uniffi.ferrostar.TripSummary
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import java.time.Instant
import java.util.Date

/** Ferrostar's state → ours, built from Ferrostar's own (plain data) model classes. */
class NavStateMappingTest {

    private val destination = Place("Tugu", null, GeoPoint(-7.7829, 110.3671))
    private val geometry = listOf(GeographicCoordinate(-7.80, 110.36), GeographicCoordinate(-7.79, 110.37))

    private fun content(
        text: String,
        type: ManeuverType? = ManeuverType.TURN,
        modifier: ManeuverModifier? = ManeuverModifier.LEFT,
        exitDegrees: UShort? = null,
    ) = VisualInstructionContent(text, type, modifier, exitDegrees, null, emptyList())

    private fun visual(text: String, type: ManeuverType? = ManeuverType.TURN, modifier: ManeuverModifier? = ManeuverModifier.LEFT) =
        VisualInstruction(content(text, type, modifier), null, null, 100.0)

    private fun step(vararg visuals: VisualInstruction) = RouteStep(
        geometry, 500.0, 60.0, null, emptyList(), "", visuals.toList(), emptyList(), null, emptyList(), DrivingSide.LEFT, null,
    )

    private fun location(lat: Double, lng: Double, course: UShort? = null) = UserLocation(
        GeographicCoordinate(lat, lng), 5.0, course?.let { CourseOverGround(it, 5u) }, Instant.EPOCH, null,
    )

    private val summary = TripSummary(1_000.0, 1_000.0, Date(0), null)

    private fun navigating(
        snapped: UserLocation = location(-7.795, 110.365, course = 45u),
        steps: List<RouteStep> = listOf(step(visual("Jalan Malioboro")), step(visual("Jalan Mangkubumi", modifier = ManeuverModifier.RIGHT))),
        deviation: RouteDeviation = RouteDeviation.NoDeviation,
        visualInstruction: VisualInstruction? = visual("Jalan Malioboro"),
        calculating: Boolean = false,
    ) = NavigationState(
        TripState.Navigating(
            0uL, location(-7.7951, 110.3651), snapped, steps, emptyList(),
            TripProgress(120.0, 2_400.0, 300.0, 15.0), summary, deviation, visualInstruction, null, null,
        ),
        geometry,
        calculating,
    )

    private val fix = SpeedSample(1_000L, 11f, 0.4f, 5f, -7.7951, 110.3651, bearingDeg = 30f)

    @Test fun `idle maps to null and complete to arrived`() {
        assertNull(NavigationState(TripState.Idle(null), geometry, false).toNavState(destination, fix))
        val complete = NavigationState(TripState.Complete(location(-7.78, 110.36), summary), geometry, false)
        assertEquals(NavState.Arrived(destination), complete.toNavState(destination, fix))
    }

    @Test fun `navigating carries the route, the banner, the next step and the progress`() {
        val s = navigating().toNavState(destination, fix) as NavState.Navigating
        assertEquals(destination, s.destination)
        assertEquals(listOf(GeoPoint(-7.80, 110.36), GeoPoint(-7.79, 110.37)), s.geometry)
        assertEquals(Instruction("Jalan Malioboro", ManeuverKind.Turn, TurnDirection.Left), s.instruction)
        // remainingSteps[0] is the current step; the "then" hint is the one after it.
        assertEquals(Instruction("Jalan Mangkubumi", ManeuverKind.Turn, TurnDirection.Right), s.nextInstruction)
        assertEquals(120.0, s.distanceToManeuverM, 0.0)
        assertEquals(2_400.0, s.distanceRemainingM, 0.0)
        assertEquals(300.0, s.durationRemainingS, 0.0)
        assertFalse(s.offRoute)
        assertFalse(s.rerouting)
    }

    @Test fun `the position is the fix snapped to the route, with the route's course`() {
        val s = navigating().toNavState(destination, fix) as NavState.Navigating
        assertEquals(fix.copy(latitude = -7.795, longitude = 110.365, bearingDeg = 45f), s.position)
    }

    @Test fun `without a snapped course the fix's own bearing is kept`() {
        val s = navigating(snapped = location(-7.795, 110.365, course = null)).toNavState(destination, fix) as NavState.Navigating
        assertEquals(30f, s.position!!.bearingDeg!!, 0f)
    }

    @Test fun `no fix yet means no position`() {
        val s = navigating().toNavState(destination, null) as NavState.Navigating
        assertNull(s.position)
    }

    @Test fun `last step has no next instruction, and a missing banner is null`() {
        val s = navigating(steps = listOf(step(visual("Tugu"))), visualInstruction = null)
            .toNavState(destination, fix) as NavState.Navigating
        assertNull(s.instruction)
        assertNull(s.nextInstruction)
        // A next step without visual instructions also gives no hint.
        val t = navigating(steps = listOf(step(visual("a")), step())).toNavState(destination, fix) as NavState.Navigating
        assertNull(t.nextInstruction)
    }

    @Test fun `deviation and rerouting are passed through`() {
        val s = navigating(deviation = RouteDeviation.Deviation(DeviationKind.CompletelyOffRoute(80.0)), calculating = true)
            .toNavState(destination, fix) as NavState.Navigating
        assertTrue(s.offRoute)
        assertTrue(s.rerouting)
    }

    @Test fun `maneuver types map to our kinds`() {
        fun kind(type: ManeuverType?) = content("", type).toInstruction().kind
        assertEquals(ManeuverKind.Depart, kind(ManeuverType.DEPART))
        assertEquals(ManeuverKind.Arrive, kind(ManeuverType.ARRIVE))
        assertEquals(ManeuverKind.Merge, kind(ManeuverType.MERGE))
        assertEquals(ManeuverKind.Fork, kind(ManeuverType.FORK))
        assertEquals(ManeuverKind.Ramp, kind(ManeuverType.ON_RAMP))
        assertEquals(ManeuverKind.Ramp, kind(ManeuverType.OFF_RAMP))
        for (t in listOf(ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN, ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY)) {
            assertEquals(t.name, ManeuverKind.Roundabout, kind(t))
        }
        for (t in listOf(ManeuverType.CONTINUE, ManeuverType.NEW_NAME, ManeuverType.NOTIFICATION)) {
            assertEquals(t.name, ManeuverKind.Continue, kind(t))
        }
        assertEquals(ManeuverKind.Turn, kind(ManeuverType.TURN))
        assertEquals(ManeuverKind.Turn, kind(ManeuverType.END_OF_ROAD))
        assertEquals(ManeuverKind.Turn, kind(null))
    }

    @Test fun `every maneuver type and modifier has a mapping`() {
        // Exhaustive `when`s already guarantee this at compile time; this guards against a
        // Ferrostar update adding an entry that falls through to a wrong default somewhere.
        ManeuverType.entries.forEach { content("", it).toInstruction() }
        ManeuverModifier.entries.forEach { content("", modifier = it).toInstruction() }
    }

    @Test fun `modifiers map to our directions`() {
        fun dir(m: ManeuverModifier?) = content("", modifier = m).toInstruction().direction
        assertEquals(TurnDirection.UTurn, dir(ManeuverModifier.U_TURN))
        assertEquals(TurnDirection.SharpRight, dir(ManeuverModifier.SHARP_RIGHT))
        assertEquals(TurnDirection.Right, dir(ManeuverModifier.RIGHT))
        assertEquals(TurnDirection.SlightRight, dir(ManeuverModifier.SLIGHT_RIGHT))
        assertEquals(TurnDirection.Straight, dir(ManeuverModifier.STRAIGHT))
        assertEquals(TurnDirection.SlightLeft, dir(ManeuverModifier.SLIGHT_LEFT))
        assertEquals(TurnDirection.Left, dir(ManeuverModifier.LEFT))
        assertEquals(TurnDirection.SharpLeft, dir(ManeuverModifier.SHARP_LEFT))
        assertNull(dir(null))
    }

    @Test fun `text and roundabout exit come across`() {
        val i = content("Jalan Solo", ManeuverType.ROUNDABOUT, ManeuverModifier.RIGHT, exitDegrees = 270u).toInstruction()
        assertEquals("Jalan Solo", i.text)
        assertEquals(270, i.roundaboutExitDegrees)
        assertNull(content("x").toInstruction().roundaboutExitDegrees)
    }
}
