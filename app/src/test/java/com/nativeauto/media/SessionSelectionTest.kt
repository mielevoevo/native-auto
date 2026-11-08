package com.nativeauto.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSelectionTest {

    private fun s(pkg: String, playing: Boolean) = SessionCandidate(pkg, playing)
    private val yt = "com.google.android.apps.youtube.music"
    private val podcast = "au.com.shiftyjelly.pocketcasts"

    @Test fun noSessionsPicksNothing() = assertEquals(-1, pickSession(emptyList()))

    @Test fun playingSpotifyWinsOverOtherPlayingApps() =
        assertEquals(1, pickSession(listOf(s(yt, true), s(SPOTIFY_PACKAGE, true))))

    @Test fun anotherAppPlayingWinsOverPausedSpotify() =
        assertEquals(1, pickSession(listOf(s(SPOTIFY_PACKAGE, false), s(yt, true))))

    @Test fun pausedSpotifyWinsWhenNothingPlays() =
        assertEquals(1, pickSession(listOf(s(yt, false), s(SPOTIFY_PACKAGE, false))))

    @Test fun otherwiseTheMostRecentSession() =
        assertEquals(0, pickSession(listOf(s(podcast, false), s(yt, false))))

    @Test fun firstPlayingAmongNonSpotify() =
        assertEquals(2, pickSession(listOf(s(podcast, false), s(yt, false), s(yt, true), s(podcast, true))))

    @Test fun bufferingCountsAsPlayingButDoesNotAdvance() {
        assertTrue(isPlayingState(PlaybackState.STATE_BUFFERING))
        assertEquals(0f, advancingSpeed(PlaybackState.STATE_BUFFERING, 1f))
        assertEquals(1f, advancingSpeed(PlaybackState.STATE_PLAYING, 1f))
    }

    @Test fun pausedStoppedAndUnknownAreNotPlaying() {
        listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR, null)
            .forEach { assertFalse("state $it", isPlayingState(it)) }
    }
}
