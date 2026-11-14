package com.nativeauto.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.call.PhoneCallSource
import com.nativeauto.media.MediaSessionRepository
import com.nativeauto.ui.components.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CallSettingsViewModel @Inject constructor(
    private val phone: PhoneCallSource,
    media: MediaSessionRepository,
    private val settings: com.nativeauto.settings.SettingsRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.callControls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEnabled(on: Boolean) { viewModelScope.launch { settings.setCallControls(on) } }

    /** Notification access (the call card's main source, shared with the media card). */
    val notificationAccess: StateFlow<Boolean> = media.access
    val canRead: StateFlow<Boolean> = phone.canRead
    val canControl: StateFlow<Boolean> = phone.canControl

    fun refresh() = phone.refreshPermissions()
}

/**
 * 14a: what the call card can do on this phone. Calls show up through notification access; allowing the phone
 * permissions adds plain phone calls (ringing state) and answering / ending them from the card. Each is the
 * platform's own runtime permission, asked for here, never at startup.
 */
@Composable
internal fun CallControlsRow(viewModel: CallSettingsViewModel = hiltViewModel()) {
    val access by viewModel.notificationAccess.collectAsStateWithLifecycle()
    val canRead by viewModel.canRead.collectAsStateWithLifecycle()
    val canControl by viewModel.canControl.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val allowed = canRead && canControl
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    // 14b+: one switch to opt out any time (the card and its takeover stay off; permissions are left as they are).
    SwitchRow(label = R.string.settings_call_controls, hint = R.string.settings_call_controls_hint, checked = enabled, tag = "settings_call_controls") {
        haptics.toggle(it); viewModel.setEnabled(it)
    }
    if (!enabled) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("What works on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                buildString {
                    append(if (access) "Calls from any app show on the dashboard. " else "Turn on notification access (Media card) so calls show. ")
                    append(if (allowed) "Phone calls can be answered and ended here." else "Allow the phone permission to answer and end phone calls here.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings_call_status"),
            )
        }
        if (!allowed) {
            Spacer(Modifier.width(16.dp))
            FilledTonalButton(
                onClick = {
                    haptics.tap()
                    request.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
                },
                modifier = Modifier.heightIn(min = 56.dp).testTag("settings_call_permissions"),
            ) { Text("Allow") }
        }
    }
}
