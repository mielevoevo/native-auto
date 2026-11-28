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
import com.nativeauto.garage.BikeRecord
import com.nativeauto.garage.CustomFuel
import com.nativeauto.garage.DashKmPerL
import com.nativeauto.garage.FuelMark
import com.nativeauto.garage.FuelPrice
import com.nativeauto.garage.GarageDao
import com.nativeauto.garage.OdometerReading
import com.nativeauto.garage.RefuelRecord
import com.nativeauto.garage.ServiceItem
import com.nativeauto.garage.ServiceRecord
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
    /**
     * 4.3d: the riding load ([com.nativeauto.garage.LoadSums]): energy per km (J/kg/km) and the raw sums it came from.
     * Null for trips from before v4.6 and imported ones without it.
     */
    val loadIndex: Double? = null,
    val loadDistanceM: Double? = null,
    val loadAero: Double? = null,
    val loadAccel: Double? = null,
    val loadClimbM: Double? = null,
    val loadStops: Int? = null,
    /** 4.7: crawling (under 15 km/h, not stopped): seconds, metres and metres climbed. Null before v4.7. */
    val loadCrawlS: Double? = null,
    val loadCrawlM: Double? = null,
    val loadCrawlClimbM: Double? = null,
    /**
     * 4.8: the trip's effort ([com.nativeauto.garage.EffortSums]). Null on older and imported trips until it has been
     * worked out from their saved track ([TripDao.tripsWithoutEffort]), and on trips without one.
     */
    val effortM: Double? = null,
    val effortFlatJ: Double? = null,
    val effortSlopeJ: Double? = null,
    val effortSlopeM: Double? = null,
    val effortSlowS: Double? = null,
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
    /**
     * 4.7.2: metres above the WGS84 ellipsoid and how sure that is, to 0.1 m. For slopes (differences), not a height
     * above sea level. Null before v4.7.2 and on fixes without one.
     */
    val altitudeM: Float? = null,
    val altitudeAccuracyM: Float? = null,
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

    /** 4.8: trips with a saved track whose effort isn't known yet. */
    @Query("SELECT id FROM trips WHERE effortM IS NULL AND id IN (SELECT DISTINCT tripId FROM track_points)")
    suspend fun tripsWithoutEffort(): List<Long>

    @Query(
        "UPDATE trips SET effortM = :distanceM, effortFlatJ = :flatJ, effortSlopeJ = :slopeJ, effortSlopeM = :slopeM, " +
            "effortSlowS = :slowS WHERE id = :id",
    )
    suspend fun setEffort(id: Long, distanceM: Double, flatJ: Double, slopeJ: Double, slopeM: Double, slowS: Double)

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

@Database(
    entities = [
        TripRecord::class, RecentPlaceRecord::class, TrackPointRecord::class, BikeRecord::class, OdometerReading::class,
        RefuelRecord::class, FuelPrice::class, LiveTripRecord::class, LivePointRecord::class,
        ServiceItem::class, ServiceRecord::class, FuelMark::class, DashKmPerL::class,
        CustomFuel::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun recentPlaces(): RecentPlaceDao
    abstract fun garage(): GarageDao
    abstract fun liveTrip(): LiveTripDao
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

/**
 * v2 → v3 (11a): the Garage's bikes and odometer readings. Trips already have `bikeId` (9c);
 * the first bike added adopts the untagged ones.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bikes (" +
                "id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, model TEXT NOT NULL, " +
                "tankLitres REAL NOT NULL, reserveLitres REAL NOT NULL, fuelType TEXT NOT NULL, " +
                "startKmPerL REAL NOT NULL, odoFactor REAL NOT NULL, active INTEGER NOT NULL, " +
                "lastUsedAtMs INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS odometer_readings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bikeId TEXT NOT NULL, timeMs INTEGER NOT NULL, " +
                "km REAL NOT NULL, kind TEXT NOT NULL, awayKm REAL NOT NULL, tripOffsetM REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_odometer_readings_bikeId ON odometer_readings (bikeId)")
    }
}

/**
 * v3 → v4 (11b): the refuel log and the last pump price per fuel type; the trip-recovery
 * checkpoint (one row) and its fixes.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS refuels (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bikeId TEXT NOT NULL, timeMs INTEGER NOT NULL, " +
                "odometerKm REAL NOT NULL, litres REAL NOT NULL, pricePerL REAL NOT NULL, totalRp REAL NOT NULL, " +
                "full INTEGER NOT NULL, fuelType TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_refuels_bikeId ON refuels (bikeId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS fuel_prices (" +
                "fuelType TEXT NOT NULL, pricePerL REAL NOT NULL, updatedAtMs INTEGER NOT NULL, PRIMARY KEY(fuelType))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS live_trip (" +
                "id INTEGER NOT NULL, savedAtMs INTEGER NOT NULL, savedAtNanos INTEGER NOT NULL, " +
                "startedAtNanos INTEGER NOT NULL, distanceM REAL NOT NULL, maxSpeedMps REAL NOT NULL, " +
                "movingNanos INTEGER NOT NULL, pausedNanos INTEGER NOT NULL, pausedAtNanos INTEGER, " +
                "lastMovingAtNanos INTEGER, autoPaused INTEGER NOT NULL, paused INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS live_points (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestampNanos INTEGER NOT NULL, " +
                "latitude REAL NOT NULL, longitude REAL NOT NULL, speedMps REAL NOT NULL, speedAccuracyMps REAL, " +
                "accuracyM REAL NOT NULL, bearingDeg REAL)",
        )
    }
}

/** v4 → v5 (11c): service items (km / months, or a due date) and their history. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS service_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bikeId TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "intervalKm REAL, intervalMonths INTEGER, dueDateMs INTEGER, lastDoneMs INTEGER NOT NULL, lastDoneKm REAL, " +
                "createdAtMs INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_service_items_bikeId ON service_items (bikeId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS service_records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, bikeId TEXT NOT NULL, " +
                "itemName TEXT NOT NULL, timeMs INTEGER NOT NULL, odometerKm REAL, costRp REAL, note TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_service_records_bikeId ON service_records (bikeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_service_records_itemId ON service_records (itemId)")
    }
}

/** 12b: gauge readings ("Set fuel level") and the bike's dash km/L after a ride. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS fuel_marks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bikeId TEXT NOT NULL, timeMs INTEGER NOT NULL, " +
                "odometerKm REAL NOT NULL, bars INTEGER NOT NULL, ofBars INTEGER NOT NULL, litres REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_fuel_marks_bikeId ON fuel_marks (bikeId)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dash_kmpl (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bikeId TEXT NOT NULL, timeMs INTEGER NOT NULL, " +
                "kmPerL REAL NOT NULL, tripKm REAL NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dash_kmpl_bikeId ON dash_kmpl (bikeId)")
    }
}

/** 4.3d: the riding load on each trip (nullable: older trips have none). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("loadIndex REAL", "loadDistanceM REAL", "loadAero REAL", "loadAccel REAL", "loadClimbM REAL", "loadStops INTEGER")
            .forEach { db.execSQL("ALTER TABLE trips ADD COLUMN $it") }
    }
}

/** 4.6.1: each bike's low-fuel warning, in km of range left. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bikes ADD COLUMN lowFuelKm INTEGER NOT NULL DEFAULT 30")
    }
}

/** 4.7: each bike's gauge bars (what its model had so far) and own odometer correction, and the trips' crawling. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bikes ADD COLUMN gaugeBars INTEGER NOT NULL DEFAULT 8")
        db.execSQL("UPDATE bikes SET gaugeBars = 6 WHERE model = 'NMAX_GEN1'")
        db.execSQL("ALTER TABLE bikes ADD COLUMN odoFactorManual REAL")
        listOf("loadCrawlS", "loadCrawlM", "loadCrawlClimbM").forEach { db.execSQL("ALTER TABLE trips ADD COLUMN $it REAL") }
    }
}

/** 4.7.2: each fix's altitude, on saved trips and in the checkpoint of the trip in progress. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("track_points", "live_points").forEach { table ->
            listOf("altitudeM", "altitudeAccuracyM").forEach { db.execSQL("ALTER TABLE $table ADD COLUMN $it REAL") }
        }
    }
}

/** 4.8: each bike's caution, fill-ups not to learn from, the rider's own fuels, and each trip's effort. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bikes ADD COLUMN cautionPct INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE refuels ADD COLUMN learn INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS custom_fuels (" +
                "`key` TEXT NOT NULL, name TEXT NOT NULL, createdAtMs INTEGER NOT NULL, retired INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`key`))",
        )
        listOf("effortM", "effortFlatJ", "effortSlopeJ", "effortSlopeM", "effortSlowS").forEach { db.execSQL("ALTER TABLE trips ADD COLUMN $it REAL") }
    }
}
