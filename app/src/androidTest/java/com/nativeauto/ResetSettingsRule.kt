package com.nativeauto

import androidx.test.platform.app.InstrumentationRegistry
import com.nativeauto.di.SettingsEntryPoint
import com.nativeauto.settings.SavedLayout
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.settings.SpeedUnit
import com.nativeauto.settings.ThemeMode
import com.nativeauto.settings.ClockFace
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Puts the persisted preferences back to defaults before the activity launches. Since
 * Phase 7 the layout survives a relaunch, so without this a test that swaps sides would
 * move where every later test starts. Order it before the compose rule.
 */
class ResetSettingsRule : ExternalResource() {

    val settings: SettingsRepository
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            SettingsEntryPoint::class.java,
        ).settings()

    override fun before() = runBlocking {
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            SettingsEntryPoint::class.java,
        ).tripTracker().discard()
        val navigator = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            SettingsEntryPoint::class.java,
        ).navigator()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { navigator.stop() }
        // 11a: no bikes (so no Garage prompts) unless the test adds them.
        val entry = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            SettingsEntryPoint::class.java,
        )
        entry.garageDao().clear()
        // 11b: a checkpoint left by an earlier run would be recovered as a trip.
        entry.liveTripDao().clear()
        entry.garage().dismissPrompt()
        // 13a: the "What's new" sheet must not cover a test.
        entry.changelogStore().markSeen(com.nativeauto.BuildConfig.VERSION_CODE)
        settings.saveLayout(SavedLayout.Default)
        settings.setSpeedUnit(SpeedUnit.KMH)
        settings.setShowBattery(true)
        settings.setAutoLayout(true)
        settings.setThemeMode(ThemeMode.DARK)
        settings.setClockFace(ClockFace.DIGITAL)
        settings.setClockSeconds(false)
        settings.setGarageValue(com.nativeauto.settings.GarageValue.RANGE)
        settings.setTankIcon(com.nativeauto.settings.TankIcon.GAUGE)
        settings.setAutoStartMode(com.nativeauto.trip.AutoStartMode.OFF)
        settings.setAutoStartHoldS(com.nativeauto.trip.DEFAULT_AUTO_START_HOLD_S)
        settings.setTotalPeriod(com.nativeauto.settings.TotalPeriod.DAY)
        settings.setRefreshMode(com.nativeauto.settings.RefreshMode.AUTO)
        settings.setDragHoldMs(com.nativeauto.settings.DEFAULT_DRAG_HOLD_MS)
        settings.setAutoPauseMinutes(com.nativeauto.settings.DEFAULT_AUTO_PAUSE_MIN)
        settings.setAutoEndMinutes(com.nativeauto.settings.DEFAULT_AUTO_END_MIN)
        com.nativeauto.settings.TelemetryStat.entries.forEach {
            settings.setStatShown(it, it !in com.nativeauto.settings.TelemetryStat.DEFAULT_HIDDEN)
        }
    }
}
