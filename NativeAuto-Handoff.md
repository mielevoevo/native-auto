# Native Auto: Engineering Handoff

> **Audience:** Claude Code (terminal).
> **Status:** Architecture approved. Start at Phase 0 and work through the phases in order. Each phase has a Definition of Done; don't start the next phase until it passes.

---

## 1. Product summary

Native Auto is a phone-only, car-mounted dashboard inspired by Headunit Reloaded. Unlike Headunit Reloaded, it does **not** virtualize or project Android Auto. Every pane is a native Compose component, which keeps CPU, RAM and battery use low.

### Screen layout (default, landscape)

```
┌──────────────────────┬──────────────────────┐
│                      │   Spotify / Media    │
│                      │   (MediaSession)     │
│     Google Map       ├──────────────────────┤
│                      │   GPS Speedometer    │
│                      │   (speed / telemetry)│
└──────────────────────┴──────────────────────┘
```

### Required interactions

| Interaction | Effect |
|---|---|
| Maximize Media or Speed | The card fills the whole right column; the other card collapses |
| Fullscreen any pane | Map, Media or Speed takes over the entire screen |
| Swap sides | The map moves to the right and the card column to the left (and back) |
| Swap cards | Media and Speed trade top/bottom positions |
| Back | Unwinds one level: fullscreen → maximized → default → system back |
| Speed card compact | Shows big GPS speed only |
| Speed card expanded or fullscreen | Shows full telemetry: current, max, average, moving average, elapsed time, distance |

### Non-goals (v1)

- No Android Auto projection or virtualization.
- No turn-by-turn navigation. The Maps SDK draws a map only; the Navigation SDK has separate terms and is a later decision.
- No portrait layout tuning beyond "doesn't crash". The target is a landscape car mount.

---

## 2. Tech stack

Use the **latest stable** versions via `gradle/libs.versions.toml`. Don't pin versions from memory; check the current stable releases.

- Kotlin, Jetpack Compose (Compose BOM), Material 3
- `minSdk 26` (needed for `Location.speedAccuracyMetersPerSecond`); `targetSdk`/`compileSdk` = latest stable
- Hilt for DI
- Coroutines + Flow; `lifecycle-runtime-compose` (`collectAsStateWithLifecycle`)
- `com.google.maps.android:maps-compose` + `play-services-maps`
- `play-services-location` (FusedLocationProviderClient)
- `androidx.datastore:datastore-preferences` (speed unit, persisted layout prefs)
- `com.google.android.libraries.mapsplatform.secrets-gradle-plugin` (reads `MAPS_API_KEY` from `local.properties`)
- Tests: JUnit, Turbine, kotlinx-coroutines-test, Compose UI test

---

## 3. Folder structure (exact)

Package / applicationId: **`com.nativeauto`**

```
NativeAuto/
├── HANDOFF.md                         ← this file
├── CLAUDE.md                          ← short pointer: "Read HANDOFF.md first"
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── local.properties                   ← MAPS_API_KEY=... (never committed)
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/ (values/strings.xml, values/themes.xml, xml/…)
        │   └── java/com/nativeauto/
        │       ├── NativeAutoApp.kt                 @HiltAndroidApp
        │       ├── MainActivity.kt                  edge-to-edge, immersive, keep-screen-on
        │       ├── di/
        │       │   ├── AppModule.kt                 @ApplicationScope CoroutineScope, clocks
        │       │   └── LocationModule.kt            binds SpeedSampleSource (real or fake)
        │       ├── core/
        │       │   └── MonotonicClock.kt            interface + SystemMonotonicClock
        │       ├── ui/
        │       │   ├── theme/
        │       │   │   ├── Color.kt
        │       │   │   ├── Theme.kt                 dark-first theme
        │       │   │   └── Type.kt
        │       │   ├── dashboard/
        │       │   │   ├── DashboardScreen.kt       ★ CORE FILE: Row/Column/weight hierarchy
        │       │   │   ├── DashboardLayoutState.kt  ★ CORE STATE: intent → weights
        │       │   │   ├── PaneSlot.kt              clipped, padded weighted slot
        │       │   │   └── AnimatedWeight.kt        animateWeight() + MIN_WEIGHT
        │       │   ├── components/
        │       │   │   └── CardChrome.kt            card header: title, maximize, fullscreen, swap
        │       │   ├── map/
        │       │   │   └── MapPane.kt
        │       │   ├── media/
        │       │   │   ├── MediaCard.kt
        │       │   │   └── MediaViewModel.kt
        │       │   ├── speed/
        │       │   │   ├── SpeedCard.kt
        │       │   │   ├── SpeedometerUiState.kt
        │       │   │   └── SpeedometerViewModel.kt
        │       │   └── permissions/
        │       │       └── PermissionsGate.kt       location + notification-access onboarding
        │       ├── trip/                            NO android.* imports (pure Kotlin)
        │       │   ├── SpeedSample.kt
        │       │   ├── TripStats.kt                 + derived extension functions
        │       │   ├── TripReducer.kt
        │       │   ├── TripTracker.kt
        │       │   └── Geo.kt                       haversineMeters()
        │       ├── location/
        │       │   ├── SpeedSampleSource.kt         interface: val samples: Flow<SpeedSample>
        │       │   ├── FusedSpeedSampleSource.kt    real GPS (callbackFlow)
        │       │   ├── FakeSpeedSampleSource.kt     scripted drive for emulator/dev
        │       │   └── TripService.kt               foreground service, type=location
        │       ├── media/
        │       │   ├── MediaListenerService.kt      NotificationListenerService (enables session access)
        │       │   ├── MediaSessionRepository.kt    StateFlow<NowPlaying?>, transport controls
        │       │   └── NowPlaying.kt
        │       └── settings/
        │           ├── SpeedUnit.kt                 KMH (default), MPH
        │           └── SettingsRepository.kt        DataStore
        ├── test/java/com/nativeauto/
        │   ├── trip/TripReducerTest.kt
        │   └── ui/dashboard/DashboardLayoutStateTest.kt
        └── androidTest/java/com/nativeauto/
            └── ui/dashboard/DashboardScreenTest.kt
```

**Core files:** `ui/dashboard/DashboardScreen.kt` (composable `DashboardScreen`) and `ui/dashboard/DashboardLayoutState.kt` (class `DashboardLayoutState`, `rememberDashboardLayoutState()`).

---

## 4. Architecture rules (non-negotiable)

1. **Store intent, derive weights.** Layout state is `fullscreen`, `maximized`, `mapOnLeft`, `mediaOnTop` and `split`. The weights are computed with `derivedStateOf`. Never store four independent weight variables.
2. **Never call `Modifier.weight(0f)`; it throws.** Animate toward 0 and clamp to `MIN_WEIGHT = 0.0001f`.
3. **Never remove a pane from composition to hide it.** Hidden panes stay composed at ~0 px. Removing the map would reload tiles and the camera.
4. **Every pane is wrapped in `movableContentOf`**, so swapping sides or cards moves the content instead of recreating it.
5. **Gutters live on `PaneSlot` padding, not `Arrangement.spacedBy`.** `spacedBy` leaves a stray gap beside a fullscreen pane.
6. **Card rendering is driven by `layout.modeOf(pane)`** (`Compact` / `Expanded` / `Fullscreen`), never by measured size. That avoids flicker mid-animation.
7. **SI units everywhere** (m/s, metres, nanoseconds). Convert to km/h or mph only in `SpeedometerViewModel`.
8. **Time comes from `elapsedRealtimeNanos`** (monotonic), never the wall clock. Inject `MonotonicClock` so it can be faked in tests.
9. **`trip/` is pure Kotlin**, with no `android.*` imports, so it is unit-testable on the JVM.
10. **Trip tracking survives the UI.** `TripTracker` is a singleton driven by `TripService` (foreground, `location` type). ViewModels only observe it.
11. **Average speed = distance ÷ time.** Never average speed samples.

---

## 5. Layout specification

### 5.1 Weight table

"Kept" means the value is unchanged while the column is hidden, so the column reappears as the user left it.

| Mode | map | cards | media | speed |
|---|---|---|---|---|
| Default split | `split` (0.5) | `1 - split` | 0.5 | 0.5 |
| Media maximized | split | 1 - split | 1.0 | 0.0 |
| Speed maximized | split | 1 - split | 0.0 | 1.0 |
| Map fullscreen | 1.0 | 0.0 | kept | kept |
| Media fullscreen | 0.0 | 1.0 | 1.0 | 0.0 |
| Speed fullscreen | 0.0 | 1.0 | 0.0 | 1.0 |

Swaps change **child order**, not weights.

### 5.2 Hierarchy

```
Row (fillMaxSize)                          ← child order flips with mapOnLeft
├── PaneSlot  weight(map)        → MapPane
└── Column    weight(cards)                ← child order flips with mediaOnTop
    ├── PaneSlot weight(media)   → MediaCard
    └── PaneSlot weight(speed)   → SpeedCard
```

### 5.3 Reference: `DashboardLayoutState.kt`

```kotlin
enum class Pane { MAP, MEDIA, SPEED }
enum class CardMode { Compact, Expanded, Fullscreen }

@Immutable
data class PaneWeights(val map: Float, val cards: Float, val media: Float, val speed: Float)

@Stable
class DashboardLayoutState(
    fullscreen: Pane? = null,
    maximized: Pane? = null,
    mapOnLeft: Boolean = true,
    mediaOnTop: Boolean = true,
    split: Float = 0.5f,
) {
    var fullscreen by mutableStateOf(fullscreen); private set
    var maximized  by mutableStateOf(maximized);  private set
    var mapOnLeft  by mutableStateOf(mapOnLeft);  private set
    var mediaOnTop by mutableStateOf(mediaOnTop); private set
    var split      by mutableFloatStateOf(split); private set

    val weights: PaneWeights by derivedStateOf {
        val (map, cards) = when (fullscreen) {
            Pane.MAP -> 1f to 0f
            Pane.MEDIA, Pane.SPEED -> 0f to 1f
            null -> split to (1f - split)
        }
        val focus = fullscreen?.takeIf { it != Pane.MAP } ?: maximized
        val (media, speed) = when (focus) {
            Pane.MEDIA -> 1f to 0f
            Pane.SPEED -> 0f to 1f
            else -> 0.5f to 0.5f
        }
        PaneWeights(map, cards, media, speed)
    }

    fun modeOf(pane: Pane): CardMode = when {
        fullscreen == pane -> CardMode.Fullscreen
        fullscreen == null && maximized == pane -> CardMode.Expanded
        else -> CardMode.Compact
    }

    fun toggleFullscreen(pane: Pane) { fullscreen = if (fullscreen == pane) null else pane }
    fun toggleMaximized(pane: Pane) {
        require(pane != Pane.MAP) { "Only right-column cards can be maximized" }
        maximized = if (maximized == pane) null else pane
    }
    fun swapSides() { mapOnLeft = !mapOnLeft }
    fun swapCards() { mediaOnTop = !mediaOnTop }
    fun setSplit(fraction: Float) { split = fraction.coerceIn(0.3f, 0.7f) }

    /** Returns true if it consumed the back press. */
    fun back(): Boolean = when {
        fullscreen != null -> { fullscreen = null; true }
        maximized != null  -> { maximized = null; true }
        else -> false
    }

    companion object {
        val Saver = listSaver<DashboardLayoutState, Any?>(
            save = { listOf(it.fullscreen?.name, it.maximized?.name, it.mapOnLeft, it.mediaOnTop, it.split) },
            restore = {
                DashboardLayoutState(
                    fullscreen = (it[0] as String?)?.let(Pane::valueOf),
                    maximized  = (it[1] as String?)?.let(Pane::valueOf),
                    mapOnLeft  = it[2] as Boolean,
                    mediaOnTop = it[3] as Boolean,
                    split      = it[4] as Float,
                )
            },
        )
    }
}

@Composable
fun rememberDashboardLayoutState() =
    rememberSaveable(saver = DashboardLayoutState.Saver) { DashboardLayoutState() }
```

### 5.4 Reference: `AnimatedWeight.kt` + `PaneSlot.kt`

```kotlin
internal const val MIN_WEIGHT = 0.0001f

@Composable
internal fun animateWeight(target: Float, label: String): Float {
    val value by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = label,
    )
    return value.coerceAtLeast(MIN_WEIGHT)
}

@Composable
internal fun PaneSlot(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds().padding(4.dp)) { content() }
}
```

### 5.5 Reference: `DashboardScreen.kt` skeleton

```kotlin
@Composable
fun DashboardScreen(layout: DashboardLayoutState = rememberDashboardLayoutState()) {
    val w = layout.weights
    val mapW   = animateWeight(w.map, "map")
    val cardsW = animateWeight(w.cards, "cards")
    val mediaW = animateWeight(w.media, "media")
    val speedW = animateWeight(w.speed, "speed")

    val map = remember { movableContentOf { MapPane(layout) } }
    val media = remember { movableContentOf { MediaCard(layout.modeOf(Pane.MEDIA), layout) } }
    val speed = remember { movableContentOf { SpeedCard(layout.modeOf(Pane.SPEED), layout) } }

    BackHandler(enabled = layout.fullscreen != null || layout.maximized != null) { layout.back() }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val mapSlot: @Composable () -> Unit = {
            PaneSlot(Modifier.weight(mapW).fillMaxHeight()) { map() }
        }
        val cardsSlot: @Composable () -> Unit = {
            Column(Modifier.weight(cardsW).fillMaxHeight()) {
                val mediaSlot: @Composable () -> Unit = {
                    PaneSlot(Modifier.weight(mediaW).fillMaxWidth()) { media() }
                }
                val speedSlot: @Composable () -> Unit = {
                    PaneSlot(Modifier.weight(speedW).fillMaxWidth()) { speed() }
                }
                if (layout.mediaOnTop) { mediaSlot(); speedSlot() } else { speedSlot(); mediaSlot() }
            }
        }
        if (layout.mapOnLeft) { mapSlot(); cardsSlot() } else { cardsSlot(); mapSlot() }
    }
}
```

The `movableContentOf` lambdas must read layout values through the `layout` object, which holds snapshot state. Don't capture plain values at `remember` time; they would go stale.

---

## 6. Speedometer data model

### 6.1 Flow

```
SpeedSampleSource.samples ─▶ TripReducer.reduce (pure) ─▶ TripTracker.stats: StateFlow<TripStats>
   (Fused or Fake)                                              │  (singleton, run by TripService)
                                  1 Hz ticker ──combine──▶ SpeedometerViewModel.ui: StateFlow<SpeedometerUiState>
```

### 6.2 Reference: models (`trip/`)

```kotlin
data class SpeedSample(
    val timestampNanos: Long,        // Location.elapsedRealtimeNanos
    val speedMps: Float,
    val speedAccuracyMps: Float?,
    val horizontalAccuracyM: Float,
    val latitude: Double,
    val longitude: Double,
)

enum class GpsStatus { Searching, Fix, Stale, Disabled, PermissionDenied }

data class TripStats(
    val startedAtNanos: Long? = null,
    val lastSample: SpeedSample? = null,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val distanceM: Double = 0.0,
    val movingNanos: Long = 0L,
    val gpsStatus: GpsStatus = GpsStatus.Searching,
)

fun TripStats.elapsedNanos(nowNanos: Long): Long = startedAtNanos?.let { nowNanos - it } ?: 0L

fun TripStats.averageSpeedMps(nowNanos: Long): Float {
    val sec = elapsedNanos(nowNanos) / 1e9
    return if (sec < 1.0) 0f else (distanceM / sec).toFloat()
}

fun TripStats.movingAverageMps(): Float {
    val sec = movingNanos / 1e9
    return if (sec < 1.0) 0f else (distanceM / sec).toFloat()
}
```

### 6.3 Reference: `TripReducer.kt`

```kotlin
object TripReducer {
    const val STATIONARY_MPS = 0.8f              // ~3 km/h; below = jitter → 0
    const val MAX_ACCURACY_M = 25f
    const val MAX_ACCEL_MPS2 = 12f               // spike filter for max speed
    const val MAX_GAP_NANOS = 5_000_000_000L     // tunnel → straight-line distance

    fun reduce(s: TripStats, fix: SpeedSample): TripStats {
        if (fix.horizontalAccuracyM > MAX_ACCURACY_M) return s.copy(gpsStatus = GpsStatus.Searching)

        val speed = if (fix.speedMps < STATIONARY_MPS) 0f else fix.speedMps
        val prev = s.lastSample ?: return s.copy(
            startedAtNanos = s.startedAtNanos ?: fix.timestampNanos,
            lastSample = fix, currentSpeedMps = speed, gpsStatus = GpsStatus.Fix,
        )

        val dtNanos = fix.timestampNanos - prev.timestampNanos
        if (dtNanos <= 0L) return s
        val dtSec = dtNanos / 1e9

        val dDist = if (dtNanos > MAX_GAP_NANOS) haversineMeters(prev, fix)
                    else (s.currentSpeedMps + speed) / 2.0 * dtSec

        val plausible = abs(speed - s.currentSpeedMps) / dtSec <= MAX_ACCEL_MPS2
        val accurate = fix.speedAccuracyMps?.let { it < 1.5f } ?: true

        return s.copy(
            lastSample = fix,
            currentSpeedMps = speed,
            maxSpeedMps = if (plausible && accurate) max(s.maxSpeedMps, speed) else s.maxSpeedMps,
            distanceM = s.distanceM + dDist,
            movingNanos = s.movingNanos + if (speed > 0f) dtNanos else 0L,
            gpsStatus = GpsStatus.Fix,
        )
    }
}
```

### 6.4 `TripTracker`, ViewModel and UI state

- `TripTracker(source: SpeedSampleSource, clock: MonotonicClock, @ApplicationScope scope)` exposes `stats: StateFlow<TripStats>` and the actions `start()`, `stop()` and `reset()`.
- `start()` sets `startedAtNanos = clock.nowNanos()` if it is null, then collects `source.samples` into `_stats.update { TripReducer.reduce(it, fix) }`.
- `SpeedometerViewModel` combines `tracker.stats`, a 1 Hz ticker of `clock.nowNanos()`, and `settings.speedUnit`. It uses `stateIn(WhileSubscribed(5_000))`.
- **Stale rule:** if `now - lastSample.timestampNanos > 3 s`, set `gps = Stale` and `speed = null`, so the UI shows "--".

```kotlin
data class SpeedometerUiState(
    val speed: Int?,          // null → "--"
    val maxSpeed: Int,
    val avgSpeed: Int,
    val movingAvgSpeed: Int,
    val elapsed: Duration,
    val distance: Double,     // km or mi per unit
    val unit: SpeedUnit,
    val gps: GpsStatus,
    val isTracking: Boolean,
) { companion object { val Empty = SpeedometerUiState(null, 0, 0, 0, Duration.ZERO, 0.0, SpeedUnit.KMH, GpsStatus.Searching, false) } }
```

---

## 7. Media (Spotify via MediaSession)

- `MediaListenerService : NotificationListenerService`. Declare it in the manifest with `BIND_NOTIFICATION_LISTENER_SERVICE`. The user must grant **Notification access** in system settings; deep-link there with `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- `MediaSessionRepository`:
  - `MediaSessionManager.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java))` plus `addOnActiveSessionsChangedListener`.
  - Prefer the controller with package `com.spotify.music`; otherwise use the first session that is playing.
  - Register a `MediaController.Callback` and map it to `StateFlow<NowPlaying?>`.
  - Expose `play()`, `pause()`, `next()`, `previous()` and `seekTo()` via `transportControls`.
- `NowPlaying(title, artist, album, art: ImageBitmap?, isPlaying, positionMs, durationMs, packageName)`.
- To compute the live position: `state.position + (elapsedRealtime - state.lastPositionUpdateTime) * state.playbackSpeed`. Tick it in the UI only while playing.
- If access isn't granted, `MediaCard` shows a "Grant notification access" call to action, not an error.

---

## 8. Manifest and permissions

```
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION      (Android 14+ requirement)
POST_NOTIFICATIONS                                   (Android 13+, for the FGS notification)
INTERNET                                             (maps)
```

- `<service android:name=".location.TripService" android:foregroundServiceType="location" android:exported="false"/>`
- `<service android:name=".media.MediaListenerService" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" android:exported="true">` with the `android.service.notification.NotificationListenerService` intent filter.
- `<meta-data android:name="com.google.android.geo.API_KEY" android:value="${MAPS_API_KEY}"/>`
- `MainActivity`: `android:screenOrientation="sensorLandscape"`. In code: `enableEdgeToEdge()`, hide system bars (immersive sticky) and `FLAG_KEEP_SCREEN_ON`.

---

## 9. Implementation order

Build the visual scaffold first with placeholders, then wire in real data one pane at a time.

### Phase 0: Project setup
1. Create the Gradle project with the structure in §3 (empty files are fine for later phases).
2. Add the version catalog and dependencies (§2), Hilt plugin, secrets plugin and Compose compiler plugin.
3. Create `NativeAutoApp` (`@HiltAndroidApp`) and `MainActivity` (`@AndroidEntryPoint`, edge-to-edge, immersive, keep-screen-on, landscape) with a dark theme.

**Definition of Done:** `./gradlew assembleDebug` succeeds and the app launches to a blank dark screen in landscape.

### Phase 1: Visual UI scaffold ★ (start here for real work)
1. `DashboardLayoutState.kt`: implement exactly as in §5.3.
2. `AnimatedWeight.kt` and `PaneSlot.kt`, as in §5.4.
3. `DashboardScreen.kt`, as in §5.5, with **placeholder panes**: three rounded `Surface`s in distinct colours, each labelled MAP / MEDIA / SPEED, showing its current `CardMode`.
4. `CardChrome.kt`: a header row with icon buttons for maximize (cards only), fullscreen and swap. Wire them to the layout state.
5. Add a temporary debug overlay (bottom-centre) with buttons for swapSides, swapCards and split presets 0.4/0.5/0.6.
6. `DashboardLayoutStateTest.kt` must cover:
   - every row of the §5.1 table;
   - back unwinding;
   - `toggleMaximized(MAP)` throws;
   - toggling twice returns to default;
   - Saver round-trip.

**Definition of Done:**
- All six modes animate smoothly with no crash.
- There is no stray gutter beside a fullscreen pane.
- Swaps work, Back unwinds correctly, and state survives rotation.
- Unit tests pass (`./gradlew testDebugUnitTest`).

### Phase 2: Card modes and interactions
1. `SpeedCard` and `MediaCard` switch content with `AnimatedContent(targetState = mode)`:
   - Compact: one hero element.
   - Expanded / Fullscreen: a detail grid.
2. Gestures: tapping a card toggles maximize; the header icon (or a long-press) toggles fullscreen.
3. Use static fake data in the cards (for example, 87 km/h, a sample track).

**Definition of Done:** the speed card shows only the big speed in Compact and the full telemetry grid in Expanded or Fullscreen. The media card shows art, title and controls at both sizes, and there is no visible text-reflow jank.

### Phase 3: Speedometer data layer
1. Write `MonotonicClock`, `SpeedSample`, `TripStats`, `Geo.kt` (haversine) and `TripReducer`, as in §6.
2. `TripReducerTest.kt` must cover:
   - stationary jitter → 0;
   - a speed spike doesn't change max;
   - poor accuracy is rejected;
   - trapezoid distance;
   - a gap greater than 5 s uses haversine;
   - duplicate or out-of-order fixes are ignored;
   - average = distance ÷ time;
   - moving average excludes stopped time.
3. Write the `SpeedSampleSource` interface and `FakeSpeedSampleSource`, a scripted drive: accelerate, cruise, stop, tunnel gap, spike.
4. Write `TripTracker`, `SpeedometerViewModel` (with the stale rule) and `SettingsRepository` (`SpeedUnit`).
5. Bind the **fake** source in `LocationModule` (debug), then connect `SpeedCard` to `viewModel.ui.collectAsStateWithLifecycle()`.

**Definition of Done:** the reducer tests pass. On the emulator, the speed card plays back the fake drive with correct max, averages, elapsed time and the "--" stale state.

### Phase 4: Real GPS and foreground service
1. `FusedSpeedSampleSource`: `callbackFlow`, `PRIORITY_HIGH_ACCURACY`, 1 s interval, only fixes where `hasSpeed()` is true; map `speedAccuracyMetersPerSecond` when available.
2. `TripService`: foreground service of type `location` with an ongoing notification (speed + elapsed) that starts and stops `TripTracker`.
3. `PermissionsGate`: request fine location, then notifications (13+), then start the service. Handle denial with `GpsStatus.PermissionDenied`.
4. Source selection: the fake source is available in debug builds behind a toggle; release builds always use the real one.

**Definition of Done:** in a real drive (or an emulator GPX route), tracking continues with the screen off and the app backgrounded, and the numbers are plausible.

### Phase 5: Map pane
1. `MapPane`: maps-compose `GoogleMap` with my-location and a follow-camera mode (bearing = course, tilt).
   - Panning breaks follow mode; show a recenter button.
2. Dark map style JSON to match the theme.
3. Verify rule #3: going fullscreen on a card and back **does not** reload the map, and swapping sides does not recreate it.

**Definition of Done:** the map stays alive through every layout transition, and resize animations are smooth on a mid-range device. If they stutter, use a stiffer spring for the map weight only.

### Phase 6: Media pane
1. Write `MediaListenerService`, `MediaSessionRepository`, `NowPlaying` and `MediaViewModel`, as in §7.
2. Add the notification-access onboarding to `MediaCard`.
3. Add controls (play/pause, next, previous) and a progress bar with live position.

**Definition of Done:** with Spotify playing, the card shows the current track and art, controls work, and the card updates on track change. Revoking access shows the call-to-action.

### Phase 7: Polish and persistence
1. Persist `mapOnLeft`, `mediaOnTop` and `split` in DataStore (the current session state stays in `rememberSaveable`).
2. Add a unit setting (km/h or mph), a trip reset action and haptics on toggles.
3. Profile for jank and battery: check for recomposition hot spots during animations and confirm the GPS interval.
4. Run a Compose UI test (`DashboardScreenTest`) for a mode-switching smoke test.

---

## 10. Gotchas checklist

- [ ] `Modifier.weight(0f)` anywhere → crash. Always use `animateWeight()`.
- [ ] Hiding a pane with `if (visible)` → map reload. Don't.
- [ ] `Arrangement.spacedBy` in the Row or Column → gap beside a fullscreen pane.
- [ ] Capturing plain values inside a `movableContentOf` lambda → stale UI.
- [ ] Averaging speed samples → biased average.
- [ ] Wall-clock (`System.currentTimeMillis`) maths for elapsed time → jumps on clock change.
- [ ] Notification access is **not** a runtime permission; it can't be requested with a dialog.
- [ ] `MAPS_API_KEY` belongs in `local.properties` only; never commit it.

## 11. Commands

```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```
