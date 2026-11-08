package com.nativeauto.media

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Snapshot of the active media session (§7). Positions are in milliseconds.
 *
 * [positionMs] was true at [positionAtMs] (`SystemClock.elapsedRealtime`, monotonic) and
 * advances at [playbackSpeed]; see [positionAt]. Both extra fields default so a static
 * snapshot (paused, or sample data) needs neither.
 */
@Immutable
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val packageName: String,
    val positionAtMs: Long = 0L,
    /** 0 while the position isn't advancing (paused, buffering). */
    val playbackSpeed: Float = 0f,
) {
    /** §7: `position + (now - lastPositionUpdateTime) * speed`, kept within the track. */
    fun positionAt(nowElapsedMs: Long): Long {
        val live = if (playbackSpeed == 0f || positionAtMs <= 0L) positionMs
        else positionMs + ((nowElapsedMs - positionAtMs) * playbackSpeed).toLong()
        return if (durationMs > 0) live.coerceIn(0L, durationMs) else live.coerceAtLeast(0L)
    }
}
