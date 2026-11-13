package com.nativeauto.trip

/**
 * 13b: what the app does by itself when it is open and the bike starts moving. Never in the
 * background: no service, no background location (the rider's rule). Pure logic here; the
 * watcher that feeds it fixes is `location/AutoStartMonitor`.
 */
enum class AutoStartMode(val label: String) {
    /** Nothing happens by itself (default, and what every install from before 13b keeps). */
    OFF("Off"),

    /** Riding adds its km to the odometer, fuel and services; no trip is saved. */
    ODOMETER_ONLY("Odometer only"),

    /** A trip starts by itself, saved to the history like any other. */
    TRIP("Start trip"),
}

/** The speed that counts as "riding": 15 km/h. */
const val AUTO_START_KMH = 15
const val AUTO_START_MPS = AUTO_START_KMH / 3.6f

/** How long the speed must be held, in seconds (setting; default [DEFAULT_AUTO_START_HOLD_S]). */
val AUTO_START_HOLD_CHOICES_S = listOf(20, 30, 45, 60)
const val DEFAULT_AUTO_START_HOLD_S = 30

/** A gap in the fixes this long breaks the hold (a tunnel, a lost fix): the proof has to be continuous. */
const val AUTO_START_MAX_GAP_NANOS = 10_000_000_000L

/**
 * The hold so far: when the speed first reached [AUTO_START_MPS] without dropping below it since,
 * and the distance covered meanwhile ([stats], through the trip's own filters, so a spike counts
 * for nothing here either).
 */
data class AutoStartState(
    val heldSinceNanos: Long? = null,
    val lastFixNanos: Long? = null,
    val stats: TripStats = TripStats(),
)

/** One step's outcome. [triggered]: the hold has just completed; [state] carries the km ridden during it. */
data class AutoStartStep(val state: AutoStartState, val triggered: Boolean)

/**
 * Folds one fix into the hold. Speed at or above [AUTO_START_MPS] for [holdNanos] in a row triggers.
 * - Fixes without a speed, or too inaccurate for the trip maths, are skipped (neither help nor reset).
 * - A speed below the threshold, or a gap over [AUTO_START_MAX_GAP_NANOS], starts the hold over.
 */
fun stepAutoStart(state: AutoStartState, fix: SpeedSample, holdNanos: Long): AutoStartStep {
    if (!fix.hasSpeed || fix.horizontalAccuracyM > TripReducer.MAX_ACCURACY_M) return AutoStartStep(state, false)
    val speedOk = fix.speedAccuracyMps?.let { it < TripReducer.MAX_SPEED_ACCURACY_MPS } ?: true
    if (!speedOk) return AutoStartStep(state, false)
    val gap = state.lastFixNanos?.let { fix.timestampNanos - it } ?: 0L
    val base = if (gap > AUTO_START_MAX_GAP_NANOS) AutoStartState() else state
    if (fix.speedMps < AUTO_START_MPS) return AutoStartStep(AutoStartState(lastFixNanos = fix.timestampNanos), false)
    val since = base.heldSinceNanos ?: fix.timestampNanos
    val next = AutoStartState(since, fix.timestampNanos, TripReducer.reduce(base.stats, fix))
    return AutoStartStep(next, fix.timestampNanos - since >= holdNanos)
}

/**
 * Whether auto-start may act now. Only from a clean state: the app was opened with no trip, and no
 * trip has ended since (the rider pressing End means "I'm done"; it stays suppressed until the app
 * is opened afresh). Never while a trip exists.
 */
fun autoStartAllowed(mode: AutoStartMode, tripState: TripState, endedThisSession: Boolean, cleanAtOpen: Boolean): Boolean =
    mode != AutoStartMode.OFF && cleanAtOpen && !endedThisSession && tripState == TripState.Idle

/**
 * Odometer-only: metres ridden since the last commit are added to the odometer in chunks of at
 * least this much, and what is left when riding stops or the app goes away.
 */
const val ODOMETER_CHUNK_M = 500.0

/** Stationary this long ends a run of odometer-only riding (the remainder is committed). */
const val ODOMETER_IDLE_NANOS = 120_000_000_000L
