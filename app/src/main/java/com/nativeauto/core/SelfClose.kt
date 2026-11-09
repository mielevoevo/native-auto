package com.nativeauto.core

import com.nativeauto.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Rider-Feedback "Decided": after a trip is ended from the notification, an app left in the
 * background closes itself after [DELAY_MS] (if the system hasn't killed it first). Coming back
 * to the app before then cancels it; so does starting a new trip.
 *
 * [MainActivity] reports when it is visible and finishes its task on [closeRequests].
 */
@Singleton
class SelfClose @Inject constructor(@ApplicationScope private val scope: CoroutineScope) {
    private var armed = false
    private var visible = false
    private var timer: Job? = null

    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    /** The trip was ended from the notification. */
    fun arm() {
        armed = true
        if (!visible) startTimer()
    }

    /** A new trip started: the app is in use again. */
    fun disarm() {
        armed = false
        timer?.cancel()
    }

    fun onVisible() {
        visible = true
        disarm()
    }

    fun onHidden() {
        visible = false
        if (armed) startTimer()
    }

    private fun startTimer() {
        timer?.cancel()
        timer = scope.launch {
            delay(DELAY_MS)
            armed = false
            _closeRequests.emit(Unit)
        }
    }

    companion object {
        /** "10–15 min" in the brief: the short end, the GPS is already off. */
        const val DELAY_MS = 10 * 60_000L
    }
}
