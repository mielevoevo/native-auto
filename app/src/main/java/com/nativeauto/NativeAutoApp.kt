package com.nativeauto

import android.app.Application
import com.nativeauto.garage.GarageRepository
import com.nativeauto.history.TripRecorder
import com.nativeauto.trip.AutoTrip
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

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

    override fun onCreate() {
        super.onCreate()
        recorder.start()
        autoTrip.start()
        // 11a: "which bike?" after a big change of location, debug builds only (§6 privacy note).
        if (BuildConfig.DEBUG) garage.startWhichBikeCheck()
    }
}
