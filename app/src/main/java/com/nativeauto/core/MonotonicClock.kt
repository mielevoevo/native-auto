package com.nativeauto.core

import android.os.SystemClock
import javax.inject.Inject

/**
 * Monotonic time source in nanoseconds, in the same time base as
 * `Location.elapsedRealtimeNanos` (rule #8). Never use the wall clock for trip maths.
 */
fun interface MonotonicClock {
    fun nowNanos(): Long
}

class SystemMonotonicClock @Inject constructor() : MonotonicClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
