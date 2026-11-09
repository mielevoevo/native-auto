package com.nativeauto.di

import com.nativeauto.device.AndroidBatteryMonitor
import com.nativeauto.device.BatteryMonitor
import com.nativeauto.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {

    @Binds
    abstract fun bindBatteryMonitor(impl: AndroidBatteryMonitor): BatteryMonitor
}

/**
 * For device tests: reset saved preferences before each launch, so a test that swaps the
 * layout doesn't change where the next one starts (the layout is persisted since Phase 7).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun settings(): SettingsRepository
    /** Device tests start each test with no trip (9a: nothing starts on its own). */
    fun tripTracker(): com.nativeauto.trip.TripTracker
}
