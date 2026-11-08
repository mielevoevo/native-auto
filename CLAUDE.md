# Native Auto

Read `NativeAuto-Handoff.md` first. It holds the architecture rules, layout spec and phase plan.

- Package / applicationId: `com.nativeauto`
- Build: `./gradlew assembleDebug` · Unit tests: `./gradlew testDebugUnitTest`
- **Versioning (user rule, 2026-11-08):** Phase 8b shipped as `versionName` 2.0. Each later sub-phase
  build adds 0.1 to the minor version (8c → 2.1, 8d → 2.2, …) and 1 to `versionCode`, in
  `app/build.gradle.kts`, once that sub-phase's tests pass.
- `MAPS_API_KEY` goes in `local.properties` only (fallback placeholder in `local.defaults.properties`).

## Deliberate deviations from the handoff's reference code

Keep these; the reference versions are buggy.

- `DashboardLayoutState` (§5.3): the `split` setter carries `@JvmName("assignSplit")`, because
  the reference's private setter clashes with `fun setSplit(Float)` and doesn't compile.
- `DashboardLayoutState` (§5.3): `weights` reads `this.fullscreen` / `this.split` /
  `this.maximized`. In the reference, the same-named constructor parameters shadow the
  properties inside `derivedStateOf`, so the weights never changed.
- `TripReducer` (§6.3): an implausible fix (over `MAX_ACCEL_MPS2`) is ignored completely, like
  a poor-accuracy fix. The reference let spikes reach the current speed and the distance, and
  two spikes in a row set a false max. See the class KDoc and `TripReducerTest`.

## Phase 4 additions (beyond the handoff's reference)

- `SpeedSampleSource.isLocationEnabled()` (defaults to `true`): `TripTracker` only leaves
  `GpsStatus.Disabled` once a retry can succeed, so the GPS tile doesn't flicker on each retry.
- `TripTracker` never lets a source error escape (its scope has no handler, so one would crash
  the app): location off → `Disabled` + retry every 3 s; no permission → `PermissionDenied` and
  tracking ends; anything else → retry after 5 s.
- Debug builds start on the **real** GPS. The scripted drive is behind "Fake GPS" in the debug
  layout tools (it also resets the trip). On the emulator, real GPS needs fixes with a speed:
  `adb emu geo fix <lon> <lat> <alt> <sats> <knots>`.
- Trip life cycle (`TripState`: Idle / Running / Paused / Ended), chosen by the user over the
  handoff's plain start/stop. Pause stops the GPS and freezes the clock; paused time is excluded
  from elapsed and averages, and resume drops `lastSample` so no distance is counted across the
  pause. End trip freezes everything and stops the service; the next start is a fresh trip.
  Opening the app keeps a pause. `SpeedometerUiState.isTracking` is derived from `tripState`.
- `DashboardScreenTest` grants location + notifications with `GrantPermissionRule`, otherwise
  `PermissionsGate`'s dialog covers the UI under test.

## Phase 5 additions (beyond the handoff's reference)

- `SpeedSample.bearingDeg: Float? = null` (course over ground) was added to the §6.2 model for
  the map's heading-up camera. Nothing in `trip/` reads it.
- `MapViewModel`: while a trip runs, the map reuses `TripTracker.lastFix` (one GPS subscription;
  the map always agrees with the speed card). When paused or ended, it collects the source itself.
- In `MapPane`, Maps SDK calls inside effects are wrapped in `withContext(AndroidUiDispatcher.Main)`.
  The v2 Compose test dispatcher runs effects off the main thread, and the SDK throws there.
- Follow mode breaks on `cameraMoveStartedReason == GESTURE`, not on `isMoving`: at 1 Hz the
  follow glide keeps `isMoving` true almost all the time.
- `MapDiagnostics` counts map instances per activity; `MapPaneTest` uses it to check rule #3.
- **Two map engines** (`MapEngine`, fixed per build by `BuildConfig.HAS_GOOGLE_MAPS_KEY`, which
  `app/build.gradle.kts` derives from `MAPS_API_KEY` in `local.properties`): with a real key,
  Google (`GoogleDashboardMap`); without one, MapLibre 13.7.1 + OpenFreeMap's free OSM tiles
  (`OpenDashboardMap`). Added 2026-11-02 because the user couldn't link a card for Google billing
  yet. `MapPane` holds the shared parts (follow state, buttons, `MapDiagnostics`).
- MapLibre notes: `textureMode(true)` (a SurfaceView ignores the card's rounded clip); its zoom is
  one level below Google's (512 px tiles), converted at the edges with `ZOOM_OFFSET`; its lifecycle
  is forwarded by hand (`MapViewLifecycle`); the stock OpenFreeMap dark style draws roads darker
  than land, so `applyDashboardPalette()` recolours it to match `map_style_dark.json`. Keep the
  MapLibre "i" attribution button: OpenFreeMap / OpenStreetMap terms require the credit.
- MapLibre uses the **OpenGL ES** artifact (`android-sdk-opengl`). The default `android-sdk` is
  Vulkan, which creates and destroys a Vulkan device per map; that segfaulted the emulator's
  GPU emulation (qemu exit 139) twice during connected tests.
- MapLibre ignores Compose's synthetic swipe; `MapPaneTest.pan()` uses an Espresso swipe there.
- OpenFreeMap has no traffic data, so the traffic button is hidden on that engine
  (`MapEngine.hasTraffic`); the toggle test is skipped there.
- **Traffic toggle** on the map (user request): `SettingsRepository.showTraffic`, off by default and
  remembered across launches; `MapProperties.isTrafficEnabled`. Map buttons (traffic, recenter)
  are full-size 56 dp FABs stacked bottom-right, because the main target is a motorcycle mount
  (gloves).

## Phase 6 additions (beyond the handoff's reference)

- `NowPlaying` gained `positionAtMs` (elapsedRealtime of the snapshot) and `playbackSpeed`
  (0 unless `STATE_PLAYING`), both defaulted, plus `positionAt(now)` for §7's live position.
  The ticking lives in `MediaViewModel.positionMs` (4 Hz, `MonotonicClock`), not in composition:
  a delay loop in a composable keeps the v2 Compose test dispatcher from ever going idle.
- Session choice (`pickSession`): Spotify playing > any app playing > Spotify paused > most
  recent. The handoff's "prefer Spotify" would show a paused Spotify over another app that's
  actually playing.
- With no session to follow, the transport buttons send media keys (`AudioManager`), so Play
  resumes whichever app played last. The card then shows "Nothing playing" with Play only
  (no skips), so the text fits the compact strip.
- Art comes from the metadata bitmap only (ALBUM_ART > ART > DISPLAY_ICON); `*_URI` art isn't
  loaded. Cached per track: every metadata read unparcels a new Bitmap.
- Compact media buttons are 52/60 dp (were 44/52) for gloves.
- Access changes are noticed from four places: `MediaListenerService` connect / disconnect /
  destroy, a `ContentObserver` on `enabled_notification_listeners`, and `MediaCard`'s `ON_START`.
  `hasAccess()` asks `NotificationManager.isNotificationListenerAccessGranted` (8.1+): the
  settings string lags a revoke. `onActiveSessionsChanged` re-queries `getActiveSessions`
  instead of using its argument: around an access change the system sends empty lists, and
  the last one can land after the real sessions (seen on API 37; it left the card on "Nothing
  playing" while music played).
- Device tests publish a real `MediaSession` from the test process (`androidTest/.../TestPlayer`)
  and flip access with `cmd notification allow_listener / disallow_listener`. The media tests
  moved from `DashboardScreenTest` to `MediaCardTest`. Spotify itself is checked on the user's
  phone (the emulator has no Spotify).
- `FakeCardData` (Phase 2 sample tracks) was removed.

## Phase 7 additions (beyond the handoff's reference)

- **Persistence** (extended in 8b: `mediaOnTop` became per-layout pane order, `mapOnLeft` became
  `bigOnLeft`): `mapOnLeft`, `mediaOnTop` and `split` live in DataStore (`SavedLayout`); modes
  (maximized / fullscreen) stay session-only in `rememberSaveable`. `DashboardRoute` shows only the
  background until `DashboardViewModel.initialLayout` is read once, so the map is created once, in
  its saved place; a `snapshotFlow` then saves every change. A stored split is sanitized (NaN →
  0.5, clamped to `MIN_SPLIT`..`MAX_SPLIT` = 0.3..0.7, which `setSplit` also uses).
- **Status corner** (user request): the system bars are hidden, so the header of the top-right pane
  (`DashboardLayoutState.statusPane`) carries a small battery reading plus the settings gear.
  Battery is shown by default, hidden from settings; when hidden, the `ACTION_BATTERY_CHANGED`
  receiver is released (`flatMapLatest` on `showBattery`). Red at ≤15% unless on power; "on power"
  includes plugged-but-not-charging (weak bike USB ports).
- **Settings sheet** (`ui/settings/SettingsSheet.kt`), opened from the gear: speed unit, map width
  40/50/60 (replaces the debug overlay's presets), battery switch, Reset trip, and in debug builds
  Fake GPS. Two columns when ≥600 dp wide, so nothing needs scrolling in landscape. All targets
  ≥56 dp. The floating debug overlay and `DebugToolsViewModel` are gone.
- **Reset trip** needs two taps within 3 s (the button turns red: "Tap again to reset"). It
  restarts a running trip in place (`TripTracker.reset()`); an idle tracker stays idle. The timer
  is in the ViewModel (a delay in composition would hang the v2 test dispatcher).
- **Haptics** (`ui/components/Haptics.kt`): toggle on/off for maximize, fullscreen, traffic and
  switches; VirtualKey for swaps, media buttons, recenter; SegmentTick for segmented choices;
  Confirm when a reset lands. Long-press keeps combinedClickable's own buzz.
- **Device tests** start from default preferences via `ResetSettingsRule` (order 1, before the
  compose rule), which reaches `SettingsRepository` through `SettingsEntryPoint`. Without it a
  saved swap would leak into later tests.
- **Profiling (emulator)**: during a layout animation the panes recompose once, not per frame
  (movable content plus `derivedStateOf` weights); only the thin `DashboardScreen` shell re-runs
  for the animated weights. The speed card recomposes once a second for the clock. GPS: 1 s
  interval, `PRIORITY_HIGH_ACCURACY`, min interval 1 s, no batching, off while paused/ended
  except for the map's own fix while it's on screen.

## Navigation + trip history (built 2026-11-02, beyond the handoff)

The handoff had both as v1 non-goals; the user asked for them after Phase 7. Field-tested on the
bike the week of 2026-11-02 (speed, map, media, saved trips all fine; search too strict, see
`Rider-Feedback.md` §1/§7). That file holds the post-test brainstorm and the Phase 8–11 roadmap.

- **Stack (all free, no key):** Ferrostar 0.59 (navigation core) + FOSSGIS **Valhalla** with
  **motorcycle** costing (honours `motorcycle=no`, so Indonesian toll roads stay out) + **Photon**
  (komoot's OSM geocoder) for search. One `OkHttpClient` (`DataModule`) with an identifying
  User-Agent, which both community services ask for. FOSSGIS allows ~1 req/s: reroutes are at least
  8 s apart (`minimumTimeBeforeRecalculation`), and search waits for a pause in typing.
- **`nav/` keeps Ferrostar's types inside it:** `GeoPoint`, `Place`, `Instruction`, `NavState`
  (Idle / Routing / Preview / Navigating / Arrived / Failed) are ours; `toNavState` and
  `toInstruction` in `Navigator.kt` are the only mapping. The UI only draws `NavState`.
- **One GPS subscription:** `LiveLocation` reuses `TripTracker.lastFix` while a trip runs and reads the
  source itself otherwise (stopping 5 s after the last reader). Ferrostar reads it through
  `LiveLocationProvider`; it never opens its own location client. Guidance runs only while the app is
  open (no service of its own).
- Narration is English (`Locale.US` forced on the TTS): Valhalla has no Indonesian. Voice mute lives in
  `SettingsRepository.voiceGuidance` and applies mid-route.
- Destinations: the search sheet (recent places first, `RecentPlaces`, 8 kept, ~10 m dedupe key) or a
  long-press on the map (named afterwards via `Navigator.rename`).
- **Debug "Simulate navigation":** `RouteSimulation` + `simulatedDrive` drive the planned route at
  11 m/s through `SwitchableSpeedSampleSource` (it beats Fake GPS); slows to 45 % before bends over 60°.
- **History (`history/`, Room `native-auto.db` v1, `exportSchema = false`):** `TripRecorder` watches
  `TripTracker` from outside and saves on End, or when a reset replaces the trip. The path is the fixes
  the reducer **accepted** (so it matches the card's distance), 3 m minimum step, Douglas–Peucker at
  4 m, stored as a precision-5 encoded polyline. Trips under 100 m aren't kept. Wall-clock times are
  derived from the monotonic clock at save time. A trip in progress is memory-only (lost if the
  process dies).
- Addresses: the platform `Geocoder` first (Google's data on Play phones, free), Photon reverse as
  fallback, `shortAddress` trims to "Street, Area" and skips plus codes / bare house numbers. Misses are
  retried when the history opens (`fillMissingAddresses`).
- Overview maps: `SnapshotTripMaps` (MapLibre `MapSnapshotter`, OpenFreeMap style), path drawn on a
  plain background until the snapshot is ready. History opens from the settings sheet and is drawn
  over the dashboard (rule #3: the live map stays composed). Delete is a two-tap arm like Reset.
- **Unit tests (Phase 8a):** `ParsePhotonTest`, `PolylineTest` / `SimplifyTest`, `ShortAddressTest`,
  `NavFormatTest`, `NavStateMappingTest` (builds Ferrostar's uniffi records on the JVM; no native
  library needed), `SimulatedDriveTest`, `TripRecorderTest` (a real `TripTracker` with fake source,
  DAO and resolver). No device tests for nav yet: the emulator's SwiftShader doesn't draw map text,
  and routing needs the network.
- Phase 8a also fixed `formatNavDistance` printing "10.0 km" / "10.0 mi" just under 10.

## Phase 8b: layout system (built 2026-11-08, beyond the handoff)

- **Model (`LayoutModel.kt`):** five panes (`Pane`: MAP, MEDIA, SPEED, NAV = Next turn, CLOCK) and
  four presets (`LayoutPreset`): Duo (tall slot + two stacked, the old layout), Side by side, Rows,
  Quad. Each preset remembers its own pane order (`assignments`, saved as `LayoutCodec` text in
  DataStore with the preset; damaged entries fall back to that preset's default). Geometry is derived
  (rule #1): `computeRects(view, bigOnLeft, split)` gives every pane a fractional `Rect`; panes the
  layout doesn't show get `ParkedRect` (zero area). `split` and `bigOnLeft` apply to Duo only.
- **Screen:** one custom `Layout` with every pane always composed, in `Pane.entries` order (rules
  #3/#4: nothing is removed or reparented). Each child is placed in its animated rect
  (`animateRectAsState`), read only in the measure pass, so a transition re-lays out without
  recomposing. Edges are rounded, not sizes, so neighbours share an edge exactly. Slot tags are
  `slot_<PANE>`.
- **Card modes** come from the layout (rule #6): `naturalMode(preset, slot)`: Duo's tall slot
  Expanded, stacked slots Compact; Side by side Expanded; Rows **Wide** (new: speed shows the hero
  plus an inline max/avg/distance/time line; media treats Wide as Compact); Quad Compact. Maximize
  exists only where a pane shares its column (it then fills the column, Expanded); fullscreen
  everywhere. So the map's mode in Duo is now Expanded, not Compact.
- **Switching:** the layouts button (status corner, left of the gear) opens big preset thumbnails
  drawn from `computeRects`; switching clears zoom. A long-press on a pane's **header title area**
  opens the pane picker: a pane already shown trades places, any other replaces the slot's pane. A
  long-press on a header icon button just clicks that button (checked on the emulator: no double
  action). The header swap button: column-mate, or Duo's side flip, or Side by side's two panes.
- **Auto layout (`AutoLayout.kt`)**, on by default, switch in settings: when navigation starts it shows
  an *override* view on top of the user's layout: Quad → `NavQuad` (map, next turn, speed, media);
  any layout where the map isn't visible → Duo with the map (in the tall slot if Duo didn't hold it).
  A visible map (including map fullscreen) means no change. Navigation end drops the override.
  Zooming during an override keeps it; any arrangement by hand (preset, picker, swap, split) commits
  it as the user's own and ends it. A start within `HOLD_MS` (2 min) of an arrangement by hand is
  ignored ("manual wins"). Only the user's layout is saved, never the override; the override itself
  survives recreation via the `Saver`, and `DashboardRoute` saves the last nav state seen so a
  recreation mid-route isn't taken for a new start.
- **Map sleep (`MapSleep`), the one exception to rule #3:** the map pane stays composed, but when it's
  off screen (not in the layout, collapsed by zoom, or under the trip history) for 30 s, the map view
  is released and a plain background drawn instead (`map_asleep`). Camera (`MapCameraMemory`, Google
  zoom levels) and follow state are kept; on wake it's recreated at the saved camera and fades in
  (`WAKE_FADE_MS`). A quick peek inside the grace period doesn't recreate it. Only the awake map
  collects `fix`, so a sleeping map's GPS read stops; the trip and navigation keep their own readers.
  Tests shorten the grace through `MapSleep.defaultGraceMs`.
- **Next turn pane (`NextTurnCard`)**: big arrow, distance, road, ETA line ("arrive · left · km"),
  sized per mode; End button when Expanded/Fullscreen. Idle: a big "Where to?" button (the map's
  search sheet). Routing/preview/failure reuse the map's cards. When the Next turn pane is on screen,
  the map hides its own banner, status card and summary (`showNavPanels`).
- **Clock pane (`ClockCard`)**: digital, phone's 12/24-hour setting, date below. Minute tick in
  `ClockViewModel` (not in composition: v2 test dispatcher). Analog face and status-corner clock: 8c.
- `DashboardLayoutState.preset`'s private setter carries `@JvmName("assignPreset")`, for the same
  JVM clash as `split`.
- Settings: voice guidance and auto layout moved to the right column (under the trip), so both
  columns fit landscape without scrolling in a release build.

## Phase 8c: look and clock (built 2026-11-09, beyond the handoff)

- **Theme tokens (`ui/theme/Color.kt`):** `AppColors` (bg, surface, outline, text, textDim, button /
  onButton, buttonQuiet, navy / onNavy, cyan, amber, red, mapBackground), read through
  `AppTheme.colors` (`LocalAppColors`). The Material scheme is derived from them (`toScheme()`), so
  stock components follow. `OledDark` is the spec table (Rider-Feedback §11): true-black bg,
  #0A0C10 cards with a 1 dp #262A33 outline, navy for "selected" (`appSegmentedColors`, switch track
  in `appSwitchColors`), at most **one cyan element per card**, status colours amber and red only (no
  green anywhere, history start dots included). `Light` is its daylight twin.
- **Theme setting:** `ThemeMode` Dark (default) / Light / System in `SettingsRepository.themeMode`;
  `MainActivity` collects it before `NativeAutoTheme`. Window background is black (no white flash).
- **Card titles:** sentence case ("Map", "Next turn"), `PaneTitle` style, textDim.
- **Map look (`MapLook`):** dark = OpenFreeMap dark recoloured to true black (`applyOledPalette`; roads
  the lightest thing on the map), `res/raw/map_style_dark.json` its Google twin; light = OpenFreeMap
  Positron unmodified / Google's default style. Route, casing, destination and start colours come from
  the look. MapLibre restyles live in `LaunchedEffect(map, look)`. **Anything that writes to a `Style`
  checks `isFullyLoaded` first** (`VehicleMarker.draw/hide`, `showRoute`): a theme change replaced
  the style mid-glide and MapLibre threw ("Calling getSourceAs when a newer style is loading"),
  crashing the app. History snapshots are cached per theme (`…_dark.png` / `…_light.png`).
- **Clock faces:** digital or analog, a pane setting (`clockFace`, `clockSeconds`) picked in the
  pane itself when Expanded/Fullscreen (`FaceControls`: segmented Digital/Analog + "Second hand").
  The analog face is **edge-tick**: 60 ticks around the card's own rounded edge (`edgeAt` in
  `EdgeGeometry.kt`), spaced evenly **along the edge**, not by angle (angle spacing crowded them
  around 12 and 6 on a wide pane); hour ticks longer and brighter. Hands point at their own edge
  spot (`handTurns` = fractions of a turn): short fixed hour hand, minute hand reaching almost to
  its tick, optional cyan second hand (off by default) with a tail. Our own Canvas code. The
  per-second ticker (`ClockViewModel.secondsMs`) is only collected while analog + second hand + clock
  pane on screen.
- **Corner clock:** the status corner shows the time beside the battery whenever no clock pane is on
  screen (`corner_clock`), from the same minute ticker.
- **Settings sheet:** three columns at ≥840 dp (Display: theme, unit, battery · Layout and
  navigation · Trip and debug), two at ≥600 dp, one below; no drag handle, 1200 dp max width.
- Unit tests: `EdgeGeometryTest` (points on the outline, outward unit normals, even spacing, corner
  normal, hand turns). Device tests: theme switch with the map on screen, clock face + second
  hand, corner clock in Duo vs Quad.
- The ANR seen after installing 8c was the emulator after ~a day of uptime (system UI skipped
  frames too); after a cold boot there was none. A visible map still costs ~1 core there (see
  Known issues); with the map asleep the app idles at ~0%.

## Known issues (deferred)

- **Visible map costs ~1 CPU core continuously on the emulator** (about 10% with the map hidden);
  cold start is ~14 s there. MapLibre with real tiles costs the same as Google without a key, so
  the missing key isn't the cause; likely the emulator's software renderer redrawing for the
  1 Hz location updates (the emulator re-sends its last fix forever). Re-measure on the user's
  phone when the app is complete.

- **MAX lags the hero by one fix after a start or resume.** The reducer's first-fix branch
  (§6.3 reference) sets `currentSpeedMps` but not `maxSpeedMps`, so for ~1 s the card can show
  e.g. 72 km/h with MAX 0. The next fix corrects it. Deferred on purpose (2026-11-02): trips are
  assumed to start while moving, so it's cosmetic. Fix later by applying the accurate-speed max
  rule in the first-fix branch too, and list it under the deviations above.

## Future features (decided, not scheduled)

- **In-pane turn-by-turn navigation** (Android Auto–style) via the Google **Navigation SDK**,
  rendered inside the map pane. It's a separate product with its own terms and per-trip pricing,
  not covered by free map display. Out of scope for v1 (handoff §1 non-goals). Embedding the
  Google Maps app in the pane is not planned (Android doesn't allow it). Added 2026-11-02 at the
  user's request; revisit after the app is complete.
  Free alternative for the MapLibre engine: **Ferrostar** (open-source, BSD) with a Valhalla or
  OSRM routing server (public demo servers have fair-use limits; or self-host). No live traffic.
