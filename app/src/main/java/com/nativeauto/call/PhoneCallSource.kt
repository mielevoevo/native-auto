package com.nativeauto.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Backend 2 of the call card (14a): cellular state and control through the platform's own telephony APIs,
 * with the rider's permission ("Call controls" in settings). READ_PHONE_STATE gives ringing / in a call;
 * ANSWER_PHONE_CALLS lets the app answer and end a cellular call when the notification carries no such
 * action. Both are ordinary runtime permissions, nothing skin-specific.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PhoneCallSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val telecom = context.getSystemService(TelecomManager::class.java)

    private val _readGranted = MutableStateFlow(hasPermission(Manifest.permission.READ_PHONE_STATE))
    private val _answerGranted = MutableStateFlow(hasPermission(Manifest.permission.ANSWER_PHONE_CALLS))

    /** Can read ringing / in-call state. */
    val canRead: StateFlow<Boolean> = _readGranted.asStateFlow()

    /** May answer and end cellular calls. */
    val canControl: StateFlow<Boolean> = _answerGranted.asStateFlow()

    /** Call after a permission result and when the app comes back (the rider may have changed it in system settings). */
    fun refreshPermissions() {
        _readGranted.value = hasPermission(Manifest.permission.READ_PHONE_STATE)
        _answerGranted.value = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
    }

    /** The cellular call state; UNKNOWN until the rider allows reading it. */
    val state: Flow<PhoneState> = _readGranted.flatMapLatest { granted ->
        if (granted && telephony != null) listen(telephony) else flowOf(PhoneState.UNKNOWN)
    }.distinctUntilChanged()

    @Suppress("DEPRECATION")
    private fun listen(tm: TelephonyManager): Flow<PhoneState> = callbackFlow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) { trySend(state.toPhoneState()) }
            }
            try {
                tm.registerTelephonyCallback(context.mainExecutor, callback)
            } catch (e: SecurityException) {
                trySend(PhoneState.UNKNOWN)
            }
            awaitClose { tm.unregisterTelephonyCallback(callback) }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) { trySend(state.toPhoneState()) }
            }
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            awaitClose { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }

    /** Answers the ringing cellular call. False when not allowed or nothing rang. */
    @Suppress("MissingPermission")
    fun answer(): Boolean = guarded { telecom?.acceptRingingCall(); telecom != null }

    /** Ends the cellular call (or declines a ringing one). */
    @Suppress("MissingPermission", "DEPRECATION")
    fun end(): Boolean = guarded { telecom?.endCall() ?: false }

    private inline fun guarded(block: () -> Boolean): Boolean {
        if (!_answerGranted.value) return false
        return try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to control the call", e)
            false
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Int.toPhoneState() = when (this) {
        TelephonyManager.CALL_STATE_RINGING -> PhoneState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> PhoneState.OFFHOOK
        TelephonyManager.CALL_STATE_IDLE -> PhoneState.IDLE
        else -> PhoneState.UNKNOWN
    }

    private companion object {
        const val TAG = "NativeAuto/Call"
    }
}
