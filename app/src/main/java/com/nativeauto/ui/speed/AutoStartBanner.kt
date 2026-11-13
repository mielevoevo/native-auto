package com.nativeauto.ui.speed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.location.AutoStartMonitor
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 13b: "Trip started / Riding detected" for a few seconds after auto-start began a trip. The timer
 * lives here, not in composition (the v2 test dispatcher would never go idle on a delay loop).
 */
@HiltViewModel
class AutoStartBannerViewModel @Inject constructor(monitor: AutoStartMonitor) : ViewModel() {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    private var hide: Job? = null

    init {
        viewModelScope.launch { monitor.announcements.collect { show() } }
    }

    internal fun show() {
        hide?.cancel()
        _visible.value = true
        hide = viewModelScope.launch {
            delay(BANNER_MS)
            _visible.value = false
        }
    }

    fun dismiss() {
        hide?.cancel()
        _visible.value = false
    }

    companion object {
        const val BANNER_MS = 3_000L
    }
}

/** Swipe distance that dismisses the banner, in pixels. */
private const val SWIPE_PX = 60f

/**
 * Over the speed face of the speed card: slides in from the right like the log face does, with a
 * short buzz; a swipe either way (or the 3 s) takes it away. Pass the card's own [visible] state.
 */
@Composable
internal fun AutoStartBanner(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    LaunchedEffect(visible) { if (visible) haptics.tap() }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(BANNER_SLIDE_MS)) { it } + fadeIn(tween(BANNER_SLIDE_MS)),
        exit = slideOutHorizontally(tween(BANNER_SLIDE_MS)) { it } + fadeOut(tween(BANNER_SLIDE_MS)),
        modifier = modifier.fillMaxSize(),
    ) {
        val dragged = remember { floatArrayOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.colors.card)
                .draggable(
                    state = rememberDraggableState { dragged[0] += it },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragged[0] = 0f },
                    onDragStopped = { if (abs(dragged[0]) > SWIPE_PX) onDismiss() },
                )
                .semantics { contentDescription = "Trip started" }
                .testTag("auto_start_banner"),
            contentAlignment = Alignment.Center,
        ) {
            BannerText()
        }
    }
}

@Composable
private fun BannerText() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Trip started", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AppTheme.colors.text, maxLines = 1, softWrap = false)
        Text("Riding detected", style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.textDim, maxLines = 1, softWrap = false)
    }
}

/** With no speed card on screen: the same message as a pill at the top centre, like the link status. */
@Composable
fun BoxScope.AutoStartPill(visible: Boolean, onDismiss: () -> Unit) {
    val haptics = rememberHaptics()
    LaunchedEffect(visible) { if (visible) haptics.tap() }
    val dragged = remember { floatArrayOf(0f) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
    ) {
        Box(
            Modifier
                .background(AppTheme.colors.surface, RoundedCornerShape(24.dp))
                .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(24.dp))
                .draggable(
                    state = rememberDraggableState { dragged[0] += it },
                    orientation = Orientation.Horizontal,
                    onDragStarted = { dragged[0] = 0f },
                    onDragStopped = { if (abs(dragged[0]) > SWIPE_PX) onDismiss() },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("auto_start_pill"),
        ) {
            BannerText()
        }
    }
}

/** Collects the banner state for the dashboard (pill) and the speed card (overlay): one shared ViewModel. */
@Composable
internal fun rememberAutoStartBanner(viewModel: AutoStartBannerViewModel = hiltViewModel()): Pair<Boolean, () -> Unit> {
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    return visible to viewModel::dismiss
}

private const val BANNER_SLIDE_MS = 300
