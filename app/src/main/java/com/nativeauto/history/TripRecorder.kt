package com.nativeauto.history

import com.nativeauto.core.MonotonicClock
import com.nativeauto.core.WallClock
import com.nativeauto.di.ApplicationScope
import com.nativeauto.garage.ActiveBike
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripStats
import com.nativeauto.trip.TripTracker
import com.nativeauto.trip.elapsedNanos
import com.nativeauto.trip.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes each finished trip to the history. It watches [TripTracker] from outside, so the
 * tracker stays unaware of storage:
 * - the path is the fixes the reducer *accepted* (each new `lastSample`), so it matches the
 *   distance on the card; rejected spikes and poor fixes never reach it;
 * - a trip is saved when it ends (End, auto-end, notification, app swiped away);
 * - it is tagged with the Garage's active bike at save time (11a);
 * - a discarded trip (Reset, 9a) is dropped: the tracker clears it without ending it;
 * - very short trips aren't kept: under [MIN_DISTANCE_M] or under [MIN_MOVING_MS] of moving
 *   time (opening the app at a desk, a Start pressed by mistake).
 *
 * 11b: the trip in progress is also checkpointed to the database about once a minute (and at
 * each pause / resume), so a process killed mid-ride can be resumed or saved on the next
 * launch ([recover]).
 */
@Singleton
class TripRecorder @Inject constructor(
    private val tracker: TripTracker,
    private val dao: TripDao,
    private val addresses: AddressResolver,
    private val bike: ActiveBike,
    private val checkpoints: TripCheckpoints,
    private val clock: MonotonicClock,
    private val wall: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
    /** 4.3d: the live trip's riding load, for the range (published at each checkpoint). */
    private val liveLoad: com.nativeauto.garage.LiveRidingLoad = com.nativeauto.garage.LiveRidingLoad(),
) {
    /** The short-trip limits; tests lower them to check the path geometry on short drives. */
    internal var minDistanceM = MIN_DISTANCE_M
    internal var minMovingMs = MIN_MOVING_MS

    private fun worthKeeping(s: TripStats) =
        s.distanceM >= minDistanceM && s.movingNanos / 1_000_000 >= minMovingMs
    private var started = false
    private val addressLock = Mutex()

    private val _lastOutcome = MutableStateFlow<TripOutcome?>(null)

    /**
     * What happened to the last ended trip (9b's end-of-trip summary): saved, with its record, or
     * too short to keep. Null while a trip is in progress or none has ended yet.
     */
    val lastOutcome: StateFlow<TripOutcome?> = _lastOutcome.asStateFlow()

    private class Recording(val startedAtNanos: Long) {
        val points = ArrayList<GeoPoint>()
        /** Every accepted fix, unsimplified: the full track (9c). ~10 k per 3 h ride. */
        val fixes = ArrayList<SpeedSample>()
        var stats: TripStats? = null
        var lastSample: SpeedSample? = null
        @Volatile var saved = false
        /** How many of [fixes] are in the checkpoint already, and when it was last written. */
        var checkpointed = 0
        var checkpointAtNanos = startedAtNanos
        var checkpointedPaused = false
        /** 4.3d: the riding load's running sums (O(1) per fix). */
        val load = com.nativeauto.garage.LoadAccumulator()

        fun add(sample: SpeedSample) {
            lastSample = sample
            fixes += sample
            load.add(sample)
            val p = GeoPoint(sample.latitude, sample.longitude)
            val prev = points.lastOrNull()
            if (prev == null || haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude) >= MIN_STEP_M) {
                points += p
            }
        }
    }

    /** The trip being recorded. [recover] sets it before restoring the tracker, so the path carries on. */
    @Volatile private var rec: Recording? = null
    private val checkpointLock = Mutex()

    /** Called once from the Application. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(tracker.state, tracker.stats, ::Pair).collect { (state, stats) ->
                val key = stats.startedAtNanos
                rec?.let { r ->
                    if (r.startedAtNanos != key) {
                        // Cleared without ending: discarded (Reset), so nothing is saved.
                        rec = null
                        liveLoad.publish(null)
                        if (!r.saved) clearCheckpoint()
                    }
                }
                if (key == null) return@collect
                val r = rec ?: Recording(key).also {
                    rec = it
                    _lastOutcome.value = null
                }
                r.stats = stats
                val sample = stats.lastSample
                if (sample != null && sample !== r.lastSample) r.add(sample)
                if (state == TripState.Ended && !r.saved) {
                    r.saved = true
                    liveLoad.publish(null)
                    save(r, endNanos = stats.endedAtNanos ?: clock.nowNanos())
                    clearCheckpoint()
                } else if (state == TripState.Running || state == TripState.Paused) {
                    val paused = state == TripState.Paused
                    val now = clock.nowNanos()
                    if (now - r.checkpointAtNanos >= CHECKPOINT_EVERY_NANOS || paused != r.checkpointedPaused) {
                        checkpoint(r, stats, paused, now)
                    }
                }
            }
        }
    }

    /** Writes the figures and the new fixes in the background: the collector never waits on disk. */
    private fun checkpoint(r: Recording, stats: TripStats, paused: Boolean, nowNanos: Long) {
        r.checkpointAtNanos = nowNanos
        liveLoad.publish(r.load.sums)
        r.checkpointedPaused = paused
        val from = r.checkpointed
        val newFixes = r.fixes.subList(from, r.fixes.size).toList()
        r.checkpointed = r.fixes.size
        val row = stats.toCheckpoint(paused, wall.nowMs(), nowNanos, r.startedAtNanos)
        scope.launch {
            checkpointLock.withLock {
                // Ended or discarded meanwhile: the checkpoint is already cleared; don't bring it back.
                if (r.saved || rec !== r) return@withLock
                checkpoints.write(row, newFixes, replace = from == 0)
            }
        }
    }

    private suspend fun clearCheckpoint() = checkpointLock.withLock { checkpoints.clear() }

    private var recoveryChecked = false

    /**
     * 11b, called when the app becomes visible (it acts once per process). A trip the process
     * lost mid-ride is
     * - **resumed** when its last checkpoint is under [RESUME_WITHIN_MS] old (same boot): the
     *   tracker carries on with the same figures and path, and the next fix joins the last one
     *   in a straight line. The caller then restarts the trip service.
     * - otherwise **saved as ended** at the last checkpointed point (if it's long enough to keep).
     *
     * Nothing happens while a trip is already in progress in this process.
     */
    suspend fun recover(): Recovery {
        if (recoveryChecked) return Recovery.None
        recoveryChecked = true
        if (tracker.state.value != TripState.Idle) return Recovery.None
        val cp = checkpointLock.withLock { checkpoints.read() } ?: return Recovery.None
        val row = cp.trip
        val nowNanos = clock.nowNanos()
        val nowMs = wall.nowMs()
        val sameBoot = nowNanos >= row.savedAtNanos
        val r = Recording(row.startedAtNanos)
        cp.fixes.forEach(r::add)
        r.checkpointed = cp.fixes.size
        r.checkpointAtNanos = if (sameBoot) row.savedAtNanos else nowNanos
        r.checkpointedPaused = row.paused
        val stats = row.toStats(cp.fixes.lastOrNull())
        r.stats = stats
        if (sameBoot && nowMs - row.savedAtMs in 0 until RESUME_WITHIN_MS) {
            rec = r
            tracker.restore(stats, paused = row.paused)
            return Recovery.Resumed
        }
        // Too old, or the phone rebooted: the trip ends where it was last saved.
        val endNanos = row.pausedAtNanos ?: cp.fixes.lastOrNull()?.timestampNanos ?: row.savedAtNanos
        r.stats = stats.copy(endedAtNanos = endNanos)
        save(r, endNanos, refNanos = row.savedAtNanos, refMs = row.savedAtMs, report = false)
        clearCheckpoint()
        return Recovery.SavedEnded
    }

    /**
     * [refNanos] / [refMs]: one instant on both clocks, to turn monotonic times into wall times
     * (a recovered trip uses its checkpoint's, which still holds after a reboot). [report]: set
     * [lastOutcome] for the end-of-trip summary; not for a recovered trip.
     */
    private suspend fun save(
        r: Recording,
        endNanos: Long,
        refNanos: Long = clock.nowNanos(),
        refMs: Long = wall.nowMs(),
        report: Boolean = true,
    ) {
        r.saved = true
        val s = r.stats ?: return
        // The last accepted fix may have been skipped as too close; the path still ends there.
        r.lastSample?.let { last ->
            val end = GeoPoint(last.latitude, last.longitude)
            if (r.points.lastOrNull() != end) r.points += end
        }
        if (!worthKeeping(s) || r.points.size < 2) {
            if (report) _lastOutcome.value = TripOutcome.TooShort
            return
        }
        fun wallMs(nanos: Long) = refMs - (refNanos - nanos) / 1_000_000
        val track = simplify(r.points, SIMPLIFY_TOLERANCE_M)
        val start = r.points.first()
        val end = r.points.last()
        val record =
            TripRecord(
                startedAtMs = wallMs(r.startedAtNanos),
                endedAtMs = wallMs(endNanos),
                durationMs = s.elapsedNanos(endNanos) / 1_000_000,
                movingMs = s.movingNanos / 1_000_000,
                distanceM = s.distanceM,
                maxSpeedMps = s.maxSpeedMps,
                startLat = start.latitude,
                startLon = start.longitude,
                endLat = end.latitude,
                endLon = end.longitude,
                track = Polyline.encode(track),
                stoppedMs = (s.elapsedNanos(endNanos) - s.movingNanos).coerceAtLeast(0) / 1_000_000,
                // 11a: the bike selected last (a switch mid-trip moves the whole trip to it).
                bikeId = bike.id(),
            ).withLoad(r.load.sums)
        val full = r.fixes.map {
            TrackPointRecord(
                tripId = 0,
                timeMs = wallMs(it.timestampNanos),
                latitude = it.latitude,
                longitude = it.longitude,
                speedMps = it.speedMps,
                bearingDeg = it.bearingDeg,
                accuracyM = it.horizontalAccuracyM,
                altitudeM = it.altitudeM?.toTenths(),
                altitudeAccuracyM = it.altitudeAccuracyM?.toDouble()?.toTenths(),
            )
        }
        val id = dao.insertWithTrack(record, full)
        if (report) _lastOutcome.value = TripOutcome.Saved(record.copy(id = id))
        scope.launch { resolve(id, start, end, knownStart = null, knownEnd = null) }
    }

    /** Retries addresses that couldn't be looked up (offline at the time). The history calls this on open. */
    fun fillMissingAddresses() {
        scope.launch {
            dao.missingAddresses().forEach {
                resolve(it.id, GeoPoint(it.startLat, it.startLon), GeoPoint(it.endLat, it.endLon), it.startAddress, it.endAddress)
            }
        }
    }

    private suspend fun resolve(id: Long, start: GeoPoint, end: GeoPoint, knownStart: String?, knownEnd: String?) =
        addressLock.withLock {
            val s = knownStart ?: addresses.addressOf(start)
            val e = knownEnd ?: addresses.addressOf(end)
            if (s != knownStart || e != knownEnd) dao.setAddresses(id, s, e)
        }

    internal companion object {
        const val MIN_DISTANCE_M = 300.0
        const val MIN_MOVING_MS = 120_000L

        const val MIN_STEP_M = 3.0

        /** 11b: the trip in progress is checkpointed this often (and at each pause / resume). */
        const val CHECKPOINT_EVERY_NANOS = 60_000_000_000L

        /** A lost trip whose last checkpoint is younger than this is resumed; older, it's saved as ended. */
        const val RESUME_WITHIN_MS = 10 * 60_000L
        const val SIMPLIFY_TOLERANCE_M = 4.0
    }
}

/** What [TripRecorder.recover] found. */
enum class Recovery { None, Resumed, SavedEnded }

/** The fate of an ended trip, for the end-of-trip summary. */
sealed interface TripOutcome {
    data class Saved(val trip: TripRecord) : TripOutcome
    /** Under the short-trip limits (or no usable path): not kept. */
    data object TooShort : TripOutcome
}

/** 4.7.2: to 0.1 m (what GPS altitude is worth, and short in the backup); null when it isn't a number. */
internal fun Double.toTenths(): Float? = if (isFinite()) Math.round(this * 10) / 10f else null

/** 4.3d: the riding load's sums on a trip record (the index is null on a trip too short to say). */
internal fun TripRecord.withLoad(l: com.nativeauto.garage.LoadSums) = copy(
    loadIndex = l.energyPerKm,
    loadDistanceM = l.distanceM,
    loadAero = l.aero,
    loadAccel = l.accel,
    loadClimbM = l.climbM,
    loadStops = l.stops,
    loadCrawlS = l.crawlS,
    loadCrawlM = l.crawlM,
    loadCrawlClimbM = l.crawlClimbM,
)
