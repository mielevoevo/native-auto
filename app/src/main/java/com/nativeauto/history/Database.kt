package com.nativeauto.history

import androidx.room.Dao
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
    /** The path, simplified, as a Google encoded polyline (precision 5). */
    val track: String,
) {
    val averageSpeedMps: Float get() = if (durationMs < 1_000) 0f else (distanceM / (durationMs / 1000.0)).toFloat()
    val movingAverageMps: Float get() = if (movingMs < 1_000) 0f else (distanceM / (movingMs / 1000.0)).toFloat()
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<TripRecord>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<TripRecord?>

    @Insert
    suspend fun insert(trip: TripRecord): Long

    @Query("UPDATE trips SET startAddress = :start, endAddress = :end WHERE id = :id")
    suspend fun setAddresses(id: Long, start: String?, end: String?)

    @Query("SELECT * FROM trips WHERE startAddress IS NULL OR endAddress IS NULL")
    suspend fun missingAddresses(): List<TripRecord>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)
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

@Database(entities = [TripRecord::class, RecentPlaceRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun recentPlaces(): RecentPlaceDao
}
