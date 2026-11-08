package com.nativeauto.di

import com.nativeauto.BuildConfig
import com.nativeauto.location.AndroidLocationPermissionState
import com.nativeauto.location.FusedSpeedSampleSource
import com.nativeauto.location.LocationPermissionState
import com.nativeauto.location.SpeedSampleSource
import com.nativeauto.location.SwitchableSpeedSampleSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Release: always the real GPS. Debug: real GPS by default, switchable to the scripted fake
 * drive from the debug layout tools ("Fake GPS"). `BuildConfig.DEBUG` is a compile-time
 * constant, so R8 drops the fake path from release builds.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideSpeedSampleSource(
        real: Provider<FusedSpeedSampleSource>,
        switchable: Provider<SwitchableSpeedSampleSource>,
    ): SpeedSampleSource = if (BuildConfig.DEBUG) switchable.get() else real.get()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindings {

    @Binds
    abstract fun bindLocationPermissionState(impl: AndroidLocationPermissionState): LocationPermissionState
}
