package com.nativeauto.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.nativeauto.trip.GpsStatus
import com.nativeauto.trip.SpeedSample
import com.nativeauto.trip.TripStats
import javax.inject.Inject

/**
 * Trip recovery (11b, Rider-Feedback "riding without the app"): the trip in progress is
 * written to the database about once a minute, so a process killed mid-ride (HyperOS, a crash)
 * loses at most a minute. On the next launch it's resumed or saved; see [TripRecorder.recover].
 *
 * One row: the trip's figures at [savedAtNanos] (monotonic, same boot only) and [savedAtMs]
 * (wall clock, to convert times after a reboot).
 */
@Entity(tableName = "live_trip")
data class LiveTripRecord(
    @PrimaryKey val id: Int = 1,
    val savedAtMs: Long,
    val savedAtNanos: Long,
    val startedAtNanos: Long,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val movingNanos: Long,
    val pausedNanos: Long,
    val pausedAtNanos: Long?,
    val lastMovingAtNanos: Long?,
    val autoPaused: Boolean,
    val paused: Boolean,
)

/** Every accepted fix of the trip in progress, appended at each checkpoint. */
@Entity(tableName = "live_points")
data class LivePointRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampNanos: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val speedAccuracyMps: Float?,
    val accuracyM: Float,
    val bearingDeg: Float?,
)

@Dao
interface LiveTripDao {
    @Upsert
    suspend fun upsert(trip: LiveTripRecord)

    @Insert
    suspend fun insertPoints(points: List<LivePointRecord>)

    @Query("SELECT * FROM live_trip WHERE id = 1")
    suspend fun trip(): LiveTripRecord?

    @Query("SELECT * FROM live_points ORDER BY timestampNanos, id")
    suspend fun points(): List<LivePointRecord>

    @Query("DELETE FROM live_trip")
    suspend fun deleteTrip()

    @Query("DELETE FROM live_points")
    suspend fun deletePoints()

    @Transaction
    suspend fun clear() {
        deletePoints()
        deleteTrip()
    }

    /** [replace]: a new trip's first checkpoint drops whatever an older one left. */
    @Transaction
    suspend fun checkpoint(trip: LiveTripRecord, points: List<LivePointRecord>, replace: Boolean) {
        if (replace) deletePoints()
        upsert(trip)
        if (points.isNotEmpty()) insertPoints(points)
    }
}

/** A stored checkpoint: the figures and the fixes so far (oldest first). */
data class Checkpoint(val trip: LiveTripRecord, val fixes: List<SpeedSample>)

/** Storage for the trip in progress; an in-memory fake in unit tests. */
interface TripCheckpoints {
    suspend fun write(trip: LiveTripRecord, newFixes: List<SpeedSample>, replace: Boolean)
    suspend fun read(): Checkpoint?
    suspend fun clear()
}

class RoomTripCheckpoints @Inject constructor(private val dao: LiveTripDao) : TripCheckpoints {
    override suspend fun write(trip: LiveTripRecord, newFixes: List<SpeedSample>, replace: Boolean) =
        dao.checkpoint(trip, newFixes.map { it.toRecord() }, replace)

    override suspend fun read(): Checkpoint? {
        val trip = dao.trip() ?: return null
        return Checkpoint(trip, dao.points().map { it.toSample() })
    }

    override suspend fun clear() = dao.clear()
}

private fun SpeedSample.toRecord() = LivePointRecord(
    timestampNanos = timestampNanos, latitude = latitude, longitude = longitude, speedMps = speedMps,
    speedAccuracyMps = speedAccuracyMps, accuracyM = horizontalAccuracyM, bearingDeg = bearingDeg,
)

private fun LivePointRecord.toSample() = SpeedSample(
    timestampNanos = timestampNanos, speedMps = speedMps, speedAccuracyMps = speedAccuracyMps,
    horizontalAccuracyM = accuracyM, latitude = latitude, longitude = longitude, bearingDeg = bearingDeg,
)

/** The figures to checkpoint. */
internal fun TripStats.toCheckpoint(paused: Boolean, savedAtMs: Long, savedAtNanos: Long, startedAtNanos: Long) = LiveTripRecord(
    savedAtMs = savedAtMs,
    savedAtNanos = savedAtNanos,
    startedAtNanos = startedAtNanos,
    distanceM = distanceM,
    maxSpeedMps = maxSpeedMps,
    movingNanos = movingNanos,
    pausedNanos = pausedNanos,
    pausedAtNanos = pausedAtNanos,
    lastMovingAtNanos = lastMovingAtNanos,
    autoPaused = autoPaused,
    paused = paused,
)

/** The figures back, with [last] as the reducer's last good fix (the next fix joins it in a straight line). */
internal fun LiveTripRecord.toStats(last: SpeedSample?) = TripStats(
    startedAtNanos = startedAtNanos,
    lastSample = last,
    currentSpeedMps = 0f,
    maxSpeedMps = maxSpeedMps,
    distanceM = distanceM,
    movingNanos = movingNanos,
    gpsStatus = GpsStatus.Searching,
    pausedNanos = pausedNanos,
    pausedAtNanos = pausedAtNanos,
    lastMovingAtNanos = lastMovingAtNanos,
    autoPaused = autoPaused,
)
