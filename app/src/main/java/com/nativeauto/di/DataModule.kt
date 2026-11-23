package com.nativeauto.di

import android.content.Context
import androidx.room.Room
import com.nativeauto.BuildConfig
import com.nativeauto.core.SystemWallClock
import com.nativeauto.core.WallClock
import com.nativeauto.history.AddressResolver
import com.nativeauto.history.AppDatabase
import com.nativeauto.history.MIGRATION_1_2
import com.nativeauto.history.MIGRATION_2_3
import com.nativeauto.history.MIGRATION_3_4
import com.nativeauto.history.MIGRATION_4_5
import com.nativeauto.history.MIGRATION_5_6
import com.nativeauto.history.MIGRATION_6_7
import com.nativeauto.history.LiveTripDao
import com.nativeauto.history.RoomTripCheckpoints
import com.nativeauto.history.TripCheckpoints
import com.nativeauto.garage.ActiveBike
import com.nativeauto.garage.GarageDao
import com.nativeauto.garage.GarageRepository
import com.nativeauto.history.GeocoderAddressResolver
import com.nativeauto.history.RecentPlaceDao
import com.nativeauto.history.SnapshotTripMaps
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripMaps
import com.nativeauto.nav.FerrostarNavigator
import com.nativeauto.nav.Navigator
import com.nativeauto.nav.PreferredPlaceSearch
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .build()

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.trips()
    @Provides fun provideRecentPlaceDao(db: AppDatabase): RecentPlaceDao = db.recentPlaces()
    @Provides fun provideGarageDao(db: AppDatabase): GarageDao = db.garage()
    @Provides fun provideLiveTripDao(db: AppDatabase): LiveTripDao = db.liveTrip()

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
    // 10a: TomTom when its key is set, Photon otherwise and on any TomTom failure.
    @Binds abstract fun bindPlaceSearch(impl: PreferredPlaceSearch): PlaceSearch
    @Binds abstract fun bindRecentPlaces(impl: RoomRecentPlaces): RecentPlaces
    @Binds abstract fun bindAddressResolver(impl: GeocoderAddressResolver): AddressResolver
    @Binds abstract fun bindTripMaps(impl: SnapshotTripMaps): TripMaps
    // 11a: trips are tagged with the Garage's active bike.
    @Binds abstract fun bindActiveBike(impl: GarageRepository): ActiveBike
    // 11b: the trip in progress is checkpointed for recovery after a kill.
    @Binds abstract fun bindTripCheckpoints(impl: RoomTripCheckpoints): TripCheckpoints
    // 13a: the changelog and the last version the rider has seen it for.
    @Binds abstract fun bindGreetingStore(impl: com.nativeauto.greeting.DataStoreGreetingStore): com.nativeauto.greeting.GreetingStore
    @Binds abstract fun bindChangelogStore(impl: com.nativeauto.changelog.DataStoreChangelogStore): com.nativeauto.changelog.ChangelogStore
    // 4.2: whether the first-run setup pages are still to be shown.
    @Binds abstract fun bindSetupState(impl: com.nativeauto.setup.DataStoreSetupState): com.nativeauto.setup.SetupState
}
