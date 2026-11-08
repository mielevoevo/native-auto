package com.nativeauto.media

import android.content.ComponentName
import android.app.NotificationManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import com.nativeauto.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's current media session (§7): what's playing, and its transport controls. */
interface MediaSessionRepository {
    /** Notification access granted. It's a system setting, not a runtime permission. */
    val access: StateFlow<Boolean>

    /** The session the card follows, or null when nothing is (or can be) seen. */
    val nowPlaying: StateFlow<NowPlaying?>

    /** Re-reads the access setting (e.g. back from system settings) and the active sessions. */
    fun refreshAccess()

    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
}

/**
 * Follows every active session, so a different app starting playback switches the card over
 * ([pickSession]). All MediaSession calls and callbacks run on the main thread.
 */
@Singleton
class AndroidMediaSessionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MediaSessionRepository {

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)

    private val _access = MutableStateFlow(hasAccess())
    override val access: StateFlow<Boolean> = _access.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var listening = false
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var art: CachedArt? = null

    // Re-query instead of trusting the list passed in: around an access change the system
    // sends empty lists, and the last one can arrive after the real sessions (seen on API 37),
    // which would leave the card on "Nothing playing" while music plays.
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { readSessions() }

    /** Fires once the access setting is actually written (it lags the listener unbinding). */
    private val accessSetting = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshAccess()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reselect()
        override fun onMetadataChanged(metadata: MediaMetadata?) = reselect()
        override fun onSessionDestroyed() = readSessions()
    }

    init {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(ENABLED_LISTENERS_SETTING),
            false,
            accessSetting,
        )
        refreshAccess()
    }

    override fun refreshAccess() {
        if (Looper.myLooper() != main.looper) {
            main.post(::refreshAccess)
            return
        }
        val granted = hasAccess()
        if (BuildConfig.DEBUG) Log.d(TAG, "refreshAccess: granted=$granted listening=$listening")
        _access.value = granted
        when {
            granted && !listening -> try {
                manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
                listening = true
                readSessions()
            } catch (_: SecurityException) {
                lostAccess() // revoked between the check and the call
            }
            granted -> readSessions()
            listening -> lostAccess()
        }
    }

    override fun play() = transport(KeyEvent.KEYCODE_MEDIA_PLAY) { play() }
    override fun pause() = transport(KeyEvent.KEYCODE_MEDIA_PAUSE) { pause() }
    override fun next() = transport(KeyEvent.KEYCODE_MEDIA_NEXT) { skipToNext() }
    override fun previous() = transport(KeyEvent.KEYCODE_MEDIA_PREVIOUS) { skipToPrevious() }
    override fun seekTo(positionMs: Long) {
        current?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Sends to the followed session. With none (nothing played since boot, or no access), a
     * media key goes to whichever app played last, so one tap on Play resumes the music.
     */
    private inline fun transport(keyCode: Int, action: MediaController.TransportControls.() -> Unit) {
        val controls = current?.transportControls
        if (controls != null) {
            controls.action()
        } else {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /**
     * Asks the notification service itself where it can (8.1+): the settings string it's
     * stored in is written a moment after a revoke unbinds the listener.
     */
    private fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        notifications.isNotificationListenerAccessGranted(listener)
    } else {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    private fun readSessions() {
        try {
            follow(manager.getActiveSessions(listener))
        } catch (_: SecurityException) {
            lostAccess()
        }
    }

    private fun lostAccess() {
        _access.value = false
        if (listening) manager.removeOnActiveSessionsChangedListener(sessionsChanged)
        listening = false
        follow(emptyList())
    }

    private fun follow(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = sessions
        sessions.forEach { it.registerCallback(controllerCallback, main) }
        reselect()
    }

    private fun reselect() {
        val index = pickSession(controllers.map { SessionCandidate(it.packageName, isPlayingState(it.playbackState?.state)) })
        current = controllers.getOrNull(index)
        _nowPlaying.value = current?.toNowPlaying()
        if (BuildConfig.DEBUG) Log.d(TAG, "sessions=${controllers.map { it.packageName }} following=${_nowPlaying.value?.title}")
    }

    private fun MediaController.toNowPlaying(): NowPlaying? {
        val meta = metadata
        val state = playbackState
        val description = meta?.description
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: description?.title?.toString()
        if (title == null && state == null) return null // a session that hasn't published anything yet
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: description?.subtitle?.toString()
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: description?.description?.toString()
        val stateCode = state?.state
        return NowPlaying(
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            album = album.orEmpty(),
            art = artFor(packageName, title, artist, album, meta),
            isPlaying = isPlayingState(stateCode),
            positionMs = (state?.position ?: 0L).coerceAtLeast(0L),
            durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
            packageName = packageName,
            positionAtMs = state?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
            playbackSpeed = advancingSpeed(stateCode, state?.playbackSpeed ?: 0f),
        )
    }

    /**
     * Each metadata read unparcels a fresh Bitmap, so the art is converted once per track
     * rather than on every play/pause or seek.
     */
    private fun artFor(pkg: String, title: String?, artist: String?, album: String?, meta: MediaMetadata?): ImageBitmap? {
        val key = listOf(pkg, title, artist, album)
        art?.takeIf { it.key == key }?.let { return it.image }
        val bitmap: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val image = bitmap?.asImageBitmap()
        // Don't cache "no art yet": some players attach it in a second metadata update.
        art = if (image != null) CachedArt(key, image) else null
        return image
    }

    private companion object {
        const val TAG = "MediaSessions"
        /** Settings.Secure.ENABLED_NOTIFICATION_LISTENERS is hidden API; the key is stable. */
        const val ENABLED_LISTENERS_SETTING = "enabled_notification_listeners"
    }

    private class CachedArt(val key: List<String?>, val image: ImageBitmap)
}
