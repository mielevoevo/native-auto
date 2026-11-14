package com.nativeauto.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.call.PhoneCallSource
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.media.MediaSessionRepository
import com.nativeauto.setup.SetupState
import com.nativeauto.settings.OrientationMode
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.trip.DEFAULT_AUTO_START_HOLD_S
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 4.2: state of the first-run setup pages. Every choice goes straight to the same settings the Settings sheet edits,
 * so nothing is "applied" at the end; the permission states are read from the same sources the rest of the app uses.
 */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val setup: SetupState,
    private val location: LocationPermissionState,
    private val media: MediaSessionRepository,
    private val phone: PhoneCallSource,
) : ViewModel() {

    private fun <T> kotlinx.coroutines.flow.Flow<T>.hot(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val orientation: StateFlow<OrientationMode> = settings.orientationMode.hot(OrientationMode.LANDSCAPE)
    val theme: StateFlow<ThemeMode> = settings.themeMode.hot(ThemeMode.DARK)
    val unit: StateFlow<SpeedUnit> = settings.speedUnit.hot(SpeedUnit.KMH)
    val callControls: StateFlow<Boolean> = settings.callControls.hot(true)
    val autoStart: StateFlow<AutoStartMode> = settings.autoStartMode.hot(AutoStartMode.OFF)
    val autoStartHoldS: StateFlow<Int> = settings.autoStartHoldS.hot(DEFAULT_AUTO_START_HOLD_S)
    val autoLayout: StateFlow<Boolean> = settings.autoLayout.hot(true)
    val voice: StateFlow<Boolean> = settings.voiceGuidance.hot(true)

    val locationGranted: StateFlow<Boolean> = location.granted
    val notificationAccess: StateFlow<Boolean> = media.access
    val phoneRead: StateFlow<Boolean> = phone.canRead
    val phoneControl: StateFlow<Boolean> = phone.canControl

    /** After a permission prompt and when the app comes back from a system screen. */
    fun refresh() {
        location.refresh()
        phone.refreshPermissions()
        media.refreshAccess()
    }

    fun setOrientation(mode: OrientationMode) = viewModelScope.launch { settings.setOrientationMode(mode) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setUnit(unit: SpeedUnit) = viewModelScope.launch { settings.setSpeedUnit(unit) }
    fun setCallControls(on: Boolean) = viewModelScope.launch { settings.setCallControls(on) }
    fun setAutoStart(on: Boolean) = viewModelScope.launch { settings.setAutoStartMode(if (on) AutoStartMode.TRIP else AutoStartMode.OFF) }
    fun setAutoStartHold(seconds: Int) = viewModelScope.launch { settings.setAutoStartHoldS(seconds) }
    fun setAutoLayout(on: Boolean) = viewModelScope.launch { settings.setAutoLayout(on) }
    fun setVoice(on: Boolean) = viewModelScope.launch { settings.setVoiceGuidance(on) }

    /** "Start riding": the pages are done and never come back on their own. */
    fun finish() = viewModelScope.launch { setup.complete() }
}
