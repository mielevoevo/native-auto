package com.nativeauto.ui.map

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Map sleep (Phase 8b): while the map is off screen (a layout without it, another pane
 * fullscreen, the history over it) it stays alive for [graceMs], so quick peeks come back
 * instantly. After that [awake] turns false and the map view is released: no GPU, no tiles,
 * no draws, and its GPS subscription lapses unless a trip or navigation needs one. Coming
 * back on screen wakes it at once, at the camera [MapCameraMemory] kept.
 *
 * The timer runs here, on the ViewModel's real clock: a delay in composition would be
 * fast-forwarded by the v2 Compose test dispatcher.
 */
internal class MapSleep(private val scope: CoroutineScope, private val graceMs: () -> Long = { defaultGraceMs }) {
    private val _awake = MutableStateFlow(true)
    val awake: StateFlow<Boolean> = _awake.asStateFlow()
    private var timer: Job? = null
    private var onScreen = true

    fun setOnScreen(visible: Boolean) {
        if (visible == onScreen) return
        onScreen = visible
        timer?.cancel()
        timer = null
        if (visible) {
            _awake.value = true
        } else {
            timer = scope.launch {
                delay(graceMs())
                _awake.value = false
            }
        }
    }

    companion object {
        const val GRACE_MS = 30_000L

        /** Device tests shorten this to watch the map sleep without waiting half a minute. */
        @VisibleForTesting
        @Volatile
        var defaultGraceMs: Long = GRACE_MS
    }
}

/** Where the camera was when the map view was released; it comes back there. Zoom in Google levels. */
internal data class SavedCamera(val latitude: Double, val longitude: Double, val zoom: Float, val bearing: Float, val tilt: Float)

/** Lives in the ViewModel, so it outlasts each map view (and configuration changes). */
internal class MapCameraMemory {
    @Volatile var saved: SavedCamera? = null
}
