package com.nativeauto.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** User preferences. An interface so ViewModel tests can swap in an in-memory fake. */
interface SettingsRepository {
    val speedUnit: Flow<SpeedUnit>
    suspend fun setSpeedUnit(unit: SpeedUnit)

    /** Debug builds only: play the scripted drive instead of the real GPS. Off by default. */
    val useFakeGps: Flow<Boolean>
    suspend fun setUseFakeGps(enabled: Boolean)

    /** Live traffic on the map. Off by default: on a dark map it's extra visual noise. */
    val showTraffic: Flow<Boolean>
    suspend fun setShowTraffic(enabled: Boolean)

    /** The small battery reading in the top-right header. On by default. */
    val showBattery: Flow<Boolean>
    suspend fun setShowBattery(enabled: Boolean)

    /** Spoken turn-by-turn prompts while navigating. On by default (helmet headsets). */
    val voiceGuidance: Flow<Boolean>
    suspend fun setVoiceGuidance(enabled: Boolean)

    /** Debug builds only: navigation drives itself along the route instead of reading the GPS. */
    val simulateNavigation: Flow<Boolean>
    suspend fun setSimulateNavigation(enabled: Boolean)

    /** Layout to start from on a fresh launch (§9 Phase 7). The session state lives in rememberSaveable. */
    val savedLayout: Flow<SavedLayout>
    suspend fun saveLayout(layout: SavedLayout)

    /** Bring the map on screen when navigation starts, and put the layout back after. On by default. */
    val autoLayout: Flow<Boolean>
    suspend fun setAutoLayout(enabled: Boolean)

    /** Dark (OLED, default), light, or follow the system. */
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** The clock pane's face; digital by default. */
    val clockFace: Flow<ClockFace>
    suspend fun setClockFace(face: ClockFace)

    /** 11c: what the normal-size Garage card shows. */
    val garageValue: Flow<GarageValue>
    suspend fun setGarageValue(value: GarageValue)

    /** 13a: the Garage card's tank icon. Gauge by default (and for installs from before it existed). */
    val tankIcon: Flow<TankIcon>
    suspend fun setTankIcon(icon: TankIcon)

    /** 12b: the period of the log face's distance total. */
    val totalPeriod: Flow<TotalPeriod>
    suspend fun setTotalPeriod(period: TotalPeriod)

    /** 12a: refresh-rate policy (R4). Auto by default. */
    val refreshMode: Flow<RefreshMode>
    suspend fun setRefreshMode(mode: RefreshMode)

    /** 12a: hold time before a card lifts for dragging (R3), one of [DRAG_HOLD_CHOICES_MS]. */
    val dragHoldMs: Flow<Long>
    suspend fun setDragHoldMs(ms: Long)

    /** The analog face's second hand. Off by default: it redraws every second. */
    val clockSeconds: Flow<Boolean>
    suspend fun setClockSeconds(enabled: Boolean)

    /** Auto-pause after this many minutes below walking pace; 0 = off (9a, default 5). */
    val autoPauseMinutes: Flow<Int>
    suspend fun setAutoPauseMinutes(minutes: Int)

    /** Auto-end (and save) after this many minutes paused; 0 = off (9a, default 30). */
    val autoEndMinutes: Flow<Int>
    suspend fun setAutoEndMinutes(minutes: Int)

    /** Figures hidden from the log face, summary and history detail (9c). Recording is unaffected. */
    val hiddenStats: Flow<Set<TelemetryStat>>
    suspend fun setStatShown(stat: TelemetryStat, shown: Boolean)
}

const val DEFAULT_AUTO_PAUSE_MIN = 5
const val DEFAULT_AUTO_END_MIN = 30

/**
 * The persisted part of the dashboard layout. Modes (fullscreen / maximized) and auto-layout
 * overrides are session-only. [preset] and [slots] are kept as text so this layer doesn't
 * depend on the UI's enums; `ui/dashboard/LayoutCodec` reads them, falling back to defaults.
 */
data class SavedLayout(
    /** Duo: the tall pane on the left (Phase 7's "map on the left"). */
    val bigOnLeft: Boolean,
    /** Duo: the tall pane's share of the width. */
    val split: Float,
    /** The layout's name; blank means the default (Duo). */
    val preset: String = "",
    /** Panes per layout, `DUO=MAP,MEDIA,SPEED;QUAD=…`; blank or missing entries mean defaults. */
    val slots: String = "",
) {
    companion object {
        val Default = SavedLayout(bigOnLeft = true, split = 0.5f)
    }
}

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {

    private val store = context.settingsDataStore

    private val prefs: Flow<Preferences> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val speedUnit: Flow<SpeedUnit> = prefs
        .map { prefs -> prefs[SPEED_UNIT]?.let { name -> SpeedUnit.entries.firstOrNull { it.name == name } } ?: SpeedUnit.KMH }
        .distinctUntilChanged()

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        store.edit { it[SPEED_UNIT] = unit.name }
    }

    override val useFakeGps: Flow<Boolean> = prefs.map { it[USE_FAKE_GPS] ?: false }.distinctUntilChanged()

    override suspend fun setUseFakeGps(enabled: Boolean) {
        store.edit { it[USE_FAKE_GPS] = enabled }
    }

    override val showTraffic: Flow<Boolean> = prefs.map { it[SHOW_TRAFFIC] ?: false }.distinctUntilChanged()

    override suspend fun setShowTraffic(enabled: Boolean) {
        store.edit { it[SHOW_TRAFFIC] = enabled }
    }

    override val showBattery: Flow<Boolean> = prefs.map { it[SHOW_BATTERY] ?: true }.distinctUntilChanged()

    override suspend fun setShowBattery(enabled: Boolean) {
        store.edit { it[SHOW_BATTERY] = enabled }
    }

    override val voiceGuidance: Flow<Boolean> = prefs.map { it[VOICE_GUIDANCE] ?: true }.distinctUntilChanged()

    override suspend fun setVoiceGuidance(enabled: Boolean) {
        store.edit { it[VOICE_GUIDANCE] = enabled }
    }

    override val simulateNavigation: Flow<Boolean> = prefs.map { it[SIMULATE_NAVIGATION] ?: false }.distinctUntilChanged()

    override suspend fun setSimulateNavigation(enabled: Boolean) {
        store.edit { it[SIMULATE_NAVIGATION] = enabled }
    }

    override val savedLayout: Flow<SavedLayout> = prefs
        .map {
            SavedLayout(
                bigOnLeft = it[MAP_ON_LEFT] ?: SavedLayout.Default.bigOnLeft,
                split = it[SPLIT] ?: SavedLayout.Default.split,
                preset = it[PRESET] ?: SavedLayout.Default.preset,
                // Phase 7 kept only the Duo card order; carry it over until slots are first saved.
                slots = it[SLOTS] ?: if (it[MEDIA_ON_TOP] == false) "DUO=MAP,SPEED,MEDIA" else SavedLayout.Default.slots,
            )
        }
        .distinctUntilChanged()

    override suspend fun saveLayout(layout: SavedLayout) {
        store.edit {
            it[MAP_ON_LEFT] = layout.bigOnLeft
            it[SPLIT] = layout.split
            it[PRESET] = layout.preset
            it[SLOTS] = layout.slots
            it.remove(MEDIA_ON_TOP)
        }
    }

    override val autoLayout: Flow<Boolean> = prefs.map { it[AUTO_LAYOUT] ?: true }.distinctUntilChanged()

    override suspend fun setAutoLayout(enabled: Boolean) {
        store.edit { it[AUTO_LAYOUT] = enabled }
    }

    override val themeMode: Flow<ThemeMode> =
        prefs.map { enumOrDefault(it[THEME_MODE], ThemeMode.DARK) }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME_MODE] = mode.name }
    }

    override val clockFace: Flow<ClockFace> =
        prefs.map { enumOrDefault(it[CLOCK_FACE], ClockFace.DIGITAL) }.distinctUntilChanged()

    override suspend fun setClockFace(face: ClockFace) {
        store.edit { it[CLOCK_FACE] = face.name }
    }

    override val garageValue: Flow<GarageValue> =
        prefs.map { enumOrDefault(it[GARAGE_VALUE], GarageValue.RANGE) }.distinctUntilChanged()

    override val tankIcon: Flow<TankIcon> =
        prefs.map { enumOrDefault(it[TANK_ICON], TankIcon.GAUGE) }.distinctUntilChanged()

    override suspend fun setTankIcon(icon: TankIcon) {
        store.edit { it[TANK_ICON] = icon.name }
    }

    override val totalPeriod: Flow<TotalPeriod> =
        prefs.map { enumOrDefault(it[TOTAL_PERIOD], TotalPeriod.DAY) }.distinctUntilChanged()

    override suspend fun setTotalPeriod(period: TotalPeriod) {
        store.edit { it[TOTAL_PERIOD] = period.name }
    }

    override suspend fun setGarageValue(value: GarageValue) {
        store.edit { it[GARAGE_VALUE] = value.name }
    }

    override val refreshMode: Flow<RefreshMode> =
        prefs.map { enumOrDefault(it[REFRESH_MODE], RefreshMode.AUTO) }.distinctUntilChanged()

    override suspend fun setRefreshMode(mode: RefreshMode) {
        store.edit { it[REFRESH_MODE] = mode.name }
    }

    override val dragHoldMs: Flow<Long> =
        prefs.map { p -> p[DRAG_HOLD_MS]?.takeIf { it in DRAG_HOLD_CHOICES_MS } ?: DEFAULT_DRAG_HOLD_MS }.distinctUntilChanged()

    override suspend fun setDragHoldMs(ms: Long) {
        store.edit { it[DRAG_HOLD_MS] = ms }
    }

    override val clockSeconds: Flow<Boolean> = prefs.map { it[CLOCK_SECONDS] ?: false }.distinctUntilChanged()

    override suspend fun setClockSeconds(enabled: Boolean) {
        store.edit { it[CLOCK_SECONDS] = enabled }
    }

    override val autoPauseMinutes: Flow<Int> =
        prefs.map { it[AUTO_PAUSE_MIN] ?: DEFAULT_AUTO_PAUSE_MIN }.distinctUntilChanged()

    override suspend fun setAutoPauseMinutes(minutes: Int) {
        store.edit { it[AUTO_PAUSE_MIN] = minutes.coerceAtLeast(0) }
    }

    override val autoEndMinutes: Flow<Int> =
        prefs.map { it[AUTO_END_MIN] ?: DEFAULT_AUTO_END_MIN }.distinctUntilChanged()

    override suspend fun setAutoEndMinutes(minutes: Int) {
        store.edit { it[AUTO_END_MIN] = minutes.coerceAtLeast(0) }
    }

    override val hiddenStats: Flow<Set<TelemetryStat>> =
        prefs.map { p -> p[HIDDEN_STATS]?.let(TelemetryStat::fromKeys) ?: TelemetryStat.DEFAULT_HIDDEN }.distinctUntilChanged()

    override suspend fun setStatShown(stat: TelemetryStat, shown: Boolean) {
        store.edit { p ->
            val hidden = p[HIDDEN_STATS]?.let(TelemetryStat::fromKeys) ?: TelemetryStat.DEFAULT_HIDDEN
            p[HIDDEN_STATS] = (if (shown) hidden - stat else hidden + stat).mapTo(mutableSetOf()) { it.key }
        }
    }

    private companion object {
        val HIDDEN_STATS = stringSetPreferencesKey("hidden_stats")
        val AUTO_PAUSE_MIN = intPreferencesKey("auto_pause_min")
        val AUTO_END_MIN = intPreferencesKey("auto_end_min")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val USE_FAKE_GPS = booleanPreferencesKey("use_fake_gps")
        val SHOW_TRAFFIC = booleanPreferencesKey("show_traffic")
        val SHOW_BATTERY = booleanPreferencesKey("show_battery")
        val VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val SIMULATE_NAVIGATION = booleanPreferencesKey("simulate_navigation")
        val MAP_ON_LEFT = booleanPreferencesKey("layout_map_on_left")
        val MEDIA_ON_TOP = booleanPreferencesKey("layout_media_on_top")
        val SPLIT = floatPreferencesKey("layout_split")
        val PRESET = stringPreferencesKey("layout_preset")
        val SLOTS = stringPreferencesKey("layout_slots")
        val AUTO_LAYOUT = booleanPreferencesKey("auto_layout")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CLOCK_FACE = stringPreferencesKey("clock_face")
        val GARAGE_VALUE = stringPreferencesKey("garage_value")
        val TOTAL_PERIOD = stringPreferencesKey("total_period")
        val TANK_ICON = stringPreferencesKey("tank_icon")
        val REFRESH_MODE = stringPreferencesKey("refresh_mode")
        val DRAG_HOLD_MS = longPreferencesKey("drag_hold_ms")
        val CLOCK_SECONDS = booleanPreferencesKey("clock_seconds")
    }
}
