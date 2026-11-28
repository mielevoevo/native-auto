package com.nativeauto

import android.app.Application
import com.nativeauto.garage.GarageRepository
import com.nativeauto.di.ApplicationScope
import com.nativeauto.history.TripDao
import com.nativeauto.history.TripRecorder
import com.nativeauto.history.fillMissingEffort
import com.nativeauto.trip.AutoTrip
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Trip tracking is started by TripService when the rider presses Start, not here. The recorder
 * watches the tracker, writing each finished trip to the history; AutoTrip applies auto-pause
 * and auto-end.
 */
@HiltAndroidApp
class NativeAutoApp : Application() {
    @Inject lateinit var recorder: TripRecorder
    @Inject lateinit var autoTrip: AutoTrip
    @Inject lateinit var garage: GarageRepository
    @Inject lateinit var settings: com.nativeauto.settings.SettingsRepository
    @Inject lateinit var setup: com.nativeauto.setup.SetupState
    @Inject lateinit var trips: TripDao
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // 13c: first-run defaults that differ between a fresh install and an update (accent). Before anything else writes.
        // 4.2: decide first whether the setup pages show (an empty DataStore = a new install), before any default is written.
        runBlocking { setup.start() }
        val fresh = com.nativeauto.changelog.InstallInfo.isFreshInstall(this)
        runBlocking { settings.initialiseDefaults(fresh) }
        recorder.start()
        autoTrip.start()
        // 4.8: once after an update: the effort of the trips so far, from their saved tracks. Not while the app is starting.
        scope.launch {
            delay(EFFORT_FILL_DELAY_MS)
            runCatching { trips.fillMissingEffort() }
        }
        // 11a: "which bike?" after a big change of location, debug builds only (§6 privacy note).
        if (BuildConfig.DEBUG) garage.startWhichBikeCheck()
    }

    private companion object {
        const val EFFORT_FILL_DELAY_MS = 15_000L
    }
}
