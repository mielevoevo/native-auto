package com.nativeauto.ui.media

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.media.NotificationAccess
import com.nativeauto.media.TestPlayer
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.Pane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 6 DoD, against a real media session ([TestPlayer]) standing in for Spotify. */
@RunWith(AndroidJUnit4::class)
class MediaCardTest {

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()

    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var player: TestPlayer

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(t: String) { tag(t).performClick(); rule.waitForIdle() }
    private fun text(t: String) = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun playPauseLabel() =
        tag("media_play_pause").onChildAt(0).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun awaitTitle(title: String) =
        rule.waitUntil(timeoutMillis = 10_000) { count("media_title") == 1 && text("media_title") == title }
    private fun assertMode(mode: CardMode) = rule.onNodeWithTag("pane_${Pane.MEDIA.name}")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, mode.name))

    @Before fun setUp() {
        NotificationAccess.grant()
        onMain { player = TestPlayer(InstrumentationRegistry.getInstrumentation().targetContext) }
        awaitTitle(player.current.title)
    }

    @After fun tearDown() {
        onMain { player.close() }
        NotificationAccess.grant() // leave it granted, as a test that revokes might not finish
    }

    @Test fun showsTheTrackWithArtAndControlsAtEverySize() {
        fun assertBasics() {
            listOf("media_art", "media_title", "media_previous", "media_play_pause", "media_next")
                .forEach { tag(it).assertIsDisplayed() }
            // 4.3b: the skips are plated buttons with a visible touch area of at least 52 dp, like play / pause.
            listOf("media_previous", "media_next").forEach { t ->
                val b = tag(t).getBoundsInRoot()
                assertTrue("$t plate", b.right - b.left >= 52.dp)
            }
            rule.onNodeWithContentDescription("Album art", useUnmergedTree = true).assertIsDisplayed()
        }
        assertMode(CardMode.Compact); assertBasics()
        click("maximize_MEDIA")
        assertMode(CardMode.Expanded); assertBasics()
        click("fullscreen_MEDIA")
        assertMode(CardMode.Fullscreen); assertBasics()
    }

    @Test fun controlsDriveThePlayerAndTheCardFollowsTrackChanges() {
        assertEquals("Pause", playPauseLabel())
        click("media_play_pause")
        rule.waitUntil(5_000) { !player.playing && playPauseLabel() == "Play" }
        click("media_play_pause")
        rule.waitUntil(5_000) { player.playing && playPauseLabel() == "Pause" }

        click("media_next")
        awaitTitle("Test Track Two")
        assertEquals(1, player.index)
        click("media_previous")
        awaitTitle("Test Track One")

        // Control taps must not bubble up into the card's tap-to-maximize gesture.
        assertMode(CardMode.Compact)
    }

    @Test fun positionAdvancesWhilePlayingOnly() {
        click("maximize_MEDIA") // times are shown from Expanded up
        fun seconds() = text("media_position").split(":").let { (m, s) -> m.toInt() * 60 + s.toInt() }
        rule.waitUntil(5_000) { seconds() >= 11 } // the player starts at 0:10
        click("media_play_pause")
        rule.waitUntil(5_000) { playPauseLabel() == "Play" }
        val paused = seconds()
        Thread.sleep(2_000)
        rule.waitForIdle()
        assertEquals("frozen while paused", paused, seconds())
    }

    @Test fun revokingAccessShowsTheCallToActionAndGrantingRestoresTheCard() {
        NotificationAccess.revoke()
        rule.waitUntil(10_000) { count("media_grant_access") == 1 }
        assertFalse(count("media_title") > 0)
        tag("media_grant_access").assertIsDisplayed()

        NotificationAccess.grant()
        awaitTitle(player.current.title)
        assertTrue(count("media_grant_access") == 0)
        // And it stays: late callbacks around an access change once dropped the card back to
        // "Nothing playing" a moment after the track appeared.
        Thread.sleep(3_000)
        rule.waitForIdle()
        assertEquals(player.current.title, text("media_title"))
    }
}
