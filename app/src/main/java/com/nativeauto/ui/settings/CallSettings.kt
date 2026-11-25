package com.nativeauto.ui.settings

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.R
import com.nativeauto.call.PhoneCallSource
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
    private val settings: com.nativeauto.settings.SettingsRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = settings.callControls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEnabled(on: Boolean) { viewModelScope.launch { settings.setCallControls(on) } }

    val callerName: StateFlow<Boolean> = settings.callerName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setCallerName(show: Boolean) { viewModelScope.launch { settings.setCallerName(show) } }

    val canRead: StateFlow<Boolean> = phone.canRead
    val canControl: StateFlow<Boolean> = phone.canControl

    fun refresh() = phone.refreshPermissions()
}

/** 4.6.2 (Display): the call card and its takeover, on or off. Permissions are left as they are. */
@Composable
internal fun CallCardRow(viewModel: CallSettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    SwitchRow(label = R.string.settings_call_controls, hint = R.string.settings_call_controls_hint, checked = enabled, tag = "settings_call_controls") {
        haptics.toggle(it); viewModel.setEnabled(it)
    }
}

/** 4.7.1 (Display): the caller's name on the call card and pill, ringing and on the call. */
@Composable
internal fun CallerNameRow(viewModel: CallSettingsViewModel = hiltViewModel()) {
    val shown by viewModel.callerName.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    SwitchRow(label = R.string.settings_caller_name, hint = R.string.settings_caller_name_hint, checked = shown, tag = "settings_caller_name") {
        haptics.toggle(it); viewModel.setCallerName(it)
    }
}

/**
 * 4.6.2 (Others): the phone permissions behind SIM calls (ringing state, answering and ending). Calls of other apps come
 * through notification access. Asked for here, never at startup. Once Android no longer shows its prompt (refused before),
 * the same button opens the app's page in Android's settings; allowed, it goes there too, to take it back.
 */
@Composable
internal fun CallPermissionRow(viewModel: CallSettingsViewModel = hiltViewModel()) {
    val canRead by viewModel.canRead.collectAsStateWithLifecycle()
    val canControl by viewModel.canControl.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val allowed = canRead && canControl
    var askedAt by remember { mutableLongStateOf(0L) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.refresh()
        // An answer this fast is Android's own (no prompt was shown): the rider refused it earlier.
        val noPrompt = SystemClock.elapsedRealtime() - askedAt < NO_PROMPT_MS
        if (result.values.any { !it } && noPrompt) context.openAppDetails()
    }
    ActionRow(
        title = stringResource(R.string.settings_call_permission),
        hint = stringResource(if (allowed) R.string.settings_call_permission_on else R.string.settings_call_permission_off),
        hintTag = "settings_call_status",
        action = stringResource(if (allowed) R.string.settings_open else R.string.settings_call_permission_allow),
        tag = "settings_call_permissions",
    ) {
        haptics.tap()
        if (allowed) {
            context.openAppDetails()
        } else {
            askedAt = SystemClock.elapsedRealtime()
            request.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
        }
    }
}

private const val NO_PROMPT_MS = 600L
