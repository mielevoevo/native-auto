package com.nativeauto.history

import com.nativeauto.core.MonotonicClock
import com.nativeauto.core.WallClock
import com.nativeauto.di.ApplicationScope
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
 * - a discarded trip (Reset, 9a) is dropped: the tracker clears it without ending it;
 * - very short trips aren't kept: under [MIN_DISTANCE_M] or under [MIN_MOVING_MS] of moving
 *   time (opening the app at a desk, a Start pressed by mistake).
 *
 * A trip in progress lives in memory, like the tracker's figures; if the process dies
 * mid-trip, that trip is lost with them.
 */
@Singleton
class TripRecorder @Inject constructor(
    private val tracker: TripTracker,
    private val dao: TripDao,
    private val addresses: AddressResolver,
    private val clock: MonotonicClock,
    private val wall: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
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
        var stats: TripStats? = null
        var lastSample: SpeedSample? = null
        var saved = false
    }

    /** Called once from the Application. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            var rec: Recording? = null
            combine(tracker.state, tracker.stats, ::Pair).collect { (state, stats) ->
                val key = stats.startedAtNanos
                rec?.let { r ->
                    if (r.startedAtNanos != key) {
                        // Cleared without ending: discarded (Reset), so nothing is saved.
                        rec = null
                    }
                }
                if (key == null) return@collect
                val r = rec ?: Recording(key).also {
                    rec = it
                    _lastOutcome.value = null
                }
                r.stats = stats
                val sample = stats.lastSample
                if (sample != null && sample !== r.lastSample) {
                    r.lastSample = sample
                    val p = GeoPoint(sample.latitude, sample.longitude)
                    val prev = r.points.lastOrNull()
                    if (prev == null || haversineMeters(prev.latitude, prev.longitude, p.latitude, p.longitude) >= MIN_STEP_M) {
                        r.points += p
                    }
                }
                if (state == TripState.Ended && !r.saved) {
                    r.saved = true
                    save(r, endNanos = stats.endedAtNanos ?: clock.nowNanos())
                }
            }
        }
    }

    private suspend fun save(r: Recording, endNanos: Long) {
        r.saved = true
        val s = r.stats ?: return
        // The last accepted fix may have been skipped as too close; the path still ends there.
        r.lastSample?.let { last ->
            val end = GeoPoint(last.latitude, last.longitude)
            if (r.points.lastOrNull() != end) r.points += end
        }
        if (!worthKeeping(s) || r.points.size < 2) {
            _lastOutcome.value = TripOutcome.TooShort
            return
        }
        val nowNanos = clock.nowNanos()
        val nowMs = wall.nowMs()
        fun wallMs(nanos: Long) = nowMs - (nowNanos - nanos) / 1_000_000
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
            )
        val id = dao.insert(record)
        _lastOutcome.value = TripOutcome.Saved(record.copy(id = id))
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
        const val SIMPLIFY_TOLERANCE_M = 4.0
    }
}

/** The fate of an ended trip, for the end-of-trip summary. */
sealed interface TripOutcome {
    data class Saved(val trip: TripRecord) : TripOutcome
    /** Under the short-trip limits (or no usable path): not kept. */
    data object TooShort : TripOutcome
}
