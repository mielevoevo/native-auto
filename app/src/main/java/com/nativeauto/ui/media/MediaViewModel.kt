package com.nativeauto.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.core.MonotonicClock
import com.nativeauto.media.MediaSessionRepository
import com.nativeauto.media.NowPlaying
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** What the media card shows. */
sealed interface MediaUiState {
    /** Notification access not granted: the card asks for it (not an error, §7). */
    data object NoAccess : MediaUiState

    /** Access, but no session to follow: Play resumes the last player via a media key. */
    data object Idle : MediaUiState

    data class Active(val nowPlaying: NowPlaying) : MediaUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaViewModel @Inject constructor(
    private val repository: MediaSessionRepository,
    private val clock: MonotonicClock,
) : ViewModel() {

    val state: StateFlow<MediaUiState> = combine(repository.access, repository.nowPlaying, ::toUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            toUiState(repository.access.value, repository.nowPlaying.value),
        )

    /**
     * Live playback position (§7), re-computed 4x a second while the track advances and
     * frozen otherwise. Kept apart from [state] so a tick only recomposes the progress bar.
     */
    val positionMs: StateFlow<Long> = repository.nowPlaying
        .flatMapLatest { np -> if (np == null) flowOf(0L) else livePosition(np) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.nowPlaying.value?.positionMs ?: 0L)

    private fun livePosition(np: NowPlaying) = flow {
        do {
            emit(np.positionAt(clock.nowNanos() / 1_000_000))
            if (np.playbackSpeed != 0f) delay(POSITION_TICK_MS)
        } while (np.playbackSpeed != 0f)
    }

    fun refreshAccess() = repository.refreshAccess()

    fun playPause() {
        if (repository.nowPlaying.value?.isPlaying == true) repository.pause() else repository.play()
    }

    fun next() = repository.next()
    fun previous() = repository.previous()
}

private const val POSITION_TICK_MS = 250L

internal fun toUiState(access: Boolean, nowPlaying: NowPlaying?): MediaUiState = when {
    !access -> MediaUiState.NoAccess
    nowPlaying == null -> MediaUiState.Idle
    else -> MediaUiState.Active(nowPlaying)
}
