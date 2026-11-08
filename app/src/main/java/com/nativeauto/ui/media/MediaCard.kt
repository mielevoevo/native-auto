package com.nativeauto.ui.media

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.media.MediaListenerService
import com.nativeauto.media.NowPlaying
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.components.cardModeTransition
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import java.util.Locale

/**
 * The phone's current media session (Spotify preferred, any player works), or a prompt for
 * notification access. Access is re-checked whenever the app comes back to the front, which
 * is how a grant made in system settings shows up.
 */
@Composable
fun MediaCard(mode: CardMode, layout: DashboardLayoutState, viewModel: MediaViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val position = viewModel.positionMs.collectAsStateWithLifecycle()
    LifecycleStartEffect(viewModel) {
        viewModel.refreshAccess()
        onStopOrDispose { }
    }
    val context = LocalContext.current

    when (val s = state) {
        MediaUiState.NoAccess -> DashboardCard(title = "Media", pane = Pane.MEDIA, mode = mode, layout = layout) {
            MediaAccessPrompt(mode, onGrant = { openNotificationAccessSettings(context) }, Modifier.weight(1f).fillMaxWidth())
        }
        else -> {
            val active = s as? MediaUiState.Active
            MediaCardContent(
                mode = mode,
                layout = layout,
                nowPlaying = active?.nowPlaying ?: idleTrack(),
                // Read inside the lambda: a tick recomposes only the progress bar and times.
                position = if (active != null) ({ position.value }) else ({ 0L }),
                showSkips = active != null,
                onPlayPause = viewModel::playPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
            )
        }
    }
}

@Composable
private fun idleTrack() = NowPlaying(
    title = stringResource(R.string.media_idle_title),
    artist = stringResource(R.string.media_idle_subtitle),
    album = "",
    art = null,
    isPlaying = false,
    positionMs = 0,
    durationMs = 0,
    packageName = "",
)

/**
 * Notification access can't be requested with a dialog (§10); it's a toggle in system
 * settings. Android 11+ can open our own toggle directly; older versions get the list.
 */
private fun openNotificationAccessSettings(context: Context) {
    val component = ComponentName(context, MediaListenerService::class.java).flattenToString()
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
    } else {
        null
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(direct, list)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next one
        }
    }
}

/** An invitation, not an error (§7). One big button: easy to hit with gloves on. */
@Composable
private fun MediaAccessPrompt(mode: CardMode, onGrant: () -> Unit, modifier: Modifier) {
    val haptics = rememberHaptics()
    val message = @Composable {
        Text(
            text = stringResource(R.string.media_access_message),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val button = @Composable {
        FilledTonalButton(
            onClick = { haptics.tap(); onGrant() },
            modifier = Modifier.heightIn(min = 56.dp).testTag("media_grant_access"),
        ) {
            Text(stringResource(R.string.media_access_action), maxLines = 1, softWrap = false)
        }
    }
    if (mode == CardMode.Compact || mode == CardMode.Wide) {
        Row(
            modifier = modifier.requiredHeightIn(min = 108.dp).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Box(Modifier.weight(1f)) { message() }
            Spacer(Modifier.width(12.dp))
            button()
        }
    } else {
        Column(
            modifier = modifier.padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(AppIcons.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            message()
            Spacer(Modifier.height(20.dp))
            button()
        }
    }
}

/**
 * Art, title and controls at every size. Compact is one horizontal strip; Expanded stacks a
 * larger header over progress + controls; Fullscreen puts big art beside the details.
 */
@Composable
fun MediaCardContent(
    mode: CardMode,
    layout: DashboardLayoutState,
    nowPlaying: NowPlaying,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    position: () -> Long = { nowPlaying.positionMs },
    showSkips: Boolean = true,
) {
    val controls = Controls(onPlayPause, onNext, onPrevious, position, showSkips)
    DashboardCard(title = "Media", pane = Pane.MEDIA, mode = mode, layout = layout) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = { cardModeTransition() },
            contentAlignment = Alignment.Center,
            label = "mediaMode",
            // Clip so content overflowing a still-small card never draws over the header.
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
        ) { m ->
            when (m) {
                // The Rows strip is the compact strip with more width: it lays out the same.
                CardMode.Compact, CardMode.Wide -> MediaCompact(nowPlaying, controls)
                CardMode.Expanded -> MediaExpanded(nowPlaying, controls)
                CardMode.Fullscreen -> MediaFullscreen(nowPlaying, controls)
            }
        }
    }
}

private class Controls(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val position: () -> Long,
    /** Off while nothing plays: Play alone, so "Nothing playing" fits the compact strip. */
    val showSkips: Boolean,
)

@Composable
private fun MediaCompact(np: NowPlaying, controls: Controls) {
    // Natural height as a floor: a collapsing card clips this strip rather than squashing it.
    Row(
        modifier = Modifier.requiredHeightIn(min = 108.dp).fillMaxSize().padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(np.art, 96.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            TrackTitle(np.title, MaterialTheme.typography.titleLarge)
            TrackLine(np.artist, MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Progress(np, controls.position, showTimes = false)
        }
        Spacer(Modifier.width(8.dp))
        // Glove-sized even in the strip: 52 dp skips, 60 dp play.
        TransportControls(np.isPlaying, controls, buttonSize = 52.dp, playSize = 60.dp)
    }
}

@Composable
private fun MediaExpanded(np: NowPlaying, controls: Controls) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(np.art, 160.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                TrackTitle(np.title, MaterialTheme.typography.headlineSmall, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                TrackLine(np.artist, MaterialTheme.typography.titleMedium)
                TrackLine(np.album, MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(20.dp))
        Progress(np, controls.position, showTimes = true)
        Spacer(Modifier.height(8.dp))
        TransportControls(np.isPlaying, controls, buttonSize = 56.dp, playSize = 68.dp, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MediaFullscreen(np: NowPlaying, controls: Controls) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(np.art, 280.dp)
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            TrackTitle(np.title, MaterialTheme.typography.headlineMedium, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TrackLine(np.artist, MaterialTheme.typography.titleLarge)
            TrackLine(np.album, MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(28.dp))
            Progress(np, controls.position, showTimes = true)
            Spacer(Modifier.height(12.dp))
            TransportControls(np.isPlaying, controls, buttonSize = 64.dp, playSize = 80.dp, Modifier.fillMaxWidth())
        }
    }
}

/** Fixed-size square per mode, so the art never rescales mid-animation (it only clips). */
@Composable
private fun AlbumArt(art: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(if (size > 120.dp) 16.dp else 12.dp)
    Box(
        modifier = Modifier.size(size).clip(shape).testTag("media_art"),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)),
                ),
            )
            Icon(AppIcons.MusicNote, contentDescription = "No album art", modifier = Modifier.size(size / 3))
        }
    }
}

@Composable
private fun TrackTitle(text: String, style: TextStyle, maxLines: Int = 1) {
    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("media_title"),
    )
}

@Composable
private fun TrackLine(text: String, style: TextStyle) {
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The bar reads [position] at draw time; only the time labels recompose per tick. */
@Composable
private fun Progress(np: NowPlaying, position: () -> Long, showTimes: Boolean) {
    fun fraction() = if (np.durationMs > 0) (position().toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().testTag("media_progress")) {
        LinearProgressIndicator(
            progress = ::fraction,
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = AppTheme.colors.cyan,
            trackColor = AppTheme.colors.outline,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        if (showTimes) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                TimeLabel(formatPlaybackTime(position()), Modifier.testTag("media_position"))
                Spacer(Modifier.weight(1f))
                TimeLabel(if (np.durationMs > 0) formatPlaybackTime(np.durationMs) else "")
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    controls: Controls,
    buttonSize: Dp,
    playSize: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (controls.showSkips) {
            IconButton(onClick = { haptics.tap(); controls.onPrevious() }, modifier = Modifier.size(buttonSize).testTag("media_previous")) {
                Icon(AppIcons.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(buttonSize * 0.55f))
            }
        }
        FilledIconButton(
            onClick = { haptics.tap(); controls.onPlayPause() },
            modifier = Modifier.size(playSize).testTag("media_play_pause"),
            // The card's main button: off-white with a black icon (inverted in the light theme).
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = AppTheme.colors.button,
                contentColor = AppTheme.colors.onButton,
            ),
        ) {
            Icon(
                imageVector = if (isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(playSize * 0.5f),
            )
        }
        if (controls.showSkips) {
            IconButton(onClick = { haptics.tap(); controls.onNext() }, modifier = Modifier.size(buttonSize).testTag("media_next")) {
                Icon(AppIcons.SkipNext, contentDescription = "Next track", modifier = Modifier.size(buttonSize * 0.55f))
            }
        }
    }
}

/** `M:SS`, or `H:MM:SS` for long tracks. */
internal fun formatPlaybackTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
