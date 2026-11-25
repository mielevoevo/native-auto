package com.nativeauto.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nativeauto.media.NowPlaying
import com.nativeauto.nav.GeoPoint
import com.nativeauto.nav.Instruction
import com.nativeauto.nav.ManeuverKind
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Place
import com.nativeauto.nav.TurnDirection
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.TripState
import com.nativeauto.ui.components.LocalPortrait
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.media.MediaCardContent
import com.nativeauto.ui.nav.TurnFace
import com.nativeauto.ui.speed.SpeedCardContent
import com.nativeauto.ui.speed.SpeedometerUiState
import com.nativeauto.ui.theme.NativeAutoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * 14b: the portrait card forms on the stateless cards, each drawn at the size its slot gives it on a 400 x 860 dp
 * phone (a row of three 400 x 285, a half-width card 200 x 385, a four-stack card 400 x 215). Alignment is measured:
 * one centre axis for the Navigation card, one left edge for the speed card's figures, units always visible.
 */
class PortraitCardsTest {
    @get:Rule val rule = createComposeRule()

    private enum class Slot(val mode: CardMode, val w: Dp, val h: Dp) {
        MINI(CardMode.Mini, 400.dp, 215.dp),
        NARROW(CardMode.Narrow, 200.dp, 385.dp),
        ROW(CardMode.Wide, 400.dp, 285.dp),
        TWO(CardMode.Expanded, 400.dp, 430.dp),
        FULL(CardMode.Fullscreen, 400.dp, 860.dp),
    }

    // setContent may be called once per test: later calls swap what the one host shows.
    private var shownSlot by androidx.compose.runtime.mutableStateOf(Slot.MINI)
    private var shownContent by androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
    private var shownVersion by androidx.compose.runtime.mutableIntStateOf(0)
    private var started = false
    private var shownPortrait by androidx.compose.runtime.mutableStateOf(true)

    private fun host(slot: Slot, content: @androidx.compose.runtime.Composable () -> Unit) {
        shownSlot = slot
        shownContent = content
        shownVersion++
        if (!started) {
            started = true
            rule.setContent {
                NativeAutoTheme {
                    CompositionLocalProvider(LocalPortrait provides shownPortrait) {
                        androidx.compose.runtime.key(shownVersion) {
                            Box(Modifier.size(shownSlot.w, shownSlot.h)) { shownContent() }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun SemanticsNodeInteraction.cx(): Float = getBoundsInRoot().let { (it.left.value + it.right.value) / 2f }
    private fun SemanticsNodeInteraction.left(): Float = getBoundsInRoot().left.value
    private fun SemanticsNodeInteraction.right(): Float = getBoundsInRoot().right.value
    private fun assertNear(msg: String, expected: Float, actual: Float, tol: Float = 1.5f) =
        assertTrue("$msg: expected $expected, was $actual", abs(expected - actual) <= tol)

    private val driving = SpeedometerUiState.Empty.copy(
        speed = 88, maxSpeed = 112, avgSpeed = 47, movingAvgSpeed = 55, distance = 12.4, elapsed = 874.seconds,
        gps = GpsStatus.Fix, tripState = TripState.Running,
    )

    // ---- speed: unit always visible, value and figures aligned ----

    @Test fun theUnitIsVisibleInEverySlotAndState() {
        for (slot in Slot.entries) for ((name, ui) in listOf("driving" to driving, "idle" to SpeedometerUiState.Empty, "paused" to driving.copy(tripState = TripState.Paused))) {
            host(slot) { SpeedCardContent(slot.mode, DashboardLayoutState(portrait = true), ui) }
            val card = tag("pane_SPEED").getBoundsInRoot()
            val unit = rule.onAllNodesWithText("km/h", useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("$slot/$name: no unit on the card", unit.isNotEmpty())
            // ...and inside the card, not clipped away at its edge.
            val hero = tag("speed_hero").getBoundsInRoot()
            assertTrue("$slot/$name: hero $hero sticks out of the card $card", hero.left >= card.left && hero.right <= card.right)
        }
    }

    @Test fun beside_theValueIsCentredInItsColumnAndTheFiguresShareALeftEdge() {
        for (slot in listOf(Slot.MINI, Slot.ROW)) {
            host(slot) { SpeedCardContent(slot.mode, DashboardLayoutState(portrait = true), driving) }
            val figures = listOf("inline_stat_max", "inline_stat_avg") + if (slot == Slot.ROW) listOf("inline_stat_distance", "inline_stat_elapsed") else emptyList()
            val lefts = figures.map { tag(it).left() }
            lefts.forEach { assertNear("$slot figures share one left edge", lefts.first(), it, 0.5f) }
            // The value sits left of the figures, centred in the column that ends where the figures begin.
            val hero = tag("speed_hero")
            assertTrue("$slot: value left of the figures", hero.right() <= lefts.first())
        }
    }

    @Test fun below_theValueIsCentredAndTheFiguresShareALeftEdge() {
        host(Slot.NARROW) { SpeedCardContent(Slot.NARROW.mode, DashboardLayoutState(portrait = true), driving) }
        val card = tag("pane_SPEED").getBoundsInRoot()
        assertNear("value centred on the card", (card.left.value + card.right.value) / 2f, tag("speed_hero").cx(), 2f)
        val lefts = listOf("inline_stat_max", "inline_stat_avg", "inline_stat_distance", "inline_stat_elapsed").map { tag(it).left() }
        lefts.forEach { assertNear("figures share one left edge", lefts.first(), it, 0.5f) }
    }

    // ---- log face: the last line is never cut ----

    /** 4.7.3: every portrait shape of the log face on a 393 x 873 dp phone: the period total is whole, inside the card and clear of the dots. */
    @Test fun theLogFacesLastLineIsWholeInEveryPortraitShape() {
        val shapes = listOf(
            // name, mode, width, height, beside a maximized card
            LogShape("slim row", CardMode.Mini, 384.dp, 136.dp, slim = true),
            LogShape("slim row on top", CardMode.Mini, 384.dp, 136.dp, slim = true, top = true),
            LogShape("four-stack", CardMode.Mini, 384.dp, 209.dp),
            LogShape("four-stack on top", CardMode.Mini, 384.dp, 209.dp, top = true),
            LogShape("half width", CardMode.Narrow, 188.dp, 383.dp),
            LogShape("three rows", CardMode.Wide, 384.dp, 281.dp),
            LogShape("maximized of four", CardMode.Expanded, 384.dp, 427.dp),
            LogShape("two stacked", CardMode.Expanded, 384.dp, 427.dp),
            LogShape("big of three", CardMode.Expanded, 384.dp, 471.dp),
        )
        val item = com.nativeauto.garage.ServiceItem(bikeId = "b", name = "Engine oil", kind = "DISTANCE", lastDoneMs = 0, createdAtMs = 0)
        val due = com.nativeauto.garage.ServiceDue(item, 80.0, null, com.nativeauto.garage.DueLevel.SOON, 0.05)
        val all = com.nativeauto.settings.TelemetryStat.entries.toSet()
        val logs = listOf(
            "plain" to com.nativeauto.ui.speed.TripLogUi(periodM = 123_400.0),
            "service" to com.nativeauto.ui.speed.TripLogUi(periodM = 123_400.0, reminder = due),
            "all figures" to com.nativeauto.ui.speed.TripLogUi(periodM = 123_400.0, reminder = due, visible = all),
        )
        val wrong = mutableListOf<String>()
        for (s in shapes) for ((state, log) in logs) for (scale in listOf(1f, 1.15f)) {
            val layout = DashboardLayoutState(com.nativeauto.ui.dashboard.LayoutPreset.STACK4, portrait = true)
            // On top the header holds the battery and the status buttons, which makes it taller.
            if (layout.view.panes.first() != com.nativeauto.ui.dashboard.Pane.SPEED && s.top) layout.assign(layout.view.panes.first(), com.nativeauto.ui.dashboard.Pane.SPEED)
            if (s.slim) layout.toggleMaximized(layout.view.panes.first { it != com.nativeauto.ui.dashboard.Pane.SPEED })
            assertEquals(s.slim, layout.maximized != null)
            host(Slot.ROW) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, scale),
                    com.nativeauto.ui.components.LocalStatusCorner provides com.nativeauto.ui.components.StatusCorner(
                        "12:45", com.nativeauto.device.BatteryStatus(91, false), onOpenLayouts = {}, onOpenSettings = {},
                    )) {
                    Box(Modifier.requiredSize(s.w, s.h)) {
                        SpeedCardContent(s.mode, layout, driving, log, face = com.nativeauto.ui.speed.SpeedFace.LOG)
                    }
                }
            }
            // Unclipped: what is cut off counts too.
            fun box(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().let { n ->
                val k = n.layoutInfo.density.density
                (n.positionInRoot.y / k) to ((n.positionInRoot.y + n.size.height) / k)
            }
            val faces = box("speed_faces")
            val dots = box("face_dots")
            val total = box(if (log.reminder != null && s.mode != CardMode.Expanded) "service_reminder" else "log_period")
            val start = box("log_start")
            android.util.Log.i("LogFace", "${s.name} $state x$scale: faces $faces start $start total $total dots $dots")
            if (total.second > dots.first + 0.5f || start.first < faces.first - 0.5f) {
                wrong += "${s.name} $state x$scale: face $faces, controls from ${start.first}, total to ${total.second}, dots at ${dots.first}"
            }
        }
        assertTrue(wrong.joinToString("; "), wrong.isEmpty())
    }

    private class LogShape(val name: String, val mode: CardMode, val w: androidx.compose.ui.unit.Dp, val h: androidx.compose.ui.unit.Dp, val slim: Boolean = false, val top: Boolean = false)

    // ---- Navigation: one centre axis ----

    private fun navigating(road: String, distanceM: Double = 350.0, durationS: Double = 9_000.0, remainingM: Double = 354_000.0) = NavState.Navigating(
        destination = Place("Home", null, GeoPoint(0.0, 0.0)),
        geometry = emptyList(),
        instruction = Instruction(road, ManeuverKind.Turn, TurnDirection.Right),
        nextInstruction = null,
        distanceToManeuverM = distanceM,
        distanceRemainingM = remainingM,
        durationRemainingS = durationS,
        position = null,
        offRoute = false,
        rerouting = false,
    )

    @Test fun navigationInfoSitsOnOneCentreAxisInEveryPortraitForm() {
        val roads = listOf("Turn right onto Jl. Malioboro", "Turn right onto Jalan Raya Yogyakarta - Solo - Semarang Km 14")
        for (slot in listOf(Slot.MINI, Slot.NARROW, Slot.ROW)) for (road in roads) for (m in listOf(350.0, 12_400.0)) {
            host(slot) { TurnFace(navigating(road, distanceM = m), SpeedUnit.KMH, slot.mode, portrait = true, onEnd = {}) }
            val axis = slot.w.value / 2f
            for (t in listOf("next_turn_glyph", "next_turn_road", "next_turn_distance", "next_turn_eta")) {
                assertNear("$slot '$road' $m m: $t on the axis", axis, tag(t).cx(), 1.5f)
                // never cut: inside the card's width
                assertTrue("$slot: $t sticks out", tag(t).left() >= -0.5f && tag(t).right() <= slot.w.value + 0.5f)
            }
        }
    }

    /** 4.6.4: the whole stack is inside the content of a four-stack card (161 dp), a two-line road included. */
    @Test fun theTurnStackFitsAFourStackCard() {
        host(Slot.MINI) {
            // Room to spare, so nothing is squeezed: the stack's own height is what is measured.
            Box(Modifier.size(390.dp, 210.dp)) {
                TurnFace(navigating("Turn right onto Jalan Raya Yogyakarta - Solo - Semarang Km 14"), SpeedUnit.KMH, CardMode.Mini, portrait = true, onEnd = {})
            }
        }
        val heights = listOf("next_turn_glyph", "next_turn_road", "next_turn_distance_line", "next_turn_eta").associateWith { t ->
            tag(t).fetchSemanticsNode().let { it.size.height / it.layoutInfo.density.density }
        }
        val total = heights.values.sum() + 4f // the gaps
        assertTrue("$total dp: $heights", total <= 161.5f)
    }

    // ---- media: swipe changes track, play / pause stays ----

    private fun track() = NowPlaying("Song", "Artist", "Album", null, true, 30_000, 200_000, "pkg")

    @Test fun aSwipeChangesTrackAndTheSmallCardsHaveNoSkipButtons() {
        for (slot in listOf(Slot.MINI, Slot.NARROW, Slot.ROW)) {
            var next = 0
            var previous = 0
            host(slot) {
                MediaCardContent(slot.mode, DashboardLayoutState(portrait = true), track(), onPlayPause = {}, onNext = { next++ }, onPrevious = { previous++ })
            }
            assertEquals("$slot: no skip buttons", 0, rule.onAllNodesWithTag("media_next", useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(1, rule.onAllNodesWithTag("media_play_pause", useUnmergedTree = true).fetchSemanticsNodes().size)
            tag("media_swipe").performTouchInput { swipeLeft() }
            rule.waitForIdle()
            assertEquals("$slot: swipe left = next", 1, next)
            assertEquals(0, previous)
            tag("media_swipe").performTouchInput { swipeRight() }
            rule.waitForIdle()
            assertEquals("$slot: swipe right = previous", 1, previous)
        }
    }

    /** 4.2.1: the landscape cards take the same swipe and keep their skip buttons. */
    @Test fun landscapeMediaCardsChangeTrackBySwipeAndKeepSkips() {
        shownPortrait = false
        for (mode in listOf(CardMode.Compact, CardMode.Expanded, CardMode.Fullscreen)) {
            var next = 0
            var previous = 0
            host(Slot.ROW) {
                MediaCardContent(mode, DashboardLayoutState(), track(), onPlayPause = {}, onNext = { next++ }, onPrevious = { previous++ })
            }
            assertEquals("$mode: skip buttons stay", 1, rule.onAllNodesWithTag("media_next", useUnmergedTree = true).fetchSemanticsNodes().size)
            tag("media_swipe").performTouchInput { swipeLeft() }
            rule.waitForIdle()
            assertEquals("$mode: swipe left = next", 1, next)
            tag("media_swipe").performTouchInput { swipeRight() }
            rule.waitForIdle()
            assertEquals("$mode: swipe right = previous", 1, previous)
        }
    }

    @Test fun aShortDragIsNotASwipe() {
        var next = 0
        host(Slot.MINI) {
            MediaCardContent(Slot.MINI.mode, DashboardLayoutState(portrait = true), track(), onPlayPause = {}, onNext = { next++ }, onPrevious = {})
        }
        tag("media_swipe").performTouchInput { swipe(Offset(centerX, centerY), Offset(centerX - 30f, centerY), 200) }
        rule.waitForIdle()
        assertEquals(0, next)
    }

    @Test fun withNothingPlayingASwipeDoesNothing() {
        var next = 0
        host(Slot.ROW) {
            MediaCardContent(Slot.ROW.mode, DashboardLayoutState(portrait = true), track(), onPlayPause = {}, onNext = { next++ }, onPrevious = {}, showSkips = false)
        }
        tag("media_swipe").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(0, next)
    }
}
