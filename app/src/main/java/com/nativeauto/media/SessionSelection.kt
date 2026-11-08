package com.nativeauto.media

import android.media.session.PlaybackState

/** What [pickSession] needs to know about one active media session. */
internal data class SessionCandidate(val packageName: String, val isPlaying: Boolean)

internal const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * Which active session the card follows (§7), as an index into [candidates], or -1 for none.
 * Order: Spotify playing, anything playing, Spotify paused, then the most recent session
 * (the system lists them most recent first). Preferring a paused Spotify over another app
 * that's playing would show the wrong track, so playing wins.
 */
internal fun pickSession(candidates: List<SessionCandidate>): Int {
    val rules = listOf<(SessionCandidate) -> Boolean>(
        { it.packageName == SPOTIFY_PACKAGE && it.isPlaying },
        { it.isPlaying },
        { it.packageName == SPOTIFY_PACKAGE },
        { true },
    )
    for (rule in rules) {
        val i = candidates.indexOfFirst(rule)
        if (i >= 0) return i
    }
    return -1
}

/** "Playing" for the play/pause button: buffering mid-track still shows Pause. */
internal fun isPlayingState(state: Int?): Boolean = when (state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_CONNECTING,
    -> true
    else -> false
}

/** Speed the position advances at: only while actually playing (buffering stands still). */
internal fun advancingSpeed(state: Int?, reportedSpeed: Float): Float =
    if (state == PlaybackState.STATE_PLAYING) reportedSpeed else 0f
