package com.nativeauto.garage

import com.nativeauto.core.MonotonicClock
import com.nativeauto.core.WallClock
import com.nativeauto.di.ApplicationScope
import com.nativeauto.nav.GeoPoint
import com.nativeauto.trip.TripState
import com.nativeauto.trip.TripTracker
import com.nativeauto.trip.haversineMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The bike trips are tagged with when saved. Null before the Garage has any bike. */
fun interface ActiveBike {
    suspend fun id(): String?
}

/** What the rider types in the bike editor. */
data class BikeDraft(
    val name: String,
    val model: BikeModel,
    val tankLitres: Double,
    val reserveLitres: Double,
    val fuel: FuelType,
    val startKmPerL: Double,
)

/** A question the dashboard shows as a dialog (never while a pane is being used for riding info). */
sealed interface GaragePrompt {
    /** §6 "Gen 2's odometer now?": the bike had been away; prefilled with the estimate. */
    data class OdometerNow(val bike: BikeRecord, val estimateKm: Double?) : GaragePrompt

    /** Debug builds: the trip started far from where the active bike was last ridden. */
    data class WhichBike(val bikes: List<BikeRecord>, val activeId: String, val distanceKm: Double) : GaragePrompt
}

/** A bike's odometer as the Garage shows it. */
data class OdometerState(
    val estimateKm: Double?,
    val lastReading: OdometerReading?,
    val factor: Double,
)

/**
 * The Garage's bikes and odometers (11a). Only the active bike collects the live trip, so the
 * Garage card (11c) can show its odometer moving during a ride.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GarageRepository @Inject constructor(
    private val dao: GarageDao,
    private val tracker: TripTracker,
    private val clock: MonotonicClock,
    private val wall: WallClock,
    @ApplicationScope private val scope: CoroutineScope,
) : ActiveBike {

    val bikes: Flow<List<BikeRecord>> = dao.observeBikes()
    val active: Flow<BikeRecord?> = dao.observeActive()

    private val _prompt = MutableStateFlow<GaragePrompt?>(null)
    val prompt: StateFlow<GaragePrompt?> = _prompt.asStateFlow()

    /** Readings and the factor update are read-modify-write; one at a time. */
    private val lock = Mutex()

    override suspend fun id(): String? = dao.activeBike()?.id

    /** The trip in progress (running or paused), in wall time. An ended trip is already saved. */
    private fun liveNow(): LiveTrip? {
        val s = tracker.stats.value
        val start = s.startedAtNanos ?: return null
        if (tracker.state.value != TripState.Running && tracker.state.value != TripState.Paused) return null
        return LiveTrip(wallOf(start), s.distanceM)
    }

    private fun wallOf(nanos: Long): Long = wall.nowMs() - (clock.nowNanos() - nanos) / 1_000_000

    /** The live trip, changing every ~10 m (not every fix: the Garage needn't redraw at 1 Hz). */
    private val liveTrip: Flow<LiveTrip?> =
        combine(tracker.state, tracker.stats) { state, s ->
            if ((state == TripState.Running || state == TripState.Paused) && s.startedAtNanos != null) {
                s.startedAtNanos to Math.floor(s.distanceM / 10.0) * 10.0
            } else {
                null
            }
        }
            .distinctUntilChanged()
            .map { it?.let { (start, m) -> LiveTrip(wallOf(start), m) } }

    fun readings(bikeId: String): Flow<List<OdometerReading>> = dao.observeReadings(bikeId)

    fun odometer(bikeId: String): Flow<OdometerState> {
        val factor = dao.observeBikes().map { list -> list.firstOrNull { it.id == bikeId }?.odoFactor ?: 1.0 }.distinctUntilChanged()
        return dao.observeLastReading(bikeId).flatMapLatest { reading ->
            if (reading == null) {
                factor.map { OdometerState(null, null, it) }
            } else {
                combine(
                    dao.observeMetersSince(bikeId, reading.timeMs, reading.tripOffsetM),
                    dao.observeActive().map { it?.id == bikeId }.distinctUntilChanged()
                        .flatMapLatest { isActive -> if (isActive) liveTrip else flowOf(null) },
                    factor,
                ) { saved, live, f ->
                    OdometerState(estimateOdometerKm(reading, f, gpsMetersSince(reading, saved, live)), reading, f)
                }
            }
        }
    }

    /** The estimate right now (one-shot). */
    suspend fun estimateNow(bikeId: String): Double? {
        val reading = dao.lastReading(bikeId) ?: return null
        val bike = dao.bike(bikeId) ?: return null
        return estimateOdometerKm(reading, bike.odoFactor, gpsSince(bike, reading))
    }

    private suspend fun gpsSince(bike: BikeRecord, reading: OdometerReading): Double =
        gpsMetersSince(reading, dao.metersSince(bike.id, reading.timeMs, reading.tripOffsetM), if (bike.active) liveNow() else null)

    /**
     * Adds a bike. The first one becomes active and adopts every trip recorded before the
     * Garage existed. Returns its id.
     */
    suspend fun addBike(draft: BikeDraft, odometerKm: Double?): String = lock.withLock {
        val now = wall.nowMs()
        val id = UUID.randomUUID().toString()
        val first = dao.activeBike() == null
        dao.insertBike(
            BikeRecord(
                id = id,
                name = draft.name.trim(),
                model = draft.model.name,
                tankLitres = draft.tankLitres,
                reserveLitres = draft.reserveLitres,
                fuelType = draft.fuel.name,
                startKmPerL = draft.startKmPerL,
                active = first,
                lastUsedAtMs = now,
                createdAtMs = now,
            ),
        )
        if (first) dao.tagUntaggedTrips(id)
        if (odometerKm != null) insertReading(dao.bike(id)!!, odometerKm, ReadingKind.SET, 0.0)
        id
    }

    suspend fun updateBike(id: String, draft: BikeDraft) = lock.withLock {
        val bike = dao.bike(id) ?: return@withLock
        dao.updateBike(
            bike.copy(
                name = draft.name.trim(),
                model = draft.model.name,
                tankLitres = draft.tankLitres,
                reserveLitres = draft.reserveLitres,
                fuelType = draft.fuel.name,
                startKmPerL = draft.startKmPerL,
            ),
        )
    }

    /** Its readings go; its trips stay in the history, untagged. Another bike takes over if it was active. */
    suspend fun deleteBike(id: String) = lock.withLock {
        val wasActive = dao.bike(id)?.active == true
        dao.deleteBike(id)
        if (wasActive) dao.bikesByUse().firstOrNull()?.let { dao.switchActive(it.id, wall.nowMs()) }
        val p = _prompt.value
        if (p is GaragePrompt.OdometerNow && p.bike.id == id) _prompt.value = null
    }

    /**
     * Makes [id] the bike being ridden (a trip in progress is tagged with it when saved, §
     * "Decided": the last selected bike). If it had been idle for [AWAY_AFTER_MS], asks once for
     * its odometer: someone else may have ridden it.
     */
    suspend fun activate(id: String) {
        val target = lock.withLock {
            val bike = dao.bike(id) ?: return
            if (bike.active) return
            dao.switchActive(id, wall.nowMs())
            bike
        }
        if (_prompt.value is GaragePrompt.WhichBike) _prompt.value = null
        if (needsAwayCheck(target.lastUsedAtMs, wall.nowMs())) {
            _prompt.value = GaragePrompt.OdometerNow(dao.bike(id) ?: return, estimateNow(id))
        }
    }

    /**
     * The rider's reading for [bikeId]. From the away prompt ([away]), a higher value becomes
     * away km; otherwise it's a correction, which teaches the GPS factor. Returns what was stored.
     */
    suspend fun enterReading(bikeId: String, km: Double, away: Boolean = false): ReadingDecision = lock.withLock {
        val bike = dao.bike(bikeId) ?: return@withLock ReadingDecision.Unchanged
        val prev = dao.lastReading(bikeId)
        val gpsM = prev?.let { gpsSince(bike, it) } ?: 0.0
        val estimate = estimateOdometerKm(prev, bike.odoFactor, gpsM)
        val decision = decideReading(km, estimate, away)
        if (decision is ReadingDecision.Store) {
            if (decision.kind == ReadingKind.CORRECTION && prev != null) {
                val factor = learnFactor(bike.odoFactor, prev.km, km, gpsM / 1000.0)
                if (factor != bike.odoFactor) dao.setFactor(bikeId, factor)
            }
            insertReading(bike, km, decision.kind, decision.awayKm)
        }
        decision
    }

    private suspend fun insertReading(bike: BikeRecord, km: Double, kind: ReadingKind, awayKm: Double) {
        // A trip running now belongs to the active bike; only its distance from here on counts.
        val offset = if (bike.active) liveNow()?.distanceM ?: 0.0 else 0.0
        dao.insertReading(
            OdometerReading(bikeId = bike.id, timeMs = wall.nowMs(), km = km, kind = kind.name, awayKm = awayKm, tripOffsetM = offset),
        )
    }

    fun dismissPrompt() {
        _prompt.value = null
    }

    /** The away prompt's answer (null = skipped: counters carry on from the app's own km). */
    suspend fun answerOdometerPrompt(km: Double?) {
        val p = _prompt.value as? GaragePrompt.OdometerNow ?: return
        _prompt.value = null
        if (km != null) enterReading(p.bike.id, km, away = true)
    }

    private var checking = false

    /**
     * Debug builds only (§6: inferring "home" from location needs a disclosure and opt-in
     * before a public release). At each trip's first fix, if the active bike was last ridden
     * more than [WHICH_BIKE_KM] away, asks which bike this is. On the device only: the trip's
     * own fix against a saved trip's end, nothing sent anywhere.
     */
    fun startWhichBikeCheck() {
        if (checking) return
        checking = true
        scope.launch {
            tracker.stats
                .filter { it.startedAtNanos != null && it.lastSample != null }
                .distinctUntilChangedBy { it.startedAtNanos }
                .collect { s ->
                    val fix = s.lastSample ?: return@collect
                    val bikes = dao.observeBikes().first()
                    val active = bikes.firstOrNull { it.active } ?: return@collect
                    val last = dao.lastTrip(active.id)?.let { GeoPoint(it.endLat, it.endLon) }
                    val km = whichBikeDistanceKm(bikes.size, last, GeoPoint(fix.latitude, fix.longitude)) ?: return@collect
                    if (_prompt.value == null) _prompt.value = GaragePrompt.WhichBike(bikes, active.id, km)
                }
        }
    }

    /** The prompt's answer: switching runs the usual away check for the chosen bike. */
    suspend fun answerWhichBike(id: String) {
        _prompt.value = null
        activate(id)
    }
}

/** A trip starting this far from the active bike's last ride asks "which bike?" (debug). */
const val WHICH_BIKE_KM = 50.0

/** The distance that warrants the question, or null when there's nothing to ask. */
fun whichBikeDistanceKm(bikeCount: Int, lastRideEnd: GeoPoint?, here: GeoPoint): Double? {
    if (bikeCount < 2 || lastRideEnd == null) return null
    val km = haversineMeters(lastRideEnd.latitude, lastRideEnd.longitude, here.latitude, here.longitude) / 1000.0
    return km.takeIf { it >= WHICH_BIKE_KM }
}
