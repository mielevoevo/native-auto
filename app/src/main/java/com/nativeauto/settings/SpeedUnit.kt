package com.nativeauto.settings

/** Display unit only. All maths stays in SI; conversion happens at display time (rule #7). */
enum class SpeedUnit(
    val speedLabel: String,
    val distanceLabel: String,
    /** Display units per metre-per-second. */
    val perMps: Float,
    val metersPerDistanceUnit: Double,
) {
    KMH(speedLabel = "km/h", distanceLabel = "km", perMps = 3.6f, metersPerDistanceUnit = 1_000.0),
    MPH(speedLabel = "mph", distanceLabel = "mi", perMps = 2.2369363f, metersPerDistanceUnit = 1_609.344),
}
