package com.nativeauto.trip

/** Accumulated trip state, all SI (rule #7). Derived values are extension functions below. */
data class TripStats(
    val startedAtNanos: Long? = null,
    val lastSample: SpeedSample? = null,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val distanceM: Double = 0.0,
    val movingNanos: Long = 0L,
    val gpsStatus: GpsStatus = GpsStatus.Searching,
    /** Total time spent paused so far; excluded from elapsed (and so from the average). */
    val pausedNanos: Long = 0L,
    /** Set while paused: the trip clock is frozen at this instant. */
    val pausedAtNanos: Long? = null,
    /** Set once the trip has ended: every figure is frozen from here on. */
    val endedAtNanos: Long? = null,
)

/** Trip time: from the start to now (or to the pause / end), minus time spent paused. */
fun TripStats.elapsedNanos(nowNanos: Long): Long {
    val start = startedAtNanos ?: return 0L
    val until = endedAtNanos ?: pausedAtNanos ?: nowNanos
    return until - start - pausedNanos
}

/** Distance ÷ elapsed time (rule #11): never an average of speed samples. */
fun TripStats.averageSpeedMps(nowNanos: Long): Float {
    val sec = elapsedNanos(nowNanos) / 1e9
    return if (sec < 1.0) 0f else (distanceM / sec).toFloat()
}

/** Distance ÷ time spent moving, so stops don't drag the figure down. */
fun TripStats.movingAverageMps(): Float {
    val sec = movingNanos / 1e9
    return if (sec < 1.0) 0f else (distanceM / sec).toFloat()
}
