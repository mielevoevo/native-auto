package com.nativeauto.device

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.nativeauto.settings.OrientationMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the activity asks the system for in each [OrientationMode] (and Auto's two decisions). */
internal object RequestedOrientation {
    const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

    /** Auto with the phone's rotation lock on: the app follows the system, which is holding one orientation. */
    const val FOLLOW_SYSTEM = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    fun fixed(mode: OrientationMode): Int? = when (mode) {
        OrientationMode.LANDSCAPE -> LANDSCAPE
        OrientationMode.PORTRAIT -> PORTRAIT
        OrientationMode.AUTO -> null
    }
}

/**
 * 14b: applies the Orientation setting to an activity.
 * - **Landscape / Portrait:** fixed (180° flips allowed). The sensor and the system lock are both ignored.
 * - **Auto:** follows the phone's auto-rotate switch. Lock on: the app asks for nothing, so the system's locked
 *   orientation holds. Lock off: the accelerometer is read (only while this runs, i.e. the app is started) and
 *   [OrientationPolicy] turns the app after a steady, generous tilt, by asking for sensor-landscape or
 *   sensor-portrait. Nothing is recreated: the manifest declares the configuration changes.
 *
 * Call [run] inside `repeatOnLifecycle(STARTED)`; it suspends until cancelled.
 */
class OrientationController(
    private val activity: Activity,
    private val mode: Flow<OrientationMode>,
    private val policy: OrientationPolicy = OrientationPolicy(),
) {
    suspend fun run() {
        combine(mode, systemAutoRotate(activity)) { m, systemAuto -> m to systemAuto }
            .distinctUntilChanged()
            .collectLatest { (m, systemAuto) ->
                val fixed = RequestedOrientation.fixed(m)
                when {
                    fixed != null -> activity.requestedOrientation = fixed
                    !systemAuto -> activity.requestedOrientation = RequestedOrientation.FOLLOW_SYSTEM
                    else -> followTilt()
                }
            }
    }

    private suspend fun followTilt() {
        policy.reset()
        // Start from what is on screen, so entering Auto never turns the app by itself.
        var portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        activity.requestedOrientation = if (portrait) RequestedOrientation.PORTRAIT else RequestedOrientation.LANDSCAPE
        tiltSamples(activity).collectLatest { s ->
            val turn = policy.onSample(s.x, s.y, s.atMs, portrait) ?: return@collectLatest
            portrait = turn
            activity.requestedOrientation = if (turn) RequestedOrientation.PORTRAIT else RequestedOrientation.LANDSCAPE
        }
    }
}

private class Tilt(val x: Float, val y: Float, val atMs: Long)

/** Gravity in the screen's plane, smoothed, about 5 times a second (SENSOR_DELAY_NORMAL). Registered only while collected. */
private fun tiltSamples(context: Context): Flow<Tilt> = callbackFlow {
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (sensor == null) {
        awaitClose { }
        return@callbackFlow
    }
    var gx = 0f
    var gy = 0f
    var primed = false
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // A light low-pass: bumps on the road average out, a steady lean comes through.
            if (!primed) { gx = event.values[0]; gy = event.values[1]; primed = true }
            else { gx += 0.2f * (event.values[0] - gx); gy += 0.2f * (event.values[1] - gy) }
            trySend(Tilt(gx, gy, SystemClock.elapsedRealtime()))
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    awaitClose { manager.unregisterListener(listener) }
}

/** The phone's own auto-rotate switch (false = rotation locked). Re-emits when the rider flips it. */
internal fun systemAutoRotate(context: Context): Flow<Boolean> = callbackFlow {
    val resolver = context.contentResolver
    fun read() = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { trySend(read()) }
    }
    resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, observer)
    trySend(read())
    awaitClose { resolver.unregisterContentObserver(observer) }
}.distinctUntilChanged()
