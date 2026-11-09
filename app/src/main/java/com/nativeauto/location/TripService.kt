package com.nativeauto.location

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nativeauto.MainActivity
import com.nativeauto.R
import com.nativeauto.core.MonotonicClock
import com.nativeauto.core.SelfClose
import com.nativeauto.settings.SettingsRepository
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import com.nativeauto.ui.speed.SpeedometerUiState
import com.nativeauto.ui.speed.formatElapsed
import com.nativeauto.ui.speed.label
import com.nativeauto.ui.speed.toUiState
import com.nativeauto.ui.speed.tripSecondTicks
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type `location`) that owns trip tracking (rule #10): it starts
 * [TripTracker] and keeps it alive with the screen off and the app in the background, with an
 * ongoing notification showing speed and elapsed time.
 *
 * The notification offers Pause / Resume (the service stays up, the GPS goes off) and
 * End trip (the service stops; the next start is a fresh trip). It also stops itself if the
 * trip ends on its own (location permission revoked, auto-end) or is discarded (Reset).
 *
 * Phase 9a: started only by the rider's Start (never on app open). Ending from the
 * notification arms [SelfClose]; swiping the app away in recent apps ends and saves the trip
 * (Android gives no chance to ask).
 */
@AndroidEntryPoint
class TripService : LifecycleService() {

    @Inject lateinit var tracker: TripTracker
    @Inject lateinit var clock: MonotonicClock
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var selfClose: SelfClose

    private var observing: Job? = null
    private var foreground = false
    /** Last state shown, so re-promoting (each time the app is reopened) doesn't blank it. */
    private var shown: SpeedometerUiState = SpeedometerUiState.Empty

    override fun onCreate() {
        super.onCreate()
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.trip_channel_name))
                .setShowBadge(false)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_END -> {
                // Only the notification sends this; ending in the app goes straight to the tracker
                // (the service then stops on its own), so an app ended in use never self-closes.
                tracker.end()
                selfClose.arm()
                shutDown()
                return START_NOT_STICKY
            }
            ACTION_PAUSE, ACTION_RESUME -> {
                // Only meaningful while the trip notification is up; never linger otherwise.
                if (!foreground) stopSelf()
                else if (intent.action == ACTION_PAUSE) tracker.pause()
                else tracker.resume()
                return START_NOT_STICKY
            }
        }
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        tracker.start()
        selfClose.disarm()
        if (observing?.isActive != true) observing = observeTrip()
        // Not sticky: a system restart from the background can't get "while in use" location
        // access anyway, and the trip itself lives in memory. Reopening the app restarts it.
        return START_NOT_STICKY
    }

    /** Swiped away in recent apps: the trip is saved, and everything stops. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tracker.end()
        shutDown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Nothing can track without the service, so the trip is over.
        tracker.end()
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(shown),
            // The type only exists (and is only enforced) from Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        foreground = true
        true
    } catch (e: SecurityException) {
        // Android 14+: a location FGS needs location permission at start time.
        Log.w(TAG, "Can't start the trip service without location permission", e)
        false
    } catch (e: IllegalStateException) {
        // Android 12+: ForegroundServiceStartNotAllowedException when started from the background.
        Log.w(TAG, "Foreground service start not allowed", e)
        false
    }

    private fun observeTrip() = lifecycleScope.launch {
        launch {
            // The trip can end on its own (permission revoked, auto-end) or be discarded:
            // don't linger with a stale notification. Pausing keeps the service up.
            tracker.state.first { it == TripState.Ended || it == TripState.Idle }
            shutDown()
        }
        combine(
            tracker.stats,
            tripSecondTicks(tracker, clock),
            settings.speedUnit,
            tracker.state,
        ) { stats, _, unit, trip -> stats.toUiState(clock.nowNanos(), unit, trip) }
            .distinctUntilChanged() // ticks within the same second map to an equal state
            .collect(::post)
    }

    private fun post(ui: SpeedometerUiState) {
        // After shutDown a late update would resurrect the notification as an orphan whose
        // buttons no longer do anything, so only post while in the foreground.
        if (!foreground) return
        shown = ui
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Without the permission the FGS still runs; the notification just isn't shown.
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(ui))
    }

    private fun notification(ui: SpeedometerUiState): Notification {
        val paused = ui.tripState == TripState.Paused
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(ui.notificationTitle())
            .setContentText(ui.notificationText())
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                0,
                getString(if (paused) R.string.trip_resume else R.string.trip_pause),
                serviceAction(if (paused) ACTION_RESUME else ACTION_PAUSE, requestCode = 1),
            )
            .addAction(0, getString(R.string.trip_end), serviceAction(ACTION_END, requestCode = 2))
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, TripService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun shutDown() {
        // Order matters: stop the update loop first, or the "trip ended" update it is about
        // to post lands after the removal below.
        observing?.cancel()
        observing = null
        foreground = false
        shown = SpeedometerUiState.Empty
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    companion object {
        private const val TAG = "TripService"
        private const val CHANNEL_ID = "trip"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE = "com.nativeauto.action.PAUSE_TRIP"
        const val ACTION_RESUME = "com.nativeauto.action.RESUME_TRIP"
        const val ACTION_END = "com.nativeauto.action.END_TRIP"

        /**
         * The rider's Start: starts tracking, or keeps it as it is (a paused trip stays paused,
         * an ended one is replaced by a fresh trip). Call while the app is visible, with
         * location granted.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't start the trip service", e)
            }
        }

        /** Resumes a paused trip (the service is already in the foreground while paused). */
        fun resume(context: Context) {
            try {
                context.startService(Intent(context, TripService::class.java).setAction(ACTION_RESUME))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Couldn't resume the trip", e)
            }
        }
    }
}

/** e.g. "100 km/h", "-- km/h" without a live fix, or "Trip paused". */
internal fun SpeedometerUiState.notificationTitle(): String =
    if (tripState == TripState.Paused) "Trip paused" else "${speed?.toString() ?: "--"} ${unit.speedLabel}"

/** e.g. "0:42 · 1.2 km · GPS Locked"; just time and distance while paused. */
internal fun SpeedometerUiState.notificationText(): String {
    val trip = "${formatElapsed(elapsed)} · ${String.format(Locale.ROOT, "%.1f", distance)} ${unit.distanceLabel}"
    return if (tripState == TripState.Paused) trip else "$trip · GPS ${gps.label}"
}
