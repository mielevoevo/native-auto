package com.nativeauto.core

import javax.inject.Inject

/** Wall-clock time, for display only (trip history dates). Trip maths uses [MonotonicClock]. */
fun interface WallClock {
    fun nowMs(): Long
}

class SystemWallClock @Inject constructor() : WallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
