package com.nativeauto.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nativeauto.call.AudioRoute
import com.nativeauto.call.CallPhase
import com.nativeauto.call.CallRepository
import com.nativeauto.call.CallState
import com.nativeauto.call.formatCallDuration
import com.nativeauto.ui.components.AppIcons
import com.nativeauto.ui.components.DashboardCard
import com.nativeauto.ui.components.LocalPortrait
import com.nativeauto.ui.components.rememberHaptics
import com.nativeauto.ui.dashboard.CardMode
import com.nativeauto.ui.dashboard.DashboardLayoutState
import com.nativeauto.ui.dashboard.Pane
import com.nativeauto.ui.theme.AppTheme
import com.nativeauto.ui.theme.Geist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the call card (14a) and tells the dashboard when to give the card a slot. The clock that makes the
 * duration tick lives here (not in composition: the v2 test dispatcher would never go idle) and only runs while
 * a call is on.
 */
@HiltViewModel
class CallViewModel @Inject constructor(private val repository: CallRepository) : ViewModel() {
    val state: StateFlow<CallState?> = repository.state

    /** A call is ringing or on: the dashboard gives the card the lowest-priority slot. */
    val active: StateFlow<Boolean> = repository.state.map { it != null }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.state.value != null)

    /** Wall-clock now, every second while a call is on. */
    val nowMs: StateFlow<Long> = repository.state.map { it?.info?.phase == CallPhase.ACTIVE }.distinctUntilChanged()
        .flatMapLatest { ticking ->
            flow {
                emit(System.currentTimeMillis())
                while (ticking) {
                    delay(1_000)
                    emit(System.currentTimeMillis())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    fun answer() = repository.answer()
    fun decline() = repository.decline()
    fun hangUp() = repository.hangUp()
    fun toggleMute() = repository.toggleMute()
    fun nextRoute() = repository.nextRoute()
}

/**
 * The call card: who, ringing or how long, and the controls that this phone can actually do (14a): Answer and
 * Decline while it rings; Mute, Audio output and End while it is on. Big glove targets at every size.
 */
@Composable
fun CallCard(mode: CardMode, layout: DashboardLayoutState, viewModel: CallViewModel = hiltViewModel()) {
    val call by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.nowMs.collectAsStateWithLifecycle()
    DashboardCard(title = "Call", pane = Pane.CALL, mode = mode, layout = layout) {
        // The state flips to null when the call ends, a frame before the slot is given back: keep the card empty then.
        val c = call ?: return@DashboardCard
        CallBody(c, now, mode, viewModel)
    }
}

@Composable
private fun CallBody(call: CallState, now: Long, mode: CardMode, vm: CallViewModel) {
    val haptics = rememberHaptics()
    val big = mode == CardMode.Expanded || mode == CardMode.Fullscreen
    // 14b: a half-width card (about 190 dp) and a four-stack card take smaller buttons; in portrait the name and
    // the duration sit above, and the controls in the lower part of the card.
    val small = mode == CardMode.Narrow || mode == CardMode.Mini
    val portrait = LocalPortrait.current
    val colors = AppTheme.colors
    val status = when (call.info.phase) {
        CallPhase.RINGING -> ringingStatus(call)
        CallPhase.ACTIVE -> call.activeSinceMs?.let { formatCallDuration(now - it) } ?: "On the call"
    }
    Column(
        Modifier.fillMaxSize().requiredHeightIn(min = 112.dp).padding(horizontal = if (small) 8.dp else 16.dp).padding(bottom = 12.dp),
        verticalArrangement = if (portrait && !big) Arrangement.SpaceBetween else Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The who and how long: kept together so the controls can sit apart, in the lower part, in portrait.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f, fill = portrait && !big), verticalArrangement = Arrangement.Center) {
        Text(
            call.title,
            style = TextStyleFor(if (big) 44.sp else if (small) 24.sp else 30.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("call_name"),
        )
        Text(
            call.info.app,
            style = if (portrait) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
            maxLines = 1,
            modifier = Modifier.testTag("call_app"),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            status,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = if (call.info.phase == CallPhase.RINGING) colors.accent else colors.text,
            maxLines = 1,
            modifier = Modifier.testTag("call_status"),
        )
        }
        Spacer(Modifier.height(if (big) 20.dp else 10.dp))
        val size = if (big) 80.dp else if (small) 52.dp else 64.dp
        Row(horizontalArrangement = Arrangement.spacedBy(if (big) 28.dp else if (small) 8.dp else 18.dp), verticalAlignment = Alignment.CenterVertically) {
            when (call.info.phase) {
                CallPhase.RINGING -> {
                    if (call.capabilities.canDecline) {
                        CallButton(AppIcons.CallEnd, "Decline", "call_decline", size, container = colors.red, content = Color.White) { haptics.confirm(); vm.decline() }
                    }
                    if (call.capabilities.canAnswer) {
                        CallButton(AppIcons.Call, "Answer", "call_answer", size, container = colors.accent, content = colors.bg) { haptics.confirm(); vm.answer() }
                    }
                }
                CallPhase.ACTIVE -> {
                    if (call.capabilities.canMute) {
                        CallButton(
                            if (call.micMuted) AppIcons.MicOff else AppIcons.Mic,
                            if (call.micMuted) "Unmute microphone" else "Mute microphone",
                            "call_mute",
                            size,
                            container = if (call.micMuted) colors.navy else colors.buttonQuiet,
                            content = if (call.micMuted) colors.accent else colors.onButtonQuiet,
                            toggled = call.micMuted,
                        ) { haptics.toggle(!call.micMuted); vm.toggleMute() }
                    }
                    if (call.capabilities.canChooseAudio) {
                        CallButton(
                            call.route.icon(),
                            "Audio: ${call.route?.label ?: "phone"}",
                            "call_audio",
                            size,
                            container = colors.buttonQuiet,
                            content = colors.onButtonQuiet,
                        ) { haptics.tap(); vm.nextRoute() }
                    }
                    if (call.capabilities.canEnd) {
                        CallButton(AppIcons.CallEnd, "End call", "call_end", size, container = colors.red, content = Color.White) { haptics.confirm(); vm.hangUp() }
                    }
                }
            }
        }
        if (big && call.info.phase == CallPhase.ACTIVE && call.route != null) {
            Text("Audio: ${call.route.label}", style = MaterialTheme.typography.bodyMedium, color = colors.textDim, modifier = Modifier.padding(top = 8.dp).testTag("call_route"))
        }
    }
}

/** Never the same words twice: with no name to show, the big line already says "Incoming call". */
private fun ringingStatus(call: CallState) = if (call.title == "Incoming call") "Ringing" else "Incoming call"

private fun AudioRoute?.icon(): ImageVector = when (this) {
    AudioRoute.SPEAKER -> AppIcons.VolumeUp
    AudioRoute.BLUETOOTH -> AppIcons.Bluetooth
    AudioRoute.WIRED -> AppIcons.Headset
    else -> AppIcons.Call
}

@Composable
private fun TextStyleFor(size: androidx.compose.ui.unit.TextUnit) = androidx.compose.ui.text.TextStyle(
    fontFamily = Geist,
    fontSize = size,
    lineHeight = size * 1.1f,
    fontWeight = FontWeight.Bold,
    color = AppTheme.colors.text,
)

@Composable
private fun CallButton(
    icon: ImageVector,
    description: String,
    tag: String,
    size: Dp,
    container: Color,
    content: Color,
    toggled: Boolean? = null,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .testTag(tag)
            .semantics {
                if (toggled != null) {
                    role = Role.Switch
                    stateDescription = if (toggled) "On" else "Off"
                }
            },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(size * 0.5f))
    }
}


/**
 * 14b: while a pane is fullscreen the call card never takes its slot (the rider chose that pane), so the call shows as a
 * slim pill at the top instead: who, then Decline / Answer while it rings, or the duration with Mute and End while it
 * is on. It floats over the fullscreen card without moving or resizing it and goes when the call ends. It covers the
 * card's header meanwhile; Back and the hold menu still leave fullscreen.
 */
@Composable
fun CallPill(modifier: Modifier = Modifier, viewModel: CallViewModel = hiltViewModel()) {
    val call by viewModel.state.collectAsStateWithLifecycle()
    val now by viewModel.nowMs.collectAsStateWithLifecycle()
    val c = call ?: return
    val haptics = rememberHaptics()
    val colors = AppTheme.colors
    val ringing = c.info.phase == CallPhase.RINGING
    val status = if (ringing) ringingStatus(c) else c.activeSinceMs?.let { formatCallDuration(now - it) } ?: "On the call"
    androidx.compose.material3.Surface(
        modifier = modifier.testTag("call_pill"),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (ringing) colors.accent else colors.outline),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.widthIn(max = 160.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("call_pill_name"))
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (ringing) colors.accent else colors.textDim,
                    maxLines = 1,
                    modifier = Modifier.testTag("call_pill_status"),
                )
            }
            if (ringing) {
                if (c.capabilities.canDecline) CallButton(AppIcons.CallEnd, "Decline", "call_pill_decline", 52.dp, container = colors.red, content = Color.White) { haptics.confirm(); viewModel.decline() }
                if (c.capabilities.canAnswer) CallButton(AppIcons.Call, "Answer", "call_pill_answer", 52.dp, container = colors.accent, content = colors.bg) { haptics.confirm(); viewModel.answer() }
            } else {
                if (c.capabilities.canMute) {
                    CallButton(
                        if (c.micMuted) AppIcons.MicOff else AppIcons.Mic,
                        if (c.micMuted) "Unmute microphone" else "Mute microphone",
                        "call_pill_mute",
                        52.dp,
                        container = if (c.micMuted) colors.navy else colors.buttonQuiet,
                        content = if (c.micMuted) colors.accent else colors.onButtonQuiet,
                        toggled = c.micMuted,
                    ) { haptics.toggle(!c.micMuted); viewModel.toggleMute() }
                }
                if (c.capabilities.canEnd) CallButton(AppIcons.CallEnd, "End call", "call_pill_end", 52.dp, container = colors.red, content = Color.White) { haptics.confirm(); viewModel.hangUp() }
            }
        }
    }
}
