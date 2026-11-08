package com.nativeauto.ui.media

import app.cash.turbine.test
import com.nativeauto.TestClock
import com.nativeauto.media.MediaSessionRepository
import com.nativeauto.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewModelTest {

    private class FakeRepository : MediaSessionRepository {
        override val access = MutableStateFlow(true)
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val calls = mutableListOf<String>()
        override fun refreshAccess() { calls += "refresh" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
    }

    private val main = StandardTestDispatcher()
    private val repo = FakeRepository()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun track(playing: Boolean, position: Long = 30_000, at: Long = 0) = NowPlaying(
        title = "Song", artist = "Artist", album = "Album", art = null, isPlaying = playing,
        positionMs = position, durationMs = 180_000, packageName = "com.spotify.music",
        positionAtMs = at, playbackSpeed = if (playing) 1f else 0f,
    )

    @Test fun uiStateFollowsAccessAndSession() {
        assertEquals(MediaUiState.NoAccess, toUiState(access = false, nowPlaying = track(true)))
        assertEquals(MediaUiState.Idle, toUiState(access = true, nowPlaying = null))
        assertEquals(MediaUiState.Active(track(true)), toUiState(access = true, nowPlaying = track(true)))
    }

    @Test fun playPauseSendsTheOppositeOfTheCurrentState() = runTest(main) {
        val vm = MediaViewModel(repo, TestClock(testScheduler))
        vm.playPause() // nothing playing: play (the repository falls back to a media key)
        repo.nowPlaying.value = track(playing = true)
        vm.playPause()
        repo.nowPlaying.value = track(playing = false)
        vm.playPause()
        vm.next(); vm.previous()
        assertEquals(listOf("play", "pause", "play", "next", "previous"), repo.calls)
    }

    @Test fun positionTicksWhilePlayingAndFreezesWhenPaused() = runTest(main) {
        advanceTimeBy(1_000); runCurrent() // virtual clock at 1 s
        val vm = MediaViewModel(repo, TestClock(testScheduler))
        vm.positionMs.test {
            assertEquals(0L, awaitItem())
            repo.nowPlaying.value = track(playing = true, position = 30_000, at = 1_000)
            assertEquals(30_000L, awaitItem())
            advanceTimeBy(1_000)
            // Ticks at 250 ms steps: 30.25, 30.5, 30.75, 31.0 s.
            assertEquals(listOf(30_250L, 30_500L, 30_750L, 31_000L), List(4) { awaitItem() })

            repo.nowPlaying.value = track(playing = false, position = 31_000)
            expectNoEvents() // same value as the last tick: StateFlow drops it
            advanceTimeBy(10_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
