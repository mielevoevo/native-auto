package com.nativeauto.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.settings.RefreshMode
import com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS
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

    fun setAutoPauseMinutes(minutes: Int) {
        viewModelScope.launch { settings.setAutoPauseMinutes(minutes) }
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

    private companion object {
        const val RESET_CONFIRM_MS = 3_000L
    }
}
