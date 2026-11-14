package com.nativeauto.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nativeauto.device.BatteryMonitor
import com.nativeauto.device.BatteryStatus
import com.nativeauto.nav.NavState
import com.nativeauto.nav.Navigator
import com.nativeauto.settings.SavedLayout
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.RefreshMode
import com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Screen-level state: the layout to start from, the status corner's battery reading, and what
 * auto layout reacts to (navigation activity and its setting).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val settings: SettingsRepository,
    battery: BatteryMonitor,
    navigator: Navigator,
) : ViewModel() {

    private val _initialLayout = MutableStateFlow<SavedLayout?>(null)

    /** Null until DataStore has answered (a few ms); then fixed for this ViewModel's life. */
    val initialLayout: StateFlow<SavedLayout?> = _initialLayout.asStateFlow()

    init {
        viewModelScope.launch { _initialLayout.value = settings.savedLayout.first().sanitized() }
    }

    fun saveLayout(layout: SavedLayout) {
        viewModelScope.launch { settings.saveLayout(layout) }
    }

    /** Null while hidden: the battery receiver is only registered when the indicator shows. */
    val battery: StateFlow<BatteryStatus?> = settings.showBattery
        .flatMapLatest { show -> if (show) battery.status else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Planning, previewing or guiding: anything but idle. */
    val navigationActive: StateFlow<Boolean> = navigator.state
        .map { it !is NavState.Idle }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), navigator.state.value !is NavState.Idle)

    val autoLayout: StateFlow<Boolean> =
        settings.autoLayout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 12a: refresh-rate policy and the hold before a card lifts. */
    val refreshMode: StateFlow<RefreshMode> =
        settings.refreshMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefreshMode.AUTO)
    val dragHoldMs: StateFlow<Long> =
        settings.dragHoldMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_DRAG_HOLD_MS)
}

/** A damaged or hand-edited preferences file must not produce an unusable layout. */
internal fun SavedLayout.sanitized() = copy(
    split = split.takeIf { it.isFinite() }?.coerceIn(MIN_SPLIT, MAX_SPLIT) ?: 0.5f,
    preset = LayoutCodec.preset(preset).takeUnless { it.portrait }?.name ?: LayoutPreset.DUO.name,
    portraitPreset = LayoutCodec.portraitPreset(portraitPreset)?.name.orEmpty(),
    slots = LayoutCodec.encode(LayoutCodec.decode(slots)),
)

/**
 * A fresh layout state from what was saved last time, for the screen's current orientation ([portrait]).
 * Each orientation remembers its own preset; a portrait start with none saved derives it from the landscape one
 * ([rotatedView]: same panes, same order).
 */
internal fun SavedLayout.toLayoutState(portrait: Boolean = false): DashboardLayoutState {
    val landscape = LayoutCodec.preset(preset)
    val portraitSaved = LayoutCodec.portraitPreset(portraitPreset)
    val assignments = LayoutCodec.decode(slots)
    if (!portrait) {
        return DashboardLayoutState(
            preset = landscape, assignments = assignments, bigOnLeft = bigOnLeft, split = split,
            lastPortraitPreset = portraitSaved,
        )
    }
    val turned = rotatedView(LayoutView(landscape, assignments.getValue(landscape)), toPortrait = true, lastPortrait = portraitSaved)
    val start = portraitSaved ?: turned.preset
    // Nothing saved for portrait yet: it starts as the landscape arrangement turned (same panes, same order).
    val withDerived = if (portraitSaved != null) assignments else assignments + (turned.preset to turned.panes)
    return DashboardLayoutState(
        preset = start, assignments = withDerived, bigOnLeft = bigOnLeft, split = split,
        portrait = true, lastLandscapePreset = landscape, lastPortraitPreset = portraitSaved,
    )
}

/** What to save: the user's own arrangement, never an auto-layout override. */
internal fun DashboardLayoutState.toSaved() = SavedLayout(
    bigOnLeft = bigOnLeft,
    split = split,
    // The landscape preset stays the one saved under `preset` (it is what older versions read).
    preset = (if (portrait) lastLandscapePreset ?: LayoutPreset.DUO else preset).name,
    portraitPreset = (if (portrait) preset else lastPortraitPreset)?.name.orEmpty(),
    slots = LayoutCodec.encode(assignments),
)

internal const val MIN_SPLIT = 0.3f
internal const val MAX_SPLIT = 0.7f
