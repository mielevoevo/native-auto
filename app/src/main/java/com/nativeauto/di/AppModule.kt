package com.nativeauto.di

import com.nativeauto.core.MonotonicClock
import com.nativeauto.core.SystemMonotonicClock
import com.nativeauto.settings.DataStoreSettingsRepository
import com.nativeauto.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** A process-lifetime scope for work that must outlive any UI (e.g. TripTracker, rule #10). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    @Binds
    abstract fun bindMonotonicClock(impl: SystemMonotonicClock): MonotonicClock

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
