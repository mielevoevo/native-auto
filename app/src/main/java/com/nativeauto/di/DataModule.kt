package com.nativeauto.di

import android.content.Context
import androidx.room.Room
import com.nativeauto.BuildConfig
import com.nativeauto.core.SystemWallClock
import com.nativeauto.core.WallClock
import com.nativeauto.history.AddressResolver
import com.nativeauto.history.AppDatabase
import com.nativeauto.history.MIGRATION_1_2
import com.nativeauto.history.GeocoderAddressResolver
import com.nativeauto.history.RecentPlaceDao
import com.nativeauto.history.SnapshotTripMaps
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripMaps
import com.nativeauto.nav.FerrostarNavigator
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.PhotonPlaceSearch
import com.nativeauto.nav.PlaceSearch
import com.nativeauto.nav.RecentPlaces
import com.nativeauto.nav.RoomRecentPlaces
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "native-auto.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.trips()
    @Provides fun provideRecentPlaceDao(db: AppDatabase): RecentPlaceDao = db.recentPlaces()

    /**
     * One client for routing (FOSSGIS Valhalla) and search (Photon). Both are free community
     * services that ask apps to identify themselves.
     */
    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(20))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private const val USER_AGENT = "NativeAuto/${BuildConfig.VERSION_NAME} (Android dashboard; personal use)"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun bindWallClock(impl: SystemWallClock): WallClock
    @Binds abstract fun bindNavigator(impl: FerrostarNavigator): Navigator
    @Binds abstract fun bindPlaceSearch(impl: PhotonPlaceSearch): PlaceSearch
    @Binds abstract fun bindRecentPlaces(impl: RoomRecentPlaces): RecentPlaces
    @Binds abstract fun bindAddressResolver(impl: GeocoderAddressResolver): AddressResolver
    @Binds abstract fun bindTripMaps(impl: SnapshotTripMaps): TripMaps
}
