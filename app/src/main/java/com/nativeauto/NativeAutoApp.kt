package com.nativeauto

import android.app.Application
import com.nativeauto.history.TripRecorder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Trip tracking is started by TripService (via PermissionsGate), not here. The recorder only
 * watches the tracker, writing each finished trip to the history.
 */
@HiltAndroidApp
class NativeAutoApp : Application() {
    @Inject lateinit var recorder: TripRecorder

    override fun onCreate() {
        super.onCreate()
        recorder.start()
    }
}
