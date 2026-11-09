package com.nativeauto.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Index
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One finished trip. Times are wall clock (for display only; the trip maths ran on the
 * monotonic clock). Average speed is derived, distance ÷ duration (rule #11), not stored.
 */
@Entity(tableName = "trips")
data class TripRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** Trip time excluding pauses, like the speed card's elapsed. */
    val durationMs: Long,
    val movingMs: Long,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    /** Null until reverse geocoding succeeds (it's retried when the history opens). */
    val startAddress: String? = null,
    val endAddress: String? = null,
    /** The path, simplified, as a Google encoded polyline (precision 5). For drawing only. */
    val track: String,
    /** Time on the running trip below walking pace (lights, traffic); pauses not included (9c). */
    @ColumnInfo(defaultValue = "0") val stoppedMs: Long = 0,
    /** The bike that was active (9c: the field only; the Garage fills it in phase 11). */
    val bikeId: String? = null,
) {
    val averageSpeedMps: Float get() = if (durationMs < 1_000) 0f else (distanceM / (durationMs / 1000.0)).toFloat()
    val movingAverageMps: Float get() = if (movingMs < 1_000) 0f else (distanceM / (movingMs / 1000.0)).toFloat()
}

/**
 * One accepted fix of a saved trip (9c, the full track for export): every fix the reducer took,
 * unsimplified. Old (v1) trips have none; they only have [TripRecord.track].
 */
@Entity(tableName = "track_points", indices = [Index("tripId")])
data class TrackPointRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    /** Wall clock, derived from the fix's monotonic time when the trip is saved. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val bearingDeg: Float?,
    val accuracyM: Float,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<TripRecord>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<TripRecord?>

    @Insert
    suspend fun insert(trip: TripRecord): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPointRecord>)

    /** The trip and its full track, together or not at all. Returns the trip's id. */
    @Transaction
    suspend fun insertWithTrack(trip: TripRecord, points: List<TrackPointRecord>): Long {
        val id = insert(trip)
        if (points.isNotEmpty()) insertPoints(points.map { it.copy(tripId = id) })
        return id
    }

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY timeMs")
    suspend fun points(tripId: Long): List<TrackPointRecord>

    @Query("UPDATE trips SET startAddress = :start, endAddress = :end WHERE id = :id")
    suspend fun setAddresses(id: Long, start: String?, end: String?)

    @Query("SELECT * FROM trips WHERE startAddress IS NULL OR endAddress IS NULL")
    suspend fun missingAddresses(): List<TripRecord>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("DELETE FROM track_points WHERE tripId = :tripId")
    suspend fun deletePoints(tripId: Long)

    /** No foreign key (simpler migration), so the track goes with its trip here. */
    @Transaction
    suspend fun delete(id: Long) {
        deletePoints(id)
        deleteTrip(id)
    }
}

/** A place navigated to, for one-tap destinations (no typing with gloves on). */
@Entity(tableName = "recent_places")
data class RecentPlaceRecord(
    /** Rounded coordinates (~10 m), so the same place isn't listed twice. */
    @PrimaryKey val key: String,
    val name: String,
    val detail: String?,
    val latitude: Double,
    val longitude: Double,
    val usedAtMs: Long,
)

@Dao
interface RecentPlaceDao {
    @Query("SELECT * FROM recent_places ORDER BY usedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentPlaceRecord>>

    @Upsert
    suspend fun upsert(place: RecentPlaceRecord)

    @Query("DELETE FROM recent_places WHERE `key` NOT IN (SELECT `key` FROM recent_places ORDER BY usedAtMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(entities = [TripRecord::class, RecentPlaceRecord::class, TrackPointRecord::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun recentPlaces(): RecentPlaceDao
}

/**
 * v1 → v2 (9c): stopped time and the bike tag on trips, and the full-track table. Old trips get
 * their stopped time back as trip time minus moving time (what the app would have counted).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN stoppedMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN bikeId TEXT")
        db.execSQL("UPDATE trips SET stoppedMs = MAX(durationMs - movingMs, 0)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS track_points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tripId INTEGER NOT NULL, timeMs INTEGER NOT NULL, " +
                "latitude REAL NOT NULL, longitude REAL NOT NULL, speedMps REAL NOT NULL, bearingDeg REAL, " +
                "accuracyM REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_points_tripId ON track_points (tripId)")
    }
}
