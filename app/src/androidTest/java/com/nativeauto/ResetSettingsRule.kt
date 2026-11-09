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
        settings.saveLayout(SavedLayout.Default)
        settings.setSpeedUnit(SpeedUnit.KMH)
        settings.setShowBattery(true)
        settings.setAutoLayout(true)
        settings.setThemeMode(ThemeMode.DARK)
        settings.setClockFace(ClockFace.DIGITAL)
        settings.setClockSeconds(false)
        settings.setAutoPauseMinutes(com.nativeauto.settings.DEFAULT_AUTO_PAUSE_MIN)
        settings.setAutoEndMinutes(com.nativeauto.settings.DEFAULT_AUTO_END_MIN)
    }
}
