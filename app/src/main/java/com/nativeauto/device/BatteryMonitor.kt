package com.nativeauto.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Phone battery: whole percent and whether it's on a charger. */
data class BatteryStatus(val percent: Int, val charging: Boolean)

interface BatteryMonitor {
    /** Emits now and on each change, for as long as it's collected. */
    val status: Flow<BatteryStatus>
}

/**
 * ACTION_BATTERY_CHANGED is sticky, so registering hands back the current state at once; the
 * receiver lives only while collected (the UI collects it only while started).
 */
class AndroidBatteryMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BatteryMonitor {

    override val status: Flow<BatteryStatus> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.toBatteryStatus()?.let { trySend(it) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // a protected system broadcast still arrives
        )
        sticky?.toBatteryStatus()?.let { trySend(it) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun Intent.toBatteryStatus() = batteryStatusOf(
        level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        status = getIntExtra(BatteryManager.EXTRA_STATUS, -1),
        plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
    )
}

/** Pure, for tests. Null when the broadcast carries no usable level. */
internal fun batteryStatusOf(level: Int, scale: Int, status: Int, plugged: Int): BatteryStatus? {
    if (level < 0 || scale <= 0) return null
    val percent = (level * 100f / scale).toInt().coerceIn(0, 100)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0
    return BatteryStatus(percent, charging)
}
