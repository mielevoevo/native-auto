package com.nativeauto

import com.nativeauto.settings.SavedLayout
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ClockFace
import com.nativeauto.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository] for ViewModel tests. */
class FakeSettingsRepository : SettingsRepository {
    override val speedUnit = MutableStateFlow(SpeedUnit.KMH)
    override suspend fun setSpeedUnit(unit: SpeedUnit) { speedUnit.value = unit }
    override val useFakeGps = MutableStateFlow(false)
    override suspend fun setUseFakeGps(enabled: Boolean) { useFakeGps.value = enabled }
    override val showTraffic = MutableStateFlow(false)
    override suspend fun setShowTraffic(enabled: Boolean) { showTraffic.value = enabled }
    override val showBattery = MutableStateFlow(true)
    override suspend fun setShowBattery(enabled: Boolean) { showBattery.value = enabled }
    override val savedLayout = MutableStateFlow(SavedLayout.Default)
    override suspend fun saveLayout(layout: SavedLayout) { savedLayout.value = layout }
    override val voiceGuidance = MutableStateFlow(true)
    override suspend fun setVoiceGuidance(enabled: Boolean) { voiceGuidance.value = enabled }
    override val themeMode = MutableStateFlow(ThemeMode.DARK)
    override suspend fun setThemeMode(mode: ThemeMode) { themeMode.value = mode }
    override val clockFace = MutableStateFlow(ClockFace.DIGITAL)
    override suspend fun setClockFace(face: ClockFace) { clockFace.value = face }
    override val garageValue = MutableStateFlow(com.nativeauto.settings.GarageValue.RANGE)
    override suspend fun setGarageValue(value: com.nativeauto.settings.GarageValue) { garageValue.value = value }
    override val autoStartMode = MutableStateFlow(com.nativeauto.trip.AutoStartMode.OFF)
    override suspend fun setAutoStartMode(mode: com.nativeauto.trip.AutoStartMode) { autoStartMode.value = mode }
    override val autoStartHoldS = MutableStateFlow(com.nativeauto.trip.DEFAULT_AUTO_START_HOLD_S)
    override suspend fun setAutoStartHoldS(seconds: Int) { autoStartHoldS.value = seconds }
    override val tankIcon = MutableStateFlow(com.nativeauto.settings.TankIcon.GAUGE)
    override suspend fun setTankIcon(icon: com.nativeauto.settings.TankIcon) { tankIcon.value = icon }
    override val totalPeriod = MutableStateFlow(com.nativeauto.settings.TotalPeriod.DAY)
    override suspend fun setTotalPeriod(period: com.nativeauto.settings.TotalPeriod) { totalPeriod.value = period }
    override val refreshMode = MutableStateFlow(com.nativeauto.settings.RefreshMode.AUTO)
    override suspend fun setRefreshMode(mode: com.nativeauto.settings.RefreshMode) { refreshMode.value = mode }
    override val dragHoldMs = MutableStateFlow(com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS)
    override suspend fun setDragHoldMs(ms: Long) { dragHoldMs.value = ms }
    override val clockSeconds = MutableStateFlow(false)
    override suspend fun setClockSeconds(enabled: Boolean) { clockSeconds.value = enabled }
    override val autoPauseMinutes = MutableStateFlow(5)
    override suspend fun setAutoPauseMinutes(minutes: Int) { autoPauseMinutes.value = minutes }
    override val autoEndMinutes = MutableStateFlow(30)
    override suspend fun setAutoEndMinutes(minutes: Int) { autoEndMinutes.value = minutes }
    override val hiddenStats = MutableStateFlow(com.nativeauto.settings.TelemetryStat.DEFAULT_HIDDEN)
    override suspend fun setStatShown(stat: com.nativeauto.settings.TelemetryStat, shown: Boolean) {
        hiddenStats.value = if (shown) hiddenStats.value - stat else hiddenStats.value + stat
    }
    override val autoLayout = MutableStateFlow(true)
    override suspend fun setAutoLayout(enabled: Boolean) { autoLayout.value = enabled }
    override val simulateNavigation = MutableStateFlow(false)
    override suspend fun setSimulateNavigation(enabled: Boolean) { simulateNavigation.value = enabled }
}
