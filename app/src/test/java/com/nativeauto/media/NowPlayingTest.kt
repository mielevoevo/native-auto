package com.nativeauto.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {

    private fun np(position: Long, at: Long, speed: Float, duration: Long = 200_000) = NowPlaying(
        title = "t", artist = "a", album = "b", art = null, isPlaying = speed != 0f,
        positionMs = position, durationMs = duration, packageName = "p",
        positionAtMs = at, playbackSpeed = speed,
    )

    @Test fun advancesFromTheSnapshotAtPlaybackSpeed() {
        assertEquals(13_000, np(10_000, at = 1_000, speed = 1f).positionAt(4_000))
        assertEquals(16_000, np(10_000, at = 1_000, speed = 2f).positionAt(4_000))
    }

    @Test fun pausedStaysPut() = assertEquals(10_000, np(10_000, at = 1_000, speed = 0f).positionAt(60_000))

    @Test fun clampsToTheTrack() {
        assertEquals(200_000, np(199_000, at = 0 + 1, speed = 1f).positionAt(10_000))
        assertEquals(0, np(1_000, at = 5_000, speed = -1f).positionAt(10_000))
    }

    @Test fun unknownDurationOnlyClampsAtZero() =
        assertEquals(1_009_000, np(1_000_000, at = 1_000, speed = 1f, duration = 0).positionAt(10_000))
}
