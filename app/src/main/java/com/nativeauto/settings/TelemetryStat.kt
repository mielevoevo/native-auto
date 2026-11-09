package com.nativeauto.settings

/**
 * The trip figures a rider can show or hide (Rider-Feedback §4). Every trip records all of
 * them; the setting only chooses what the log face, the end-of-trip summary and the history
 * detail display. Order here is display order. [key] is what DataStore keeps (stable names).
 */
enum class TelemetryStat(val key: String, val label: String) {
    DISTANCE("distance", "Distance"),
    TIME("time", "Trip time"),
    AVERAGE("avg", "Average"),
    MAX("max", "Max speed"),
    MOVING_AVG("moving_avg", "Moving avg"),
    MOVING_TIME("moving_time", "Moving time"),
    STOPPED_TIME("stopped_time", "Stopped time"),
    ;

    companion object {
        /** New in 9c, so off until the rider asks for them; the rest showed before. */
        val DEFAULT_HIDDEN: Set<TelemetryStat> = setOf(MOVING_TIME, STOPPED_TIME)

        fun fromKeys(keys: Set<String>): Set<TelemetryStat> = entries.filterTo(mutableSetOf()) { it.key in keys }
    }
}
