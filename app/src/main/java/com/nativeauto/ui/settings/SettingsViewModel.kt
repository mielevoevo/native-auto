package com.nativeauto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.settings.RefreshMode
import com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS
import com.nativeauto.settings.LEGACY_ACCENT_ARGB
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.trip.DEFAULT_AUTO_START_HOLD_S
import com.nativeauto.trip.TripTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import com.nativeauto.settings.DEFAULT_AUTO_END_MIN
import com.nativeauto.settings.DEFAULT_AUTO_PAUSE_MIN
import com.nativeauto.settings.TelemetryStat
import kotlinx.coroutines.launch

/** Backs the settings sheet. (Map width is layout state, set straight on DashboardLayoutState.) */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val tracker: TripTracker,
) : ViewModel() {

    val speedUnit: StateFlow<SpeedUnit> =
        settings.speedUnit.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedUnit.KMH)
    val showBattery: StateFlow<Boolean> =
        settings.showBattery.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val useFakeGps: StateFlow<Boolean> =
        settings.useFakeGps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val voiceGuidance: StateFlow<Boolean> =
        settings.voiceGuidance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val simulateNavigation: StateFlow<Boolean> =
        settings.simulateNavigation.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoLayout: StateFlow<Boolean> =
        settings.autoLayout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DARK)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    val refreshMode: StateFlow<RefreshMode> =
        settings.refreshMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefreshMode.AUTO)

    fun setRefreshMode(mode: RefreshMode) {
        viewModelScope.launch { settings.setRefreshMode(mode) }
    }

    /** 14b: Landscape (default), Portrait or Auto. */
    val orientationMode: StateFlow<com.nativeauto.settings.OrientationMode> =
        settings.orientationMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nativeauto.settings.OrientationMode.LANDSCAPE)

    fun setOrientationMode(mode: com.nativeauto.settings.OrientationMode) {
        viewModelScope.launch { settings.setOrientationMode(mode) }
    }

    val dragHoldMs: StateFlow<Long> =
        settings.dragHoldMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_DRAG_HOLD_MS)

    fun setDragHoldMs(ms: Long) {
        viewModelScope.launch { settings.setDragHoldMs(ms) }
    }

    val autoPauseMinutes: StateFlow<Int> =
        settings.autoPauseMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_AUTO_PAUSE_MIN)
    val autoEndMinutes: StateFlow<Int> =
        settings.autoEndMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_AUTO_END_MIN)

    val hiddenStats: StateFlow<Set<TelemetryStat>> =
        settings.hiddenStats.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelemetryStat.DEFAULT_HIDDEN)

    fun setStatShown(stat: TelemetryStat, shown: Boolean) {
        viewModelScope.launch { settings.setStatShown(stat, shown) }
    }

    /** 13b: what the app does by itself while open and the bike moves. */
    val autoStartMode: StateFlow<AutoStartMode> =
        settings.autoStartMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoStartMode.OFF)
    val autoStartHoldS: StateFlow<Int> =
        settings.autoStartHoldS.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_AUTO_START_HOLD_S)

    fun setAutoStartMode(mode: AutoStartMode) {
        viewModelScope.launch { settings.setAutoStartMode(mode) }
    }

    fun setAutoStartHoldS(seconds: Int) {
        viewModelScope.launch { settings.setAutoStartHoldS(seconds) }
    }

    /** 13c: the accent (ARGB) and the route line's own colour (null follows the accent). */
    val accentColor: StateFlow<Int> =
        settings.accentColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LEGACY_ACCENT_ARGB)
    val routeColor: StateFlow<Int?> =
        settings.routeColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAccentColor(argb: Int) {
        viewModelScope.launch { settings.setAccentColor(argb) }
    }

    fun setRouteColor(argb: Int?) {
        viewModelScope.launch { settings.setRouteColor(argb) }
    }

    fun setAutoPauseMinutes(minutes: Int) {
        viewModelScope.launch { settings.setAutoPauseMinutes(minutes) }
    }

    val totalPeriod: StateFlow<com.nativeauto.settings.TotalPeriod> =
        settings.totalPeriod.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nativeauto.settings.TotalPeriod.DAY)

    fun setTotalPeriod(period: com.nativeauto.settings.TotalPeriod) {
        viewModelScope.launch { settings.setTotalPeriod(period) }
    }

    fun setAutoEndMinutes(minutes: Int) {
        viewModelScope.launch { settings.setAutoEndMinutes(minutes) }
    }

    fun setAutoLayout(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoLayout(enabled) }
    }

    fun setVoiceGuidance(enabled: Boolean) {
        viewModelScope.launch { settings.setVoiceGuidance(enabled) }
    }

    /** Debug builds only; takes effect when the next navigation starts. */
    fun setSimulateNavigation(enabled: Boolean) {
        viewModelScope.launch { settings.setSimulateNavigation(enabled) }
    }

    fun setSpeedUnit(unit: SpeedUnit) {
        viewModelScope.launch { settings.setSpeedUnit(unit) }
    }

    fun setShowBattery(show: Boolean) {
        viewModelScope.launch { settings.setShowBattery(show) }
    }

    /** Debug builds only. Switching sources mid-trip would mix two unrelated drives, so it also starts a new trip. */
    fun setUseFakeGps(enabled: Boolean) {
        viewModelScope.launch {
            settings.setUseFakeGps(enabled)
            tracker.discard()
        }
    }

    private val _resetArmed = MutableStateFlow(false)

    /** True between the first and second tap of Reset trip. */
    val resetArmed: StateFlow<Boolean> = _resetArmed.asStateFlow()
    private var disarm: Job? = null

    /**
     * Two taps within [RESET_CONFIRM_MS]: a glove brushing the button once can't wipe a trip.
     * Timed here on the real clock rather than in composition, where tests fast-forward delays.
     * Returns true when the trip was actually reset.
     */
    fun onResetTripTapped(): Boolean {
        disarm?.cancel()
        if (_resetArmed.value) {
            _resetArmed.value = false
            tracker.discard()
            return true
        }
        _resetArmed.value = true
        disarm = viewModelScope.launch {
            delay(RESET_CONFIRM_MS)
            _resetArmed.value = false
        }
        return false
    }

    private val _resetAppArmed = MutableStateFlow(false)

    /** True between the first and second tap of Reset app (Privacy and data). */
    val resetAppArmed: StateFlow<Boolean> = _resetAppArmed.asStateFlow()
    private var disarmApp: Job? = null

    /** Two taps within [RESET_CONFIRM_MS], like Reset trip; true when the caller should now clear the app's data. */
    fun onResetAppTapped(): Boolean {
        disarmApp?.cancel()
        if (_resetAppArmed.value) {
            _resetAppArmed.value = false
            return true
        }
        _resetAppArmed.value = true
        disarmApp = viewModelScope.launch {
            delay(RESET_CONFIRM_MS)
            _resetAppArmed.value = false
        }
        return false
    }

    private companion object {
        const val RESET_CONFIRM_MS = 3_000L
    }
}
