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
    /** Device tests seed and clean trip history, and check the shared files (9d). */
    fun tripDao(): com.nativeauto.history.TripDao
    fun tripFiles(): com.nativeauto.history.TripFiles
    /** 10b: tests that open shared links put navigation back to idle. */
    fun navigator(): com.nativeauto.nav.Navigator
    /** 11a: tests start from an empty Garage, and seed bikes through the repository. */
    fun garage(): com.nativeauto.garage.GarageRepository
    fun garageDao(): com.nativeauto.garage.GarageDao
    /** 11b: no leftover trip checkpoint is recovered into a test. */
    fun liveTripDao(): com.nativeauto.history.LiveTripDao
    /** 13a: tests mark the changelog seen, so its sheet never covers the UI under test. */
    fun changelogStore(): com.nativeauto.changelog.ChangelogStore
    /** 4.2: tests mark the first-run setup done, so its pages never cover the UI under test (one test reopens it). */
    fun setupState(): com.nativeauto.setup.SetupState
    /** 4.3c: tests mark today greeted, so the greeting never covers the UI under test (its own test clears it). */
    fun greetingStore(): com.nativeauto.greeting.GreetingStore
}
