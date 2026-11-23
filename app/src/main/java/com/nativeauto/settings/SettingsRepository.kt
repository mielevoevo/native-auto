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
import com.nativeauto.trip.AUTO_RESUME_HOLD_CHOICES_S
import com.nativeauto.trip.AUTO_START_HOLD_CHOICES_S
import com.nativeauto.trip.AutoStartMode
import com.nativeauto.trip.DEFAULT_AUTO_RESUME_HOLD_S
import com.nativeauto.trip.DEFAULT_AUTO_START_HOLD_S
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.graphics.toArgb
import com.nativeauto.ui.theme.initialAccent
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

    /** 13b: what the app does by itself while open and the bike moves. Off by default, and for every earlier install. */
    val autoStartMode: Flow<AutoStartMode>
    suspend fun setAutoStartMode(mode: AutoStartMode)

    /** 14b+: the call card and its takeover. On by default (what every install had); the rider can opt out. */
    val callControls: Flow<Boolean> get() = flowOf(true)
    suspend fun setCallControls(enabled: Boolean) {}

    /** 13b: seconds at 10 km/h before auto-start acts, one of [AUTO_START_HOLD_CHOICES_S]. */
    val autoStartHoldS: Flow<Int>
    suspend fun setAutoStartHoldS(seconds: Int)

    /** 4.2.1: a paused trip resumes by itself on steady movement (while the app is open). On by default. */
    val autoResume: Flow<Boolean> get() = flowOf(true)
    suspend fun setAutoResume(enabled: Boolean) {}

    /** 4.2.1: seconds over the auto-start speed before auto-resume acts, one of [AUTO_RESUME_HOLD_CHOICES_S]. */
    val autoResumeHoldS: Flow<Int> get() = flowOf(DEFAULT_AUTO_RESUME_HOLD_S)
    suspend fun setAutoResumeHoldS(seconds: Int) {}

    /**
     * 13c: the accent colour, as ARGB. Cyan (what every install had) until [initialiseDefaults] says otherwise,
     * so existing installs keep their look. The defaulted members need no change in the test fakes.
     */
    val accentColor: Flow<Int> get() = flowOf(LEGACY_ACCENT_ARGB)
    suspend fun setAccentColor(argb: Int) {}

    /** 13c: the map's route line colour as ARGB; null follows the accent. */
    val routeColor: Flow<Int?> get() = flowOf(null)
    suspend fun setRouteColor(argb: Int?) {}

    /**
     * 13c: once per install, at app start: gives settings that differ between a fresh install and an existing
     * one their first value (the accent: green when fresh, cyan when anything was saved before).
     */
    suspend fun initialiseDefaults(freshInstall: Boolean) {}

    /**
     * 14b: the dashboard's orientation. Landscape by default, and for every earlier install (no re-setup). The defaulted
     * members need no change in the test fakes.
     */
    val orientationMode: Flow<OrientationMode> get() = flowOf(OrientationMode.LANDSCAPE)
    suspend fun setOrientationMode(mode: OrientationMode) {}

    /** 4.3b: card titles as text (default) or as the pane's icon. Defaulted so the test fakes need no change. */
    val cardTitleStyle: Flow<CardTitleStyle> get() = flowOf(CardTitleStyle.TEXT)
    suspend fun setCardTitleStyle(style: CardTitleStyle) {}

    /** 4.3b: a large, very faint pane icon behind Expanded / Fullscreen cards. Off by default. */
    val paneWatermark: Flow<Boolean> get() = flowOf(false)
    suspend fun setPaneWatermark(enabled: Boolean) {}

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

/** Cyan, the accent of every install from before 13c (kept for them). */
const val LEGACY_ACCENT_ARGB = 0xFF3FC8E0.toInt()

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
    /** 14b: the portrait layout last used; blank = none yet (a portrait start derives it from the landscape one). */
    val portraitPreset: String = "",
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
                portraitPreset = it[PRESET_PORTRAIT] ?: "",
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
            it[PRESET_PORTRAIT] = layout.portraitPreset
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

    override val autoStartMode: Flow<AutoStartMode> =
        // 14b+: "odometer only" was folded into one auto-start; an install that had it keeps starting trips.
        prefs.map { enumOrDefault(it[AUTO_START_MODE], AutoStartMode.OFF).let { m -> if (m == AutoStartMode.ODOMETER_ONLY) AutoStartMode.TRIP else m } }
            .distinctUntilChanged()

    override val callControls: Flow<Boolean> = prefs.map { it[CALL_CONTROLS] ?: true }.distinctUntilChanged()

    override val cardTitleStyle: Flow<CardTitleStyle> =
        prefs.map { enumOrDefault(it[CARD_TITLE_STYLE], CardTitleStyle.TEXT) }.distinctUntilChanged()

    override suspend fun setCardTitleStyle(style: CardTitleStyle) {
        store.edit { it[CARD_TITLE_STYLE] = style.name }
    }

    override val paneWatermark: Flow<Boolean> = prefs.map { it[PANE_WATERMARK] ?: false }.distinctUntilChanged()

    override suspend fun setPaneWatermark(enabled: Boolean) {
        store.edit { it[PANE_WATERMARK] = enabled }
    }

    override suspend fun setCallControls(enabled: Boolean) {
        store.edit { it[CALL_CONTROLS] = enabled }
    }

    override suspend fun setAutoStartMode(mode: AutoStartMode) {
        store.edit { it[AUTO_START_MODE] = mode.name }
    }

    override val autoStartHoldS: Flow<Int> =
        prefs.map { p -> p[AUTO_START_HOLD_S]?.takeIf { it in AUTO_START_HOLD_CHOICES_S } ?: DEFAULT_AUTO_START_HOLD_S }.distinctUntilChanged()

    override suspend fun setAutoStartHoldS(seconds: Int) {
        store.edit { it[AUTO_START_HOLD_S] = seconds }
    }

    override val autoResume: Flow<Boolean> = prefs.map { it[AUTO_RESUME] ?: true }.distinctUntilChanged()

    override suspend fun setAutoResume(enabled: Boolean) {
        store.edit { it[AUTO_RESUME] = enabled }
    }

    override val autoResumeHoldS: Flow<Int> =
        prefs.map { p -> p[AUTO_RESUME_HOLD_S]?.takeIf { it in AUTO_RESUME_HOLD_CHOICES_S } ?: DEFAULT_AUTO_RESUME_HOLD_S }.distinctUntilChanged()

    override suspend fun setAutoResumeHoldS(seconds: Int) {
        store.edit { it[AUTO_RESUME_HOLD_S] = seconds }
    }

    override val accentColor: Flow<Int> = prefs.map { it[ACCENT] ?: LEGACY_ACCENT_ARGB }.distinctUntilChanged()

    override suspend fun setAccentColor(argb: Int) {
        store.edit { it[ACCENT] = argb }
    }

    override val routeColor: Flow<Int?> = prefs.map { it[ROUTE_COLOR] }.distinctUntilChanged()

    override suspend fun setRouteColor(argb: Int?) {
        store.edit { if (argb == null) it.remove(ROUTE_COLOR) else it[ROUTE_COLOR] = argb }
    }

    override suspend fun initialiseDefaults(freshInstall: Boolean) {
        store.initialiseAccent(freshInstall)
    }

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

    override val orientationMode: Flow<OrientationMode> =
        prefs.map { enumOrDefault(it[ORIENTATION_MODE], OrientationMode.LANDSCAPE) }.distinctUntilChanged()

    override suspend fun setOrientationMode(mode: OrientationMode) {
        store.edit { it[ORIENTATION_MODE] = mode.name }
    }

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
        val PRESET_PORTRAIT = stringPreferencesKey("layout_preset_portrait")
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val AUTO_LAYOUT = booleanPreferencesKey("auto_layout")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CLOCK_FACE = stringPreferencesKey("clock_face")
        val GARAGE_VALUE = stringPreferencesKey("garage_value")
        val TOTAL_PERIOD = stringPreferencesKey("total_period")
        val TANK_ICON = stringPreferencesKey("tank_icon")
        val ACCENT = intPreferencesKey("accent_color")
        val ROUTE_COLOR = intPreferencesKey("route_color")
        val CALL_CONTROLS = booleanPreferencesKey("call_controls")
        val CARD_TITLE_STYLE = stringPreferencesKey("card_title_style")
        val PANE_WATERMARK = booleanPreferencesKey("pane_watermark")
        val AUTO_START_MODE = stringPreferencesKey("auto_start_mode")
        val AUTO_START_HOLD_S = intPreferencesKey("auto_start_hold_s")
        val AUTO_RESUME = booleanPreferencesKey("auto_resume")
        val AUTO_RESUME_HOLD_S = intPreferencesKey("auto_resume_hold_s")
        val REFRESH_MODE = stringPreferencesKey("refresh_mode")
        val DRAG_HOLD_MS = longPreferencesKey("drag_hold_ms")
        val CLOCK_SECONDS = booleanPreferencesKey("clock_seconds")
    }
}

private val ACCENT_KEY = intPreferencesKey("accent_color")

/**
 * 13c: gives the accent its first value, once. A fresh install (no saved preferences and installed new) starts
 * green; anything else (an update over an older build, or preferences already saved) keeps cyan, the look it had
 * until now. An accent already stored is never touched. `internal` so a JVM test can run it on a temp DataStore.
 */
internal suspend fun DataStore<Preferences>.initialiseAccent(freshInstall: Boolean) {
    edit { p ->
        if (ACCENT_KEY !in p) {
            p[ACCENT_KEY] = initialAccent(hasSavedPreferences = p.asMap().isNotEmpty(), freshInstall = freshInstall).dark.toArgb()
        }
    }
}
