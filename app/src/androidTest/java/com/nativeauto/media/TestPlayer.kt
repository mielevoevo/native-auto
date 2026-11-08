package com.nativeauto.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream

/**
 * A stand-in for Spotify: a real MediaSession, published from the test process, so the whole
 * path (listener access, session discovery, metadata, transport controls) runs as it would
 * with a real player. Create and close it on the main thread.
 */
class TestPlayer(context: Context) : AutoCloseable {

    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long, val color: Int)

    val tracks = listOf(
        Track("Test Track One", "Artist A", "Album A", 180_000, Color.RED),
        Track("Test Track Two", "Artist B", "Album B", 200_000, Color.BLUE),
        Track("Test Track Three", "Artist C", "Album C", 220_000, Color.GREEN),
    )

    @Volatile var index = 0
        private set

    @Volatile var playing = true
        private set

    private val session = MediaSession(context, "native-auto-test-player")

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() { playing = true; publish() }
                override fun onPause() { playing = false; publish() }
                override fun onSkipToNext() { index = (index + 1) % tracks.size; publish() }
                override fun onSkipToPrevious() { index = (index - 1 + tracks.size) % tracks.size; publish() }
            },
            Handler(Looper.getMainLooper()),
        )
        publish()
        session.isActive = true
    }

    val current: Track get() = tracks[index]

    private fun publish() {
        val t = current
        val art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(t.color) }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build(),
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    10_000L,
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build(),
        )
    }

    override fun close() = session.release()
}

/** Notification access is a system setting; tests flip it through the shell. */
object NotificationAccess {
    private val component: String
        get() = ComponentName(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MediaListenerService::class.java,
        ).flattenToString()

    fun grant() = shell("cmd notification allow_listener $component")
    fun revoke() = shell("cmd notification disallow_listener $component")

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).readBytes() // wait for it to finish
        }
    }
}
