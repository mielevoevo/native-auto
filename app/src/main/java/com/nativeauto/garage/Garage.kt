package com.nativeauto.garage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.nativeauto.history.TripRecord
import java.util.Locale
import kotlinx.coroutines.flow.Flow

/**
 * The kind of bike. 4.8: each kind offers its own service parts ([serviceNames]); nothing else depends on it.
 * Stored by name; one this version doesn't know reads as [OTHER].
 */
enum class BikeType(val label: String) {
    SCOOTER_CVT("Scooter"),
    UNDERBONE("Underbone"),
    GEARED("Geared"),
    OTHER("Other"),
}

/** What goes in a tank: a pump fuel ([FuelType]) or one the rider added ([CustomFuel], 4.8). [key] is what is stored. */
interface Fuel {
    val key: String
    val label: String
    val brand: String?
    val grade: String
    /** Rp per litre until a price is known; 0 = none. */
    val defaultPricePerL: Double
}

/**
 * 4.8: a fuel the rider added, shared by every bike; its price is a [FuelPrice] like the others'. Deleting one only
 * [retired]s it, so the fill-ups made with it keep its name. [key] comes from the first name ([customFuelKey]), so the
 * same fuel added on two phones is one fuel after an import.
 */
@Entity(tableName = "custom_fuels")
data class CustomFuel(
    @PrimaryKey override val key: String,
    val name: String,
    val createdAtMs: Long,
    @ColumnInfo(defaultValue = "0")
    val retired: Boolean = false,
) : Fuel {
    override val label: String get() = name
    override val brand: String? get() = null
    override val grade: String get() = name
    override val defaultPricePerL: Double get() = 0.0
}

fun customFuelKey(name: String): String = "CUSTOM:" + name.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/** The fuel stored as [key]. One that isn't known here (from a newer version, or a damaged backup) keeps its key. */
fun fuelOf(key: String, custom: List<CustomFuel>): Fuel =
    FuelType.entries.firstOrNull { it.name == key }
        ?: custom.firstOrNull { it.key == key }
        ?: CustomFuel(key, "Other fuel", 0, retired = true)

/** What can be picked: the pump fuels, the rider's own, and [current] even when it was deleted since. */
fun fuelChoices(custom: List<CustomFuel>, current: Fuel? = null): List<Fuel> {
    val all = FuelType.entries + custom.filterNot { it.retired }.sortedBy { it.createdAtMs }
    return if (current == null || all.any { it.key == current.key }) all else all + current
}

/**
 * Indonesian pump fuels. [defaultPricePerL] (Rp, rates of 2026-11-09) is only a starting point:
 * the price the rider sets in the Garage (12c) or pays at the pump is stored ([FuelPrice], keyed
 * by name) and used from then on. [brand] / [grade] are the two-line button label (12c).
 */
enum class FuelType(
    override val label: String,
    override val brand: String?,
    override val grade: String,
    override val defaultPricePerL: Double,
) : Fuel {
    PERTALITE("Pertalite", null, "Pertalite", 10_000.0),
    PERTAMAX("Pertamax", "Pertamax", "92", 15_950.0),
    PERTAMAX_GREEN("Pertamax Green", "Pertamax", "Green", 19_150.0),
    PERTAMAX_TURBO("Pertamax Turbo", "Pertamax", "Turbo", 19_600.0),
    ;

    override val key: String get() = name
}

/**
 * Presets: they only prefill the editor, everything stays editable. Tanks from the makers' figures (4.7: sources in
 * DECISIONS.md), the reserve ~20% of the tank. [gaugeBars]: counted on the two NMAX generations; for the others it is
 * only a start (the rider sets it). [OTHER] is any bike: the editor keeps what is typed.
 */
enum class BikeModel(val label: String, val tankLitres: Double, val reserveLitres: Double, val gaugeBars: Int = DEFAULT_GAUGE_BARS) {
    NMAX_GEN1("NMAX Gen 1", 6.6, 1.3, 6),
    NMAX_GEN2("NMAX Gen 2", 7.1, 1.4, 8),
    BEAT("BeAT (2020 on)", 4.2, 0.8),
    VARIO_110("Vario 110", 3.7, 0.7),
    VARIO_125("Vario 125", 5.5, 1.1),
    VARIO_150("Vario 150", 5.5, 1.1),
    VARIO_160("Vario 160", 5.5, 1.1),
    PCX_150("PCX 150", 8.0, 1.6),
    PCX_160("PCX 160", 8.1, 1.6),
    OTHER("Other bike", 7.1, 1.4),
}

/** 4.7: the fuel gauge's bars, per bike. */
const val DEFAULT_GAUGE_BARS = 8
val GAUGE_BARS_RANGE = 4..12

/** Starting consumption for a new bike; 11b learns the real figure from full-to-full fill-ups. */
const val DEFAULT_KM_PER_L = 36.0

/**
 * One of the rider's bikes. Exactly one is [active] once any exist: trips are tagged with it,
 * and the odometer and (11b/11c) fuel and service counters follow it.
 */
@Entity(tableName = "bikes")
data class BikeRecord(
    @PrimaryKey val id: String,
    val name: String,
    /** [BikeType] name. */
    val type: String = BikeType.SCOOTER_CVT.name,
    /** [BikeModel] name. */
    val model: String,
    val tankLitres: Double,
    val reserveLitres: Double,
    /** [Fuel.key]. */
    val fuelType: String,
    val startKmPerL: Double,
    /** Bike km per GPS km, learned from odometer corrections (1.0 until then). */
    val odoFactor: Double = 1.0,
    val active: Boolean = false,
    /** When the bike stopped being the active one (or was created): the away-km check (§6). */
    val lastUsedAtMs: Long,
    val createdAtMs: Long,
    /** 4.6.1: warn at this many km of range left. */
    @ColumnInfo(defaultValue = "30")
    val lowFuelKm: Int = DEFAULT_LOW_FUEL_KM,
    /** 4.7: bars on the bike's fuel gauge ([GAUGE_BARS_RANGE]). */
    @ColumnInfo(defaultValue = "8")
    val gaugeBars: Int = DEFAULT_GAUGE_BARS,
    /** 4.7: the rider's own bike km per GPS km; null = the learned [odoFactor] (which keeps learning either way). */
    val odoFactorManual: Double? = null,
    /** 4.8: how much lower the range is shown, in percent ([RangeCaution]). */
    @ColumnInfo(defaultValue = "0")
    val cautionPct: Int = 0,
) {
    /** Bike km per GPS km, as used. */
    val factor: Double get() = odoFactorManual ?: odoFactor
    val bikeModel: BikeModel get() = BikeModel.entries.firstOrNull { it.name == model } ?: BikeModel.OTHER
    val bikeType: BikeType get() = BikeType.entries.firstOrNull { it.name == type } ?: BikeType.OTHER
    val caution: RangeCaution get() = RangeCaution.entries.firstOrNull { it.percent == cautionPct } ?: RangeCaution.NORMAL
}

/** 4.8: how cautious a bike's range is. Normal is the estimate as it is (itself on the low side, [Consumption.cautiousKmPerL]). */
enum class RangeCaution(val label: String, val percent: Int) {
    NORMAL("Normal", 0),
    CAUTIOUS("−10%", 10),
    VERY_CAUTIOUS("−20%", 20),
    ;

    val factor: Double get() = 1.0 - percent / 100.0
}

/** Why an odometer reading was entered. Stored by name. */
enum class ReadingKind {
    /** The first reading of a bike. */
    SET,
    /** The rider fixed the estimate (teaches [BikeRecord.odoFactor]). */
    CORRECTION,
    /**
     * Read after the bike was away (ridden by others): the gap is [OdometerReading.awayKm]. It
     * advances service counters but adds no trips, and makes the fuel level unknown until the
     * next full refuel (11b).
     */
    AWAY,
    /**
     * 11b: the rider's own ride without the app (phone at home, app not running), added by hand
     * as [OdometerReading.awayKm] on top of the estimate. 4.7.2: also km recorded with another phone (its backup
     * brings the trips and fill-ups). Counts for servicing and fuel like a
     * tracked ride (km/L still applies), teaches nothing, adds no trips. With 0 km (4.7): the estimate at the moment the
     * rider changed the odometer correction, so the new one counts from there on and nothing jumps.
     */
    UNTRACKED,
}

/**
 * The bike's real odometer at [timeMs]. The estimate after it is [km] plus the GPS distance
 * of the bike's trips since, times the learned factor (see `Odometer.kt`).
 */
@Entity(tableName = "odometer_readings", indices = [Index("bikeId")])
data class OdometerReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bikeId: String,
    val timeMs: Long,
    val km: Double,
    /** [ReadingKind] name. */
    val kind: String,
    /** AWAY and UNTRACKED: km ridden while the app wasn't there (by others, or by the rider). */
    val awayKm: Double = 0.0,
    /**
     * GPS metres the trip running at [timeMs] had already covered (0 if none). That trip is
     * saved later with its full distance, and only the part after the reading counts.
     */
    val tripOffsetM: Double = 0.0,
) {
    val readingKind: ReadingKind get() = ReadingKind.entries.firstOrNull { it.name == kind } ?: ReadingKind.CORRECTION
}

@Dao
interface GarageDao {
    @Query("SELECT * FROM bikes ORDER BY createdAtMs")
    fun observeBikes(): Flow<List<BikeRecord>>

    @Query("SELECT * FROM bikes WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<BikeRecord?>

    @Query("SELECT * FROM bikes WHERE active = 1 LIMIT 1")
    suspend fun activeBike(): BikeRecord?

    @Query("SELECT * FROM bikes WHERE id = :id")
    suspend fun bike(id: String): BikeRecord?

    @Query("SELECT * FROM bikes ORDER BY lastUsedAtMs DESC")
    suspend fun bikesByUse(): List<BikeRecord>

    @Insert
    suspend fun insertBike(bike: BikeRecord)

    @Update
    suspend fun updateBike(bike: BikeRecord)

    @Query("UPDATE bikes SET odoFactor = :factor WHERE id = :id")
    suspend fun setFactor(id: String, factor: Double)

    @Query("UPDATE bikes SET active = 0, lastUsedAtMs = :nowMs WHERE active = 1")
    suspend fun deactivateAll(nowMs: Long)

    @Query("UPDATE bikes SET active = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    /** One active bike at a time; the one left behind remembers when (the away-km check). */
    @Transaction
    suspend fun switchActive(id: String, nowMs: Long) {
        deactivateAll(nowMs)
        markActive(id)
    }

    @Query("DELETE FROM bikes WHERE id = :id")
    suspend fun deleteBikeRow(id: String)

    @Query("DELETE FROM refuels WHERE bikeId = :id")
    suspend fun deleteRefuels(id: String)

    @Query("DELETE FROM service_items WHERE bikeId = :id")
    suspend fun deleteServices(id: String)

    @Query("DELETE FROM service_records WHERE bikeId = :id")
    suspend fun deleteServiceRecords(id: String)

    @Query("DELETE FROM odometer_readings WHERE bikeId = :id")
    suspend fun deleteReadings(id: String)

    @Query("UPDATE trips SET bikeId = NULL WHERE bikeId = :id")
    suspend fun untagTrips(id: String)

    /** The first bike adopts the trips recorded before the Garage existed. */
    @Query("UPDATE trips SET bikeId = :id WHERE bikeId IS NULL")
    suspend fun tagUntaggedTrips(id: String)

    @Query("DELETE FROM fuel_marks WHERE bikeId = :id")
    suspend fun deleteFuelMarks(id: String)

    @Query("DELETE FROM dash_kmpl WHERE bikeId = :id")
    suspend fun deleteDashKmPerL(id: String)

    /** The bike, its readings, fill-ups and services go; its trips stay in the history, untagged. */
    @Transaction
    suspend fun deleteBike(id: String) {
        deleteReadings(id)
        deleteRefuels(id)
        deleteFuelMarks(id)
        deleteDashKmPerL(id)
        deleteServices(id)
        deleteServiceRecords(id)
        untagTrips(id)
        deleteBikeRow(id)
    }

    @Insert
    suspend fun insertReading(reading: OdometerReading): Long

    @Query("SELECT * FROM odometer_readings WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC")
    fun observeReadings(bikeId: String): Flow<List<OdometerReading>>

    @Query("SELECT * FROM odometer_readings WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC LIMIT 1")
    fun observeLastReading(bikeId: String): Flow<OdometerReading?>

    @Query("SELECT * FROM odometer_readings WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC LIMIT 1")
    suspend fun lastReading(bikeId: String): OdometerReading?

    /**
     * GPS metres of the bike's saved trips after [sinceMs]. A trip that was running at that
     * moment counts only past [offsetM] (what it had covered by then).
     */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN startedAtMs < :sinceMs THEN MAX(distanceM - :offsetM, 0) ELSE distanceM END), 0) " +
            "FROM trips WHERE bikeId = :bikeId AND endedAtMs > :sinceMs",
    )
    fun observeMetersSince(bikeId: String, sinceMs: Long, offsetM: Double): Flow<Double>

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN startedAtMs < :sinceMs THEN MAX(distanceM - :offsetM, 0) ELSE distanceM END), 0) " +
            "FROM trips WHERE bikeId = :bikeId AND endedAtMs > :sinceMs",
    )
    suspend fun metersSince(bikeId: String, sinceMs: Long, offsetM: Double): Double

    /** Where the bike was last ridden to (the debug "which bike?" check). */
    @Query("SELECT * FROM trips WHERE bikeId = :bikeId ORDER BY endedAtMs DESC LIMIT 1")
    suspend fun lastTrip(bikeId: String): TripRecord?

    /** 4.8: the bike's trips with effort sums ([EffortSums]), newest first. */
    @Query(
        "SELECT endedAtMs, effortM AS distanceM, effortFlatJ AS flatJ, effortSlopeJ AS slopeJ, effortSlopeM AS slopeM, effortSlowS AS slowS " +
            "FROM trips WHERE bikeId = :bikeId AND effortM IS NOT NULL AND effortFlatJ IS NOT NULL AND effortSlopeJ IS NOT NULL " +
            "AND effortSlopeM IS NOT NULL AND effortSlowS IS NOT NULL ORDER BY endedAtMs DESC",
    )
    fun observeRideEfforts(bikeId: String): Flow<List<RideEffort>>

    // ---- 11b: fuel ----

    @Query("SELECT * FROM refuels WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC")
    fun observeRefuels(bikeId: String): Flow<List<RefuelRecord>>

    @Insert
    suspend fun insertRefuel(refuel: RefuelRecord): Long

    @Query("SELECT * FROM refuels WHERE id = :id")
    suspend fun refuel(id: Long): RefuelRecord?

    @Update
    suspend fun updateRefuel(refuel: RefuelRecord)

    @Query("DELETE FROM refuels WHERE id = :id")
    suspend fun deleteRefuel(id: Long)

    // ---- 4.8: fuels the rider added ----

    @Query("SELECT * FROM custom_fuels ORDER BY createdAtMs")
    fun observeCustomFuels(): Flow<List<CustomFuel>>

    @Query("SELECT * FROM custom_fuels ORDER BY createdAtMs")
    suspend fun allCustomFuels(): List<CustomFuel>

    @Upsert
    suspend fun upsertCustomFuel(fuel: CustomFuel)

    @Query("DELETE FROM custom_fuels")
    suspend fun deleteAllCustomFuels()

    @Query("SELECT * FROM fuel_prices")
    fun observePrices(): Flow<List<FuelPrice>>

    @Upsert
    suspend fun upsertPrice(price: FuelPrice)

    // ---- 12b: gauge readings and dash km/L ----

    @Query("SELECT * FROM fuel_marks WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC")
    fun observeFuelMarks(bikeId: String): Flow<List<FuelMark>>

    @Insert
    suspend fun insertFuelMark(mark: FuelMark): Long

    @Query("SELECT * FROM dash_kmpl WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC")
    fun observeDashKmPerL(bikeId: String): Flow<List<DashKmPerL>>

    @Insert
    suspend fun insertDashKmPerL(entry: DashKmPerL): Long

    @Query("UPDATE dash_kmpl SET kmPerL = :kmPerL WHERE id = :id")
    suspend fun setDashKmPerL(id: Long, kmPerL: Double)

    @Query("DELETE FROM dash_kmpl WHERE id = :id")
    suspend fun deleteDashEntry(id: Long)

    @Query("DELETE FROM fuel_marks")
    suspend fun deleteAllFuelMarks()

    @Query("DELETE FROM dash_kmpl")
    suspend fun deleteAllDashKmPerL()

    // ---- 11c: services ----

    @Query("SELECT * FROM service_items WHERE bikeId = :bikeId ORDER BY createdAtMs, id")
    fun observeServices(bikeId: String): Flow<List<ServiceItem>>

    @Query("SELECT * FROM service_items WHERE id = :id")
    suspend fun service(id: Long): ServiceItem?

    @Insert
    suspend fun insertService(item: ServiceItem): Long

    @Update
    suspend fun updateService(item: ServiceItem)

    @Query("DELETE FROM service_items WHERE id = :id")
    suspend fun deleteService(id: Long)

    @Query("SELECT * FROM service_records WHERE bikeId = :bikeId ORDER BY timeMs DESC, id DESC")
    fun observeServiceRecords(bikeId: String): Flow<List<ServiceRecord>>

    @Insert
    suspend fun insertServiceRecord(record: ServiceRecord): Long

    /** "Mark done": the history entry and the item's new starting point, together. */
    @Transaction
    suspend fun markDone(item: ServiceItem, record: ServiceRecord) {
        updateService(item)
        insertServiceRecord(record)
    }

    // ---- 11c: the whole Garage, for the backup ----

    @Query("SELECT * FROM bikes ORDER BY createdAtMs")
    suspend fun allBikes(): List<BikeRecord>

    @Query("SELECT * FROM odometer_readings ORDER BY timeMs, id")
    suspend fun allReadings(): List<OdometerReading>

    @Query("SELECT * FROM refuels ORDER BY timeMs, id")
    suspend fun allRefuels(): List<RefuelRecord>

    @Query("SELECT * FROM fuel_prices")
    suspend fun allPrices(): List<FuelPrice>

    @Query("SELECT * FROM service_items ORDER BY createdAtMs, id")
    suspend fun allServices(): List<ServiceItem>

    @Query("SELECT * FROM service_records ORDER BY timeMs, id")
    suspend fun allServiceRecords(): List<ServiceRecord>

    @Query("SELECT * FROM fuel_marks ORDER BY timeMs, id")
    suspend fun allFuelMarks(): List<FuelMark>

    @Query("SELECT * FROM dash_kmpl ORDER BY timeMs, id")
    suspend fun allDashKmPerL(): List<DashKmPerL>

    @Query("DELETE FROM service_items")
    suspend fun deleteAllServices()

    @Query("DELETE FROM service_records")
    suspend fun deleteAllServiceRecords()

    @Query("DELETE FROM refuels")
    suspend fun deleteAllRefuels()

    @Query("DELETE FROM fuel_prices")
    suspend fun deleteAllPrices()

    @Query("DELETE FROM bikes")
    suspend fun deleteAllBikes()

    @Query("DELETE FROM odometer_readings")
    suspend fun deleteAllReadings()

    /** Device tests start from an empty garage. */
    @Transaction
    suspend fun clear() {
        deleteAllFuelMarks()
        deleteAllDashKmPerL()
        deleteAllServiceRecords()
        deleteAllServices()
        deleteAllRefuels()
        deleteAllPrices()
        deleteAllCustomFuels()
        deleteAllReadings()
        deleteAllBikes()
    }
}
