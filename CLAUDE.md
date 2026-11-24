# Native Auto

Read `NativeAuto-Handoff.md` first. It holds the architecture rules, layout spec and phase plan.

- Package / applicationId: `com.nativeauto`
- Build: `./gradlew assembleDebug` · Unit tests: `./gradlew testDebugUnitTest`
- **Versioning (user rule, 2026-11-08):** Phase 8b shipped as `versionName` 2.0. Each later sub-phase
  build adds 0.1 to the minor version (8c → 2.1, 8d → 2.2, …) and 1 to `versionCode`, in
  `app/build.gradle.kts`, once that sub-phase's tests pass.
  **Phase 11 is a major version (user, 2026-11-09):** 11a ships as 3.0, then +0.1 again (11b → 3.1,
  11c → 3.2); `versionCode` keeps adding 1 (10b = 11, 11a = 12).
  **Phase 13 = feedback update (user, 2026-11-14):** 13a ships as 3.4.1, 13b as 3.4.2, 13c as 3.4.3 (versionCode 19, 20, 21); 12c (fuel prices, v3.3) is versionCode 18. **Phase 14 = call controls + portrait:** 14a 3.5.1 (versionCode 22); **14b (portrait) is a major drop (user, 2026-11-14): v4.0, versionCode 23.** Plans (WIP, not started) in `Rider-Feedback.md` "Phase 13" / "Phase 14".
  **Phase 15 = iOS port (user, 2026-11-16):** its own version line, 0.1 at 15a, +0.1 per sub-phase, 1.0 at parity with Android. Plan: `ios/PLAN.md`. Built on the rider's Mac; it comes before the Android next update.
  **Unattended again (user, 2026-11-14): v4.1 (logo, versionCode 24) -> v4.1.1 (follow-ups, 25) -> v4.2 (first-run setup pages, 26).**
  Spec and rules in `.dev/Phase-4.1-4.2-Unattended.md` (same rules as 13a -> 14a; a failing test or build is fixed at once, not handed back).
  **Phase 4.3 (user, 2026-11-24):** 4.3a v4.3 (vc 28), 4.3b v4.4 (29), 4.3c v4.5 (30), 4.3d v4.6 (31). Plan in `Rider-Feedback.md` "Phase 4.3 plan".
  **Unattended (user, 2026-11-24): 4.3a -> 4.3d, whole cycle,** spec and rules in `.dev/Phase-4.3-Unattended.md`. **Done 2026-11-24** (v4.6, vc 31; section "Run 4.3" below).
  **Phase 12 is one version (user, 2026-11-09):** 3.3 for the whole phase (12a, 12b); `versionCode` still +1
  per sub-phase build (3.2.1 = 15, Phase 12 starts at 16).
- `MAPS_API_KEY` goes in `local.properties` only (fallback placeholder in `local.defaults.properties`).
  Other keys (e.g. TomTom) follow the same pattern: a placeholder in `local.defaults.properties`, the
  real value only ever added by the user to `local.properties`. Never read or edit `local.properties`.

## Workspace rules (user, 2026-11-09): stay on D, emulator only

- **Start every build / adb / emulator command with `. /d/tsunaayukii/native-auto/.dev/env.sh`.** It
  points Gradle's cache, the emulator (AVD), Android user files and temp files at `.dev/` (git-ignored,
  inside this project on D) and pins `ANDROID_SERIAL=emulator-5554`. Use `adbe` (adb fixed to the
  emulator) instead of raw adb. Install only to the emulator, never to a phone or the PC.
- Emulator launch: `. .dev/env.sh; "$EMU" -avd Medium_Phone_API_37.0 -memory 2048 -gpu swiftshader_indirect -no-snapshot-save`
  (run in the background). Its disk lives in `.dev/android-user/avd/`.
- Files written only in: `app/`, `gradle/`, `baselineprofile/`, root build files,
  `local.defaults.properties` (placeholders), `CLAUDE.md`, `Rider-Feedback.md`, `DECISIONS.md`, `DESIGN.md`, `README.md`, `LICENSE`,
  `.gitignore`, `.dev/`, `ios/` (Phase 15, iOS port; see `ios/CLAUDE.md`). Plus Claude Code's own memory folder. Scratch work goes in `.dev/scratch/`.
- On C, only *run* the Android SDK and Android Studio's JBR (installed there); never write there.
  Known unavoidable: the emulator drops a tiny "running" marker in C's temp folder.
- Kotlin compiles in-process (`gradle.properties`), so no Kotlin daemon writes to `%LOCALAPPDATA%`.
  The debug keystore in `.dev/android-user/` is a copy of the original, so builds stay signed alike.
- **Screenshots (user, 2026-11-15):** the user's phone screenshots go in `screenshots/` (git-ignored, never committed; they show real locations). Read them from there; my own go in `.dev/scratch/`.
- **Git:** local repo, branch `main`, one commit per finished sub-phase. No remote, never push
  (blocked in `.claude/settings.local.json`); the user decides on GitHub.
- **Unattended phases (8d → 11c):** don't stop to ask. Take the recommended option and log it in
  `DECISIONS.md`. The user tests on the phone once, after 11c.
- **Unattended again (user, 2026-11-14): 13a → 14a.** Same rule: on a roadblock take the recommended or
  already-discussed option, log it in `DECISIONS.md` with the *reason* (the user reads it later). Each sub-phase
  exports its **own** release APK to `.dev/apk/NativeAuto-<version>-<sub>-release.apk` (never overwrite an
  earlier one: they are the fallback if 14b goes wrong), after the release smoke test. **14b (portrait, v4.0) is its own
  unattended run (user, 2026-11-14), spec in `.dev/Phase-14b-Portrait.md`; tests only what a change touches.** Every sub-phase build also adds its entry to `assets/changelog.md` (from 13a on).

**Design review (user, 2026-11-24):** every UI change goes through `DESIGN.md` (gates, consistency locks, critique) before its
commit; the result is logged in `DECISIONS.md`. Never a blocking question during an unattended run.

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
- `TripReducer` (§6.3, 13b): the first fix after a start or resume sets `maxSpeedMps` too (accurate-speed rule), so
  the max no longer lags the hero by one fix. Matters for auto-start, which begins mid-motion (`TripReducerTest`).

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
- **Next turn pane (`NextTurnCard`; renamed Navigation in 13a)**: big arrow, distance, road, ETA line ("arrive · left · km"),
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
  onButton, buttonQuiet, navy / onNavy, accent (was cyan until 13c), amber, red, mapBackground), read through
  `AppTheme.colors` (`LocalAppColors`). The Material scheme is derived from them (`toScheme()`), so
  stock components follow. `OledDark` is the spec table (Rider-Feedback §11): true-black bg,
  #0A0C10 cards with a 1 dp #262A33 outline, navy for "selected" (`appSegmentedColors`, switch track
  in `appSwitchColors`), at most **one accent element per card**, status colours amber and red only (13c: the accent may be green, the rider's
  choice, and is green by default; amber and red stay status-only and are not offered as accents; history start dots stay plain). `Light` is its daylight twin.
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
- **Settings sheet (replaced by categories in 13c):** three columns at ≥840 dp (Display: theme, unit, battery · Layout and
  navigation · Trip and debug), two at ≥600 dp, one below; no drag handle, 1200 dp max width.
- Unit tests: `EdgeGeometryTest` (points on the outline, outward unit normals, even spacing, corner
  normal, hand turns). Device tests: theme switch with the map on screen, clock face + second
  hand, corner clock in Duo vs Quad.
- The ANR seen after installing 8c was the emulator after ~a day of uptime (system UI skipped
  frames too); after a cold boot there was none. A visible map still costs ~1 core there (see
  Known issues); with the map asleep the app idles at ~0%.

## Phase 8d: smoothness (built 2026-11-09, beyond the handoff)

- **Release check before any APK goes to the user (added v3.2.1):** the v3.2 release APK crashed at
  start on every phone (R8, `optimization { packageScope }`, moved Kotlin's multifile-class parts out of
  their package: `IllegalAccessError` in `DataModule.provideDatabase`) while every device test passed,
  because those run on debug. Fixed by `-keeppackagenames kotlin.**,kotlinx.**,androidx.**` in
  `app/src/main/keepRules/rules.keep`. Before handing out an APK, run the black-box
  `baselineprofile/.../ReleaseSmokeTest` (UiAutomator on the minified `benchmarkRelease` build, every
  screen, fails on any entry in the crash log):
  `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.nativeauto.baselineprofile.ReleaseSmokeTest`.
  Running the in-process device tests against release doesn't work (the test APK needs library code R8
  removed from the app, e.g. `androidx.tracing`).

- **Release build** is signed with the debug key (`app/build.gradle.kts`), so it installs over the
  debug build. R8 optimizes only `androidx.**`, `kotlin.**`, `kotlinx.**` (`packageScope`); the app's
  own code and Ferrostar's uniffi/JNA bindings are left as they are. Use the release APK for the phone
  test (§12.1: the debug build was the first suspect).
- **Map during a move (`ui/map/HoldSize.kt`):** while its pane is animating, the map view is laid out
  at the window size, centred and clipped, then at its real size when the move ends. Two resizes per
  move instead of one per frame. "Moving" comes from the dashboard through `LocalPaneMotion` (animated
  rect ≠ target rect), read only in the layout pass.
- **Refresh rate (`ui/components/FrameRate.kt`):** while any pane moves, the window asks for the
  display's fastest mode at the current resolution (`preferredDisplayModeId`); 0.5 s after the last
  move it goes back to 0 (system choice). The emulator has a single 60 Hz mode, so nothing happens there.
- **Thermal:** `MainActivity` logs thermal status changes while started (`adb logcat -s NativeAuto/Thermal`).
- **Baseline profile (`baselineprofile/` module, `com.android.test`, benchmark 1.5.0):** journeys in
  `DashboardJourney.kt` (start in Duo, maximize, swap, settings, layout switch). Generate with
  `./gradlew :app:generateBaselineProfile` (output in `app/src/release/generated/baselineProfiles/`,
  committed); measure with `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. The app
  depends on `profileinstaller`. UiAutomator finds Compose tags because the root and every sheet set
  `testTagsAsResourceId` (`Modifier.sheetTag` for sheets, which live in their own window).
- Emulator numbers are relative only (SwiftShader, 60 Hz). Real frame times: phone, release build.

## Phase 9a: trip state machine (built 2026-11-09, beyond the handoff)

Replaces the Phase 4 life cycle where it differs (Rider-Feedback §2–3 and "Decided").
- **No auto-start:** `PermissionsGate` only asks for permissions; a trip starts when the rider presses
  Start (`TripService.start`). Until 9b's log face, the idle speed card's hero prompt is "No trip
  running · Start trip" (`hero_action`).
- **Reset = discard:** `TripTracker.discard()` (was `reset()`) stops the GPS read, clears the figures
  and `lastFix`, and goes Idle; `TripRecorder` saves nothing for a trip cleared without ending.
- **Live speed without a trip:** `SpeedometerViewModel` reads `LiveLocation` when the trip isn't
  running, so the hero shows the speed (the map's GPS read); the trip figures stay frozen or zero.
- **Auto-pause / auto-end (`trip/AutoTrip.kt`):** `autoAction()` is pure; `AutoTrip` (started in
  `NativeAutoApp`) checks every 10 s while running or paused. Below `STATIONARY_MPS` since
  `TripStats.lastMovingAtNanos` (or the start / resume) for `autoPauseMinutes` → `pause(auto = true)`
  (`TripStats.autoPaused`, "Auto-paused: no movement"); paused for `autoEndMinutes` → `end()` (saved).
  0 = off. Settings rows in the Trip column (`settings_auto_pause_N`, `settings_auto_end_N`).
- **Short trips dropped:** under 300 m or under 2 min moving (`TripRecorder.MIN_DISTANCE_M`,
  `MIN_MOVING_MS`; tests lower them through `minDistanceM` / `minMovingMs`).
- **Closing:** swiping the app away (`TripService.onTaskRemoved`) or the activity really finishing
  (`MainActivity.onDestroy`, not a rotation) ends and saves the trip; the service stops when the trip
  is Ended or Idle. A notification End arms `core/SelfClose`: the app left in the background finishes
  its task 10 min later (`closeRequests`); becoming visible or a new Start cancels it.
- Device tests: `ResetSettingsRule` also discards any trip (via `SettingsEntryPoint.tripTracker()`),
  so each test starts Idle. Taps meant for the speed card itself go off-centre (the Start button sits
  in the middle).

## Phase 9b: log face, summary, closing (built 2026-11-09, beyond the handoff)

- **Two faces** in `SpeedCardContent`: a `HorizontalPager` (`speed_faces`), page 0 the speed face,
  page 1 the log face (`ui/speed/TripLog.kt`), with `FaceDots`. The pager composes the neighbour page,
  so log-face stat tags carry a `log_` prefix (`TelemetryGrid(tagPrefix)`); tests that need the log
  face swipe `speed_faces` left.
- **Controls:** `log_start` (Start / Resume), `log_pause`, `log_end` (two taps, `TripLogViewModel`
  arm timer, 3 s). Start and Resume go through `TripService`; Pause, End and dismiss call the tracker.
  The speed face has no Idle / Ended prompt any more; Paused keeps Resume.
- **Summary (`TripSummary`, `trip_summary`):** shown while `TripState.Ended`; figures count up; route
  from `TripRecorder.lastOutcome` (`TripOutcome.Saved` / `TooShort`, null while a trip runs). Dismiss
  (tap / swipe) = `tracker.discard()`. `TrackSketch` in the history screen became internal for it.
- **Closing (`ui/dashboard/CloseFlow.kt`):** `CloseOnDoubleBack` at the dashboard root, enabled only
  when nothing else takes back. `CloseViewModel.onBack()` → hint (`back_hint`, 2 s) / close / ask
  (`save_prompt_save|discard|cancel`). Closing is `finishAndRemoveTask()`.

## Phase 9c: full track, migration, telemetry (built 2026-11-09, beyond the handoff)

- **Room v2** (`MIGRATION_1_2` in `history/Database.kt`, added in `DataModule`): `trips.stoppedMs`
  (`@ColumnInfo(defaultValue = "0")`, backfilled as duration − moving) and `trips.bikeId` (null until
  11a); new `track_points` table (`TrackPointRecord`: tripId, wall `timeMs`, lat/lon, speed, bearing,
  accuracy; index on tripId, no foreign key). `TripDao.insertWithTrack` / `delete` are `@Transaction`
  default methods. `MigrationTest` (device) builds a v1 file by hand and opens it under v2.
- `TripRecorder` keeps every accepted fix (`Recording.fixes`) next to the simplified path; old trips have
  only the polyline.
- **Telemetry (`settings/TelemetryStat.kt`):** `SettingsRepository.hiddenStats` (string set, default
  `DEFAULT_HIDDEN` = moving + stopped time). `SpeedometerUiState.tripStats(visible, prefix, progress)`
  builds the log face (`log_stat_*`) and summary (`sum_stat_*`) tiles; `StatGrid` lays them out; the
  history detail filters the same way. `SpeedometerUiState` gained `movingTime` / `stoppedTime`.
- **Settings:** the Trip column has a "Trip options" row (`settings_trip_options`) opening a dialog with
  auto-pause, auto-end and the figure chips (`settings_stat_<key>`, `settings_trip_options_done`).

## Phase 9d: history by day, GPX, backup (built 2026-11-09, beyond the handoff)

- **Day groups (`ui/history/DayGroups.kt`):** `groupByDay(trips, nowMs, zone)` → `DayGroup` (label,
  total distance); the history grid puts a full-span `DayHeader` (`history_day_<date>`) before each day.
- **GPX (`history/Gpx.kt`):** `buildGpx(trip, points)`, GPX 1.1, time + `<extensions><speed>/<course>`
  per point; v1 trips fall back to the polyline without times. `gpxFileName` sorts by UTC date.
- **Backup (`history/Backup.kt`, plain JVM):** ZIP with `backup.json` (`FORMAT` 1: trips + full track,
  points as `[timeMs, lat, lon, speed, bearing|null, accuracy]`) and `gpx/*.gpx`. `Backup.read` uses the
  JSON only; `Backup.restore` skips start times already present.
- **`history/TripFiles.kt`:** writes `cache/exports/` (cleared per export) and returns share-sheet
  intents through the FileProvider (`${applicationId}.files`, `res/xml/file_paths.xml`); `import(uri)`
  reads a picked ZIP. History UI: `history_backup`, `history_import` (OpenDocument), `history_export`
  in the detail, `history_message`.
- `SettingsEntryPoint` also exposes `tripDao()` / `tripFiles()` for device tests (`TripFilesTest`,
  the history test in `DashboardScreenTest`), which seed and delete their own trips.

## Phase 10a: TomTom search (built 2026-11-09, beyond the handoff)

- **Key (WIP):** `TOMTOM_API_KEY` like `MAPS_API_KEY`: placeholder `DEFAULT_TOMTOM_KEY` in
  `local.defaults.properties`, the real one only in `local.properties` (the user adds it).
  `app/build.gradle.kts` derives `BuildConfig.HAS_TOMTOM_KEY`; the secrets plugin provides
  `BuildConfig.TOMTOM_API_KEY`. Not added yet: search runs on Photon.
- **`nav/TomTomPlaceSearch.kt`:** `TomTomPlaceSearch` (search/2/search, reverseGeocode; `available` =
  has key), `PreferredPlaceSearch` (bound as `PlaceSearch` in `DataModule`: TomTom else Photon, Photon on
  any `IOException`), pure `parseTomTomSearch` / `parseTomTomReverse` / `withFallback` (`TomTomParseTest`).
- **Debug Search test (`app/src/debug/`):** `SearchTestActivity` + its manifest; `TEST_QUERIES` (20,
  YK/JKT). Opened from settings' Debug row (`debug_search_test`, by class name, so main code doesn't
  reference it). Device test `SearchTestScreenTest` (network-tolerant).

## Phase 10b: Google Maps links (built 2026-11-09, beyond the handoff)

- **`nav/MapsLink.kt` (pure):** `findMapsUrl`, `isShortMapsLink`, `parseMapsLink(url, sharedText)` →
  `LinkTarget.At(point, name)` / `Query(text)`, `sharedName` / `sharedDetail` (`MapsLinkTest`).
- **`nav/MapsLinkResolver.kt`:** `MapsLinkResolver.resolve(text)` (expands short links with a
  no-redirect OkHttp client, searches names, throws `LinkException` with a rider-readable message);
  `IncomingLinks.open(text)` (app scope) → `Navigator.routeTo` (preview) and `status` (`LinkStatus`).
  `MapsLinkResolverTest` uses a local `HttpServer` for the redirect chain.
- **Intake:** `MainActivity` (singleTask) handles `ACTION_SEND` text and `VIEW geo:` in `onCreate`
  (not on recreation) and `onNewIntent`. `LinkStatusPill` (`link_status`) sits top-centre on the
  dashboard. `DestinationSearchViewModel` resolves a pasted link instead of searching it.
- Device tests deliver real share intents (`DashboardScreenTest.share`); `ResetSettingsRule` stops the
  navigator (`SettingsEntryPoint.navigator()`, on the main thread) so a preview doesn't leak.

## Phase 11a: bikes and odometer (built 2026-11-09, beyond the handoff)

- **Room v3** (`MIGRATION_2_3`): `bikes` (`BikeRecord`: id UUID, name, type, model, tank, reserve, fuel,
  startKmPerL, `odoFactor`, `active`, `lastUsedAtMs`) and `odometer_readings` (`OdometerReading`: kind
  SET / CORRECTION / AWAY, `awayKm`, `tripOffsetM`). `GarageDao` in `garage/Garage.kt`; `MigrationTest` now
  opens a v1 file under v3.
- **`garage/Odometer.kt` (pure):** estimate = last reading + factor × GPS km since (`metersSince`: trips
  ending after the reading; the trip running at the reading counts past `tripOffsetM`; the live trip too).
  `learnFactor` (≥ 30 km, 0.8–1.2, weight 0.5), `decideReading`, `needsAwayCheck` (3 days), `parseNumber`
  (Indonesian thousands with `thousands = true`). Tests: `OdometerTest`, `GarageRepositoryTest` (fake DAO,
  real `TripTracker`).
- **`GarageRepository`** (singleton, also bound as `ActiveBike`): add (the first bike is active and adopts
  untagged trips), update, delete (trips untagged, the most recently used bike takes over), `activate`
  (raises `GaragePrompt.OdometerNow` after 3 days idle), `enterReading`, `odometer(id)` flow (live trip
  rounded to 10 m). `startWhichBikeCheck()` runs from `NativeAutoApp` in **debug builds only**
  (`GaragePrompt.WhichBike`, > 50 km from the active bike's last trip end, ≥ 2 bikes).
- **`TripRecorder`** tags each saved trip with `ActiveBike.id()` at save time (the last selected bike).
- **UI (`ui/garage/`):** `GarageScreen` over the dashboard like the history (`garage_screen`; the map sleeps
  under it, back closes it); `BikeEditor`, `OdometerDialog`, `GaragePrompts` (at the dashboard root) in
  `GarageDialogs.kt`; one activity-scoped `GarageViewModel`. Settings' Trip column: Garage row
  (`settings_garage`, hint `settings_garage_bike` "Riding Gen 1"). History: bike chips (`history_bike_all`,
  `history_bike_<name>`) and the bike name on trips, only with ≥ 2 bikes.
- Device tests: `GarageScreenTest`; `ResetSettingsRule` clears the Garage (`SettingsEntryPoint.garageDao()`)
  and its prompt. Odometer always in km.

## Phase 11b: fuel, untracked km, trip recovery (built 2026-11-09, beyond the handoff)

- **Room v4** (`MIGRATION_3_4`): `refuels` (`RefuelRecord`: bike, time, odometerKm, litres, pricePerL,
  totalRp, full, fuelType), `fuel_prices` (`FuelPrice`, last pump price per `FuelType`; defaults in
  `FuelType.defaultPricePerL`), `live_trip` + `live_points` (trip checkpoint). `MigrationTest` opens v1 under v4.
- **`garage/Fuel.kt` (pure, `FuelTest`):** everything runs on **odometer** km, never GPS km.
  `learnConsumption` (full-to-full spans, partials included; first measure weight 0.5, then 0.3; 10–100 km/L;
  ±35% outlier once learning started; spans with AWAY readings skipped), `fuelLevel` (full at the last full
  fill, walked forward through partials, capped at the tank; unknown with no full fill or AWAY since),
  `refuelAmounts`, `costPerKm`, `spend`, `formatRupiah` ("Rp 98.000").
- **`GarageRepository`:** `fuel(bikeId)` → `FuelState` (level, consumption, refuels, price); `logRefuel`
  (odometer within `REFUEL_SAME_KM` of the estimate = the estimate, else stored as a reading; updates the
  fuel's price); `deleteRefuel`; `addUntrackedKm` (`ReadingKind.UNTRACKED`, km in `awayKm`, re-anchors at
  estimate + km, teaches nothing; false before the odometer is set). `FACTOR_RANGE` narrowed to 0.9–1.1.
- **UI (`ui/garage/FuelUi.kt`):** `FuelSection` in the bike detail (`garage_range`, `garage_refuel`,
  `garage_fuel_line`, `garage_since_refuel`, `garage_cost_toggle` → `garage_cost` / `garage_refill_estimate`,
  `garage_refuel_row` with two-tap `garage_refuel_delete`); `RefuelDialog` (`garage_refuel_dialog`,
  `_full/_partial`, `_fuel_<TYPE>`, `_mode_RUPIAH|LITRES`, `_amount`, `_price`, `_result`, `_odo`, `_save`);
  `UntrackedKmDialog` (`garage_untracked` button, shown once the odometer is set). Costs only behind the toggle.
- **Trip recovery (`history/TripCheckpoint.kt`):** `TripRecorder` writes `TripCheckpoints` every
  `CHECKPOINT_EVERY_NANOS` (60 s) and at pause/resume, off the collector; clears it on end / discard.
  `TripRecorder.recover()` (from `MainActivity.onStart`, once per process): checkpoint under
  `RESUME_WITHIN_MS` (10 min), same boot → `TripTracker.restore` (next fix joins in a straight line) +
  `TripService.start`; otherwise saved as ended at the last checkpointed fix, `lastOutcome` untouched.
  `ResetSettingsRule` clears the checkpoint (`SettingsEntryPoint.liveTripDao()`).
- Device tests: `GarageScreenTest` (fill-up → range, hidden costs, two-tap delete; untracked km). Buttons low in
  the scrolling bike detail need `performScrollTo()` before the click.

## Phase 11c: Garage card, services, Garage backup (built 2026-11-09, beyond the handoff)

- **Room v5** (`MIGRATION_4_5`): `service_items` (`ServiceItem`: bike, name, `ServiceKind` DISTANCE / DATE,
  km and month intervals, last done km / time, due date) and `service_records` (`ServiceRecord`: item, bike,
  item name copied, time, odometer, cost, note). `GarageDao.markDone` is one `@Transaction`; deleting a bike
  deletes its services and records. `MigrationTest` opens v1 under v5.
- **`garage/Service.kt` (pure, `ServiceTest`):** `serviceDue` → `ServiceDue` (`DueLevel` OK / SOON / OVERDUE /
  NO_INTERVAL); amber within 10% of the km interval (at least 100 km) or 14 days, date items 30 days.
  `byUrgency`, `reminderOf`, `nextDueDate` (rolls a yearly date forward past today), `parseDate` (d/m/yyyy).
- **`GarageRepository`:** `services`, `serviceHistory`, `add/update/deleteService`, `markServiceDone`
  (odometer within 1 km of the estimate = the estimate, else stored as a reading), `activeCard` →
  `GarageCardState` (bike, odometer, fuel, services; `nextService`, `reminder`).
- **Garage pane (`Pane.GARAGE`, `ui/garage/GarageCard.kt`):** on demand from the pane picker (Quad's default
  keeps the clock). Small: 5-segment `TankGauge` (`garage_tank`, `tankSegments` with 2% hysteresis, red last
  segment) + one value (`garage_card_value`) chosen by `SettingsRepository.garageValue` (`GarageValue`: range,
  since refuel, odometer, next service, km/L). Expanded / Fullscreen (`garage_card_full`): Refuelled, Service
  done, bike chips, the value picker, Open Garage. No bike → Open Garage only.
- **Warnings elsewhere:** `LowFuelIcon` (`low_fuel`) on the speed card when the tank is in its last segment and
  the Garage pane isn't on screen; `ServiceBadge` (`service_reminder`) on the log face and trip summary when
  a service is due soon or overdue. No Android notification.
- **Services UI (`ui/garage/ServiceUi.kt`):** `ServicesSection` in the bike detail, `ServiceEditor` (name chips,
  km or date kind, two-tap delete), `ServiceDoneDialog` (odometer, cost, note) and the history list.
- **Backup format 2 (`garage/GarageBackup.kt`):** `backup.json` gains `garage` (bikes, readings, refuels,
  prices, services, records). Import merges: bikes by id (an imported bike stays inactive if one is already
  active), readings / refuels by (bike, time), prices only if newer, services by (bike, name, created), records
  by (bike, time, name). `TripFiles.import` → `ImportResult` (trips, Garage entries). Format-1 files still
  import. Tests: `GarageBackupTest`, `TankSegmentsTest`, service cases in `GarageRepositoryTest`
  (`FakeGarageDao`); device: `GarageScreenTest` (service add / due / done, log-face badge, Garage card +
  low-fuel icon).

## Phase 12a: true black, drag to swap, adaptive refresh (built 2026-11-09, v3.3)

Rider-Feedback "Revisions after the first look" R3 and R4.
- **`AppColors.card`** (new token): dashboard cards. #000000 in `OledDark`, white in `Light`. `surface`
  stays #0A0C10 for sheets and dialogs, so they still stand apart from the black dashboard. Things drawn
  to match a card's fill use `card` (clock hand hub, trip summary, the map header's fade).
- **Drag (`ui/dashboard/PaneDrag.kt`):** `Modifier.paneDrag` on each pane's slot, *before* the
  `graphicsLayer` that moves the card (so the finger is measured in unmoved coordinates).
  - It watches touches in `PointerEventPass.Initial` without consuming them until the hold
    (`SettingsRepository.dragHoldMs`, 1/2/3 s, default 1) completes. A move past touch slop, a second
    finger or a lift before then leaves the touch to the card (pager swipes, scrolling, buttons).
  - After the lift it consumes everything. A drop on another card calls `layout.assign(source, target)`,
    which swaps them, and the existing rect springs slide both. The lifted card's offset springs back to
    zero with the same spring, so it glides from under the finger into place.
  - Let go without moving = the pane picker. The map lifts from its header strip only (`MAP_DRAG_HEADER`,
    64 dp): its body keeps its long-press for destinations.
  - `PaneDragState`: holding / lifted / target / settling, `holdProgress` (ring, starts after 150 ms),
    `offset`. The lifted card gets zIndex in the layout pass. `DragOverlay` draws the ring and the
    target's cyan outline in its draw pass only.
  - **Removed:** long-press = fullscreen on the body (fullscreen is its button) and the header's
    long-press = picker. The header carries an accessibility custom action "Choose what this pane shows".
- **Refresh (`ui/components/FrameRate.kt`):** `AdaptiveRefreshRate(setting, demand, moving)`.
  - Pure policy: `refreshTargets` (fastest / slowest at the current resolution), `preferredModeId`
    (motion → fastest in every mode; idle: Auto → 0 = system, Power saver → slowest, Smooth → fastest),
    `lingerMs` (Auto 500, Power saver 200).
  - Motion = a pane sliding, `PaneDragState.active`, or `MotionDemand` (`LocalMotionDemand`). The map
    engines set `MAP_MOTION` from camera move-start/idle (MapLibre) or `isMoving` (Google, in a
    snapshotFlow, not in composition).
  - The display comes from `window.decorView.display`, so it works below API 30 too (the rider's phone is
    API 33).
  - Settings: **Screen options** dialog (`settings_screen_options` → `screen_options`, Done
    `settings_screen_options_done`) in the Display column holds refresh rate (`settings_refresh_<MODE>`),
    hold time (`settings_drag_hold_N`) and the battery switch (`settings_battery`, moved there). Rows added
    straight to the sheet pushed Battery and Voice guidance below the fold in landscape.
- Tests: `SmoothnessTest` (policy), `PaneDragTest` (drop target per layout). Device: hold-and-release
  opens the picker without zooming, drag swaps with the map kept, an early swipe is the card's own,
  fullscreen by its button. Tests hold with `down` / `advanceEventTime(1_300)` / `up`.

## Phase 12b: range, gauge level, dash km/L, period totals (built 2026-11-09, v3.3)

Rider-Feedback "Revisions after the first look" R1 and R2.
- **Room v6** (`MIGRATION_5_6`): `fuel_marks` (`FuelMark`: bike, time, odometer, bars, ofBars, litres) and
  `dash_kmpl` (`DashKmPerL`: bike, time, kmPerL, tripKm). Deleting a bike or `clear()` removes them.
  Not in the backup (format 2 unchanged; see DECISIONS 12b).
- **Set fuel level (`garage/Fuel.kt`):** `gaugeBars(model)` (NMAX gen 1 = 6, else 8; per bike after
  Phase 12), `gaugeLitres` counts the last lit bar as half used. `fuelLevel(..., marks)`: a mark newer
  than the last full fill is the starting point (`FuelLevel.fromGauge`); away km after it = unknown.
  `GarageRepository.setFuelLevel` treats the odometer like a refuel's (within 1 km = the estimate).
  UI: `FuelLevelDialog` (`garage_level_dialog`, `garage_level_bar_<n>`, `garage_level_litres`,
  `garage_level_odo`, `garage_level_save`) from `garage_set_level` (bike detail) and
  `garage_card_set_level` (expanded Garage card).
- **Cautious range:** `Consumption.cautiousKmPerL` = min(long-run estimate, mean of the last
  `RECENT_TANKS` = 3 used tanks); the repository burns the level at that figure, and
  `FuelLevel.cautiousRangeKm` rounds the range down to 5 km. Every range on screen uses it.
  `kmplSource` / `cautiousNote` explain the figure (`garage_card_kmpl_source`, the fuel line).
- **Dash km/L:** `learnConsumption(..., dash)` folds dash figures in by time with `DASH_WEIGHT` 0.15
  (plausible range, ±35% outlier once learning started); `Consumption.dashUsed`. Asked on the trip
  summary of a **saved** trip when a bike exists: `sum_dash` → `DashKmPerLDialog` (`kmpl_dialog`,
  `kmpl_value`, `kmpl_save`, `kmpl_skip`); afterwards `sum_dash_saved`. One answer per trip.
- **Period total (R2):** `TotalPeriod` DAY / WEEK (from Monday) / MONTH (calendar) in
  `SettingsRepository.totalPeriod`, chosen in Trip options (`settings_period_<NAME>`).
  `history/PeriodTotals.kt` (pure): trips count for the period they started in.
  `TripLogViewModel.periodM` = saved trips + the running / paused trip, re-read each minute.
  The log face's `log_period` ("Today 42.3 km", speed unit's distance) replaces "Recent rides":
  Compact shows it unless a service reminder is due; Expanded / Fullscreen always.
- Tests: `FuelLevel12bTest`, `PeriodTotalsTest`, a `GarageRepositoryTest` case; device:
  `GarageScreenTest.theGaugeSetsALevelBeforeTheFirstFullFill`, `TripSummaryDashTest` (stateless card).

## Phase 12c: fuel prices (built 2026-11-09, v3.3, versionCode 18)

- `FuelType`: Pertalite, Pertamax (92), **Pertamax Green**, Pertamax Turbo; defaults are the rates of
  2026-11-09 (10.000 / 15.950 / 19.150 / 19.600), used only until the rider has a price. `brand` / `grade`
  give the two-line button label (`FuelLabel`, via `Segments(label = ...)`).
- **Fuel prices** in the bike detail (`garage_price_<TYPE>`, `garage_price_value_<TYPE>`) →
  `FuelPriceDialog` (`garage_price_dialog`, `garage_price_input`, `garage_price_save`) →
  `GarageRepository.setPrice` (the same `fuel_prices` store a fill-up updates). Past refuels keep their price.
- "Refuelled" → "Refuel"; bike models "NMAX Gen 1" / "NMAX Gen 2". Custom fuel types: WIP (prices are
  keyed by name, so no migration needed later).

## App font: Geist (applied 2026-11-09, after 11c)

- **Geist** (Vercel, OFL; licence in `assets/licenses/geist-OFL.txt`), bundled as static TTFs from the `geist`
  npm package 1.3.1: `res/font/geist_regular|medium|semibold|bold.ttf`. `Geist` (`ui/theme/Type.kt`) is set on
  every Material style, `PaneTitle` and `TabularNumbers`. Bare `TextStyle(...)` doesn't inherit the theme, so
  the speed hero (`heroStyle`), clock digits and Garage card value set `fontFamily = Geist` themselves; any new
  bare `TextStyle` must too. All four faces have `tnum`. Map labels keep the map's own fonts.

## Phase 13a: fixes and tweaks (built 2026-11-14, v3.4.1, versionCode 19)

Rider feedback after a week on v3.3 (Rider-Feedback "Phase 13 decisions"). Choices and reasons: DECISIONS.md "Run 13a-14a".
- **Changelog (`changelog/`, `ui/changelog/`):** `assets/changelog.md` (`## <name> (<code>) · <date>` + bullets, newest
  first). **Every sub-phase build adds its entry** with the version bump; `ChangelogTest.theShippedChangelog…` fails
  if the top entry isn't the current `versionName` / `versionCode`. `ChangelogStore` (own DataStore key
  `changelog_seen_code`, outside `SettingsRepository` so the fakes stay as they are); `resolveLastSeen`: fresh install
  (`InstallInfo.isFreshInstall`: first install time == last update time) counts as seen, an update from a build without
  a changelog is `BEFORE_CHANGELOG` (18). `ChangelogSheet` (`changelog_sheet`) after an update: only entries newer than
  last seen, at most 3, never while a trip runs / is paused or navigation is active (`ChangelogViewModel.pending`).
  Settings "What's new" (`settings_whats_new` → `changelog_history`) shows the whole file. `ResetSettingsRule` marks
  the current version seen.
- **Navigation pane:** "Next turn" is now **Navigation** (title, picker; `NextTurnCard`, tags `next_turn_*` and
  `Pane.NAV` kept). Compact / Wide: the arrow is centred on the distance + road block and the arrival line starts at
  that block's left edge.
- **Header (`CardChrome`, `HeaderRow`):** the title is measured first; the battery, then the corner clock, are left out
  when there's no room (title never cut: tag `header_title_<PANE>`). `DashboardLayoutState.canSwap`: the swap button
  exists only in Side by side, Rows and on Duo's tall pane (Quad and Duo's stacked panes: drag or the picker). A
  fullscreen pane drops Maximize (its fullscreen button keeps toggling back). So a Compact header has at most
  maximize + fullscreen of its own. Accessibility action "Choose what this pane shows" kept.
- **Map hold:** the map's long-press pin only in fullscreen (`pinEnabled`); elsewhere the whole map card is held like
  the others (and a pan still wins). A fullscreen map still lifts from its header only.
- **Drag (`PaneDrag.kt`):** `pickDropTarget`: the card covered most (≥ 20% of the smaller of the two), else the card
  under the finger, else the nearest centre once the lifted card travelled half the way to it. Two-step hold:
  lifted (lit outline, one buzz; letting go does nothing) → `ARM_MS` 700 ms more without moving = picker armed
  (second ring in the card's centre, card dims to 80%, double buzz; letting go opens the picker). Moving at any
  time drops back to dragging. The arm timer is a coroutine in `PaneDragState`, not a pointer timeout.
- **Speed card:** always opens on the speed face (the pager state is no longer saved; an Ended trip found at first
  composition no longer jumps to the log face; one that ends while the card is up still lands on it). The dash
  km/L button is on the Compact summary (beside the figures) and on the Wide one, as it already was on Expanded.
- **Garage:** "+ Add bike" is the last card of the bike list (`garage_add`). `TankIcon` GAUGE (default) / PLAIN (fixed
  pump icon, red in the last segment), chosen in the expanded Garage card (`garage_tank_icon_<NAME>`),
  `SettingsRepository.tankIcon`.
- **Refuel:** one switch "Filled to the top" (`garage_refuel_full`, default on). Litres run 0..tank size
  (`refuelLitresFit`), never limited by the estimate. `countsAsFull`: an entry over the estimated room left (+0.2 L)
  is stored as a full anchor, so the level caps at full and the ±35% rule decides whether that span teaches km/L.
- **Muted voice no longer ducks music:** Ferrostar's `AndroidTtsObserver` asks for audio focus before it checks its
  mute flag. `FerrostarNavigator` now detaches the observer while muted (and creates the TTS only when needed).
- Device tests moved with this: stacked panes are swapped by dragging (`drag(from, to)`), pickers need
  `PICKER_MS` (hold + arm), the refuel dialog uses the switch.

## Phase 13b: compact navigation map and auto-start (built 2026-11-14, v3.4.2, versionCode 20)

- **Compact map while guiding (`MapPane`, `NavigationChip`):** route line + one slim chip (`nav_chip`: arrow + distance)
  at the top left; no banner, no summary card. The header's own buttons are **mute** (`nav_voice`, a switch) and
  **End** (`nav_end`), via `DashboardCard(headerActions)` (a pane that sets it drops its maximize / fullscreen / swap
  buttons from the header: the pane picker has **Fullscreen** and **Maximize** buttons, `pick_fullscreen`,
  `pick_maximize`). The bottom-right big button is the follow button (`map_recenter`, always there, navy + accent
  while following). Expanded / Fullscreen keep the banner, ETA card and the FAB column. The chip shows only
  where the Navigation pane isn't on screen (`showNavPanels`).
- **Auto-start (`trip/AutoStart.kt`, `location/AutoStartMonitor.kt`):** `AutoStartMode` OFF (default, and what every
  install keeps) / ODOMETER_ONLY / TRIP, hold 20 / 30 / 45 / 60 s (`autoStartHoldS`), in the Trip options dialog.
  Pure `stepAutoStart` (speed ≥ 15 km/h held; no-speed or inaccurate fixes skipped; a slower fix or a 10 s gap
  starts over; distance through `TripReducer`), `autoStartAllowed` (clean state only). The monitor runs **only while
  the activity is started** (`repeatOnLifecycle(STARTED)` in `MainActivity`): no service, no background location.
  Clean = the app was opened (`onAppOpened`, not a rotation) with no trip, and none has ended since
  (`endedThisSession`, seen while the monitor is running). TRIP: `TripService.start`, then `announcements`.
  ODOMETER_ONLY: the km (counted from the start of the hold) go to the active bike as untracked km in chunks of
  ≥ 500 m, the rest when riding stops (2 min still) or the watch is cancelled; nothing while a trip exists.
- **Low-rate watching (`SpeedSampleSource.watchSamples`):** `FusedSpeedSampleSource` asks for balanced-power fixes every
  5 s while stationary and switches the same callback to 1 Hz high accuracy once a fix moves (speed ≥ 2.5 m/s, or a
  position jump > 120 m: network fixes carry no speed), back after a minute without movement. Others reuse `samples`.
- **Announcement (`ui/speed/AutoStartBanner.kt`):** `AutoStartBannerViewModel` (3 s timer in the view model) → the speed
  card slides a "Trip started / Riding detected" banner over its faces (`auto_start_banner`, swipe to dismiss, a
  short buzz); with no speed card on screen the dashboard shows `auto_start_pill` at the top centre.
- Device tests: auto-start end to end on the scripted drive (banner, pill), the settings choice, the hold menu, and a
  compact map guiding on a real route (skips itself offline).

## Phase 13c: settings categories and colours (built 2026-11-14, v3.4.3, versionCode 21)

- **Settings (`ui/settings/SettingsSheet.kt`):** categories in the header (`settings_cat_<NAME>`, `SettingsCategory`):
  Display (theme, Colours, Screen options, What's new), Layout (map width, hold time), Navigation (speed unit, auto
  layout), Trip and Garage (history, Garage, reset, trip options; auto-start mode + hold), Audio (voice guidance),
  Backup (`BackupSection`: Back up / Import, the same `TripFiles` as the history's buttons) and Debug (debug builds).
  Each page is two short columns on a landscape phone (one when < 600 dp) inside `settings_page_<NAME>`; the
  sheet opens on Display. Screen options is refresh rate + battery only; Trip options is auto-pause, auto-end,
  period total and the figure chips. Device tests pick a category first (`openSettings("LAYOUT")`).
- **Accent (`ui/theme/AccentColor.kt`):** the `cyan` token is now `accent`. `AccentPresets` (green, cyan, ice blue, violet,
  magenta; each a dark colour + a hand-picked light variant; no amber / red), `parseHexColor` (6 digits, or 3 as
  shorthand; the field applies only a full 6), `contrastRatio` (WCAG), `lightVariant` / `darkVariant` (a custom colour is
  darkened for white / lightened for black until it reads), `accentFor(chosen, isDark)`, `accentWarnings`. `AppColors.withAccent`;
  `NativeAutoTheme(mode, accent)`; `MainActivity` collects `SettingsRepository.accentColor` (read once up front so
  the first frame is right). The map's vehicle dot stays cyan.
- **Fresh install vs existing install:** `SettingsRepository.initialiseDefaults(freshInstall)` runs in
  `NativeAutoApp.onCreate` (before anything else writes). `initialiseAccent`: nothing stored + no saved preferences + a
  fresh install = **green**; anything else = **cyan** (`LEGACY_ACCENT_ARGB`); a stored accent is never touched.
  Unit-tested on a temp DataStore (`AccentColorTest`). Pattern for later settings: give the interface member a
  default getter equal to the old behaviour, so the test fakes stay untouched.
- **Colours dialog (`ColoursDialog`):** accent presets + hex field (`accent_hex`, error `accent_hex_error`, warning
  `accent_hex_warning` when hard to see on black or white), route line: "Follow the accent" switch (`route_follow`),
  else presets + hex (`route_hex`, `route_preset_<key>`). `SettingsRepository.routeColor` (null follows the accent);
  `MapLook.withRoute` carries it into both map engines through `MapPane`.

## Phase 14a: call controls (built 2026-11-14, v3.5.1, versionCode 22)

Modular across Android skins (HyperOS, near-stock, One UI) and VoIP apps (WhatsApp, Teams, Zoom): only platform APIs an
ordinary app has, no per-skin code. Choices and reasons: DECISIONS.md "14a"; what only the phone can settle is listed
under "Concerns to check at the phone test (run 13a-14a)".
- **Pure model (`call/CallModel.kt`, `CallModelTest`):** `CallInfo` / `CallPhase`, `NotificationFacts` → `classifyCall`
  (a `CallStyle` notification says ringing / ongoing / screening itself; older ones by category `call` and by labelled
  actions, whole-word matches in English and Indonesian: `findAnswerAction` etc.), `pickCall` (ringing first),
  `mergeCall` (the notification's call, else one made from the cellular state), `callCapabilities` (each control only
  if some backend can do it), `formatCallDuration`.
- **Backends:** `NotificationCallSource` (fed by `MediaListenerService`, which since 14a also reads **call**
  notifications: only those `classifyCall` accepts are kept, in memory, with their Answer / Decline / Hang up
  `PendingIntent`s from the CallStyle extras or the labelled actions; nothing stored or sent), `PhoneCallSource`
  (READ_PHONE_STATE: ringing / off-hook; ANSWER_PHONE_CALLS: `TelecomManager.acceptRingingCall` / `endCall`),
  `CallAudio` (`AudioManager`: mic mute read back after the change, a phone that ignores it loses the button; route
  list from `availableCommunicationDevices` on API 31+, earpiece / speaker before). `CallRepository` puts them together
  (`state: CallState?`); actions go to the notification's own intent first, the telephony API second.
- **Call card (`Pane.CALL`, `ui/call/CallCard.kt`):** ringing: Decline + Answer; on: Mute (a switch), Audio output (one tap
  cycles the routes), End; name, app, "Incoming call" or the duration (`CallViewModel` ticks it each second, in the view
  model). Tags `call_name`, `call_app`, `call_status`, `call_answer`, `call_decline`, `call_mute`, `call_audio`,
  `call_end`, `call_route`.
- **Takeover (`LayoutModel.kt`, `DashboardLayoutState.callActive`):** while a call rings or is on, `view` is the arranged
  view with `Pane.CALL` in the slot of the first pane in `CallTakeoverOrder` (media, clock, Garage, speed, Navigation,
  map) that has room (`callTakeoverPane`; a pane folded away by a maximized neighbour doesn't count). **Never over a
  fullscreen pane**: with one, the card waits and comes when it ends. The rider's arrangement (`assignments`) is never
  touched, so the pane comes back exactly when the call ends. A zoom on the card is stored on the pane under it
  (`real`). `CALL` is not a picker choice and can't be dragged. `DashboardRoute` syncs `callActive` from
  `CallViewModel.active`.
- **Settings, Audio:** "Call controls" (`CallControlsRow`, `CallSettingsViewModel`): says what works, and a button
  (`settings_call_permissions`) asks for READ_PHONE_STATE + ANSWER_PHONE_CALLS (never at startup).
- Device tests (`CallCardTest`): real CallStyle notifications posted from the test (needs the debug-only
  USE_FULL_SCREEN_INTENT permission and the app-op), answer / decline / hang-up arrive as broadcasts, the card takes
  media's slot and gives it back, never a fullscreen card, mute only where it takes effect.

## Phase 14b: portrait (built 2026-11-14, v4.0, versionCode 23)

Major drop: a vertically mounted phone. Spec `.dev/Phase-14b-Portrait.md`; choices and reasons in DECISIONS.md "14b".
- **Orientation (`OrientationMode`, `SettingsRepository.orientationMode`, Settings, Layout):** LANDSCAPE (default for everyone, no re-setup), PORTRAIT,
  AUTO. Fixed modes ask for sensor-landscape / sensor-portrait and ignore the lock. AUTO follows the phone's rotation lock (lock on: no request, the
  system's orientation holds; unlocked: `device/OrientationPolicy` turns after a steady lean, `OrientationTuning` ZONE_DEG 30, STEADY_MS 1500, MIN_IN_PLANE 3;
  rider tunes on the bike). `device/OrientationController` runs under `repeatOnLifecycle(STARTED)`; `MainActivity` requests a fixed mode before the first frame.
  Manifest `configChanges`: a rotation never recreates the activity (map, trip, navigation carry on; panes slide).
- **Layout model:** `LayoutPreset` gained `STACK1..STACK4` and `BIG_TWO` (`portrait = true`); `computeRects` has a portrait branch (`portraitRects`, `BIG_TWO_TOP`
  0.55). `DashboardLayoutState.portrait` / `setPortrait` turn the layout with `rotatedView` (same panes, same order, by count; the last used shape wins);
  each orientation's last preset is saved (`SavedLayout.portraitPreset`, key `layout_preset_portrait`; `preset` stays the landscape one). No Maximize in
  portrait; swap only for two stacked panes. Auto layout: STACK4 takes `NavQuad`, a map-less portrait layout goes to BIG_TWO.
- **Card modes:** new `Mini` (4-stack) and `Narrow` (half width under the big pane); a portrait row of three is `Wide`, one or two stacked panes `Expanded`.
  `LocalPortrait` (provided around the whole dashboard, sheets included) shapes Wide / Expanded / Fullscreen for portrait. Mini / Narrow headers carry no pane
  buttons (status corner and a map's own header actions aside); Fullscreen is in the hold menu.
- **Cards:** speed `SpeedBeside` (value centred left, figures `StatLines` on one left edge) / `SpeedBelow` / `SpeedBelowGrid`, unit always visible; Navigation
  `CenteredTurn` (glyph, road, distance, arrival line, one centre axis, tags `next_turn_glyph|road|distance|eta`); media `MediaSlim` / `MediaNarrow` with
  `SwipeToChange` (64 dp, tag `media_swipe`, cue `media_swipe_cue`, no skip buttons), `MediaFullscreenTall`; log face / summary use the small centred form;
  clock and Garage Mini / Narrow forms; call card controls in the lower part, name above the duration.
- **Map:** narrow = recentre only; guiding adds End (two taps) in the header. Fullscreen portrait: `map_search` bar on top, guiding bottom row (figures + Mute +
  End left, traffic + recentre right, tops aligned).
- **Dialogs / sheets (`ui/components/AppDialog.kt`):** `AppDialogSurface` (portrait: bottom sheet up to 90% high), `DialogTwoColumns` (stacks in portrait),
  `PinnedActionDialog` (footer action; used by `GarageDialog`). Pane picker is a 2-column grid (`picker_grid`), the layout switcher wraps its thumbnails,
  history detail stacks (map, figures, pinned actions), Garage bikes are a sideways row. Settings gained the Orientation row.
- **Call pill (`CallPill`, `call_pill*`):** over a fullscreen pane the call is a slim top pill (ringing: Decline + Answer; on the call: Mute, End).
- **Refuel:** "Filled to the top" is now "Full refill" (logic unchanged).
- Tests: `PortraitLayoutTest`, `OrientationPolicyTest` (unit); `PortraitCardsTest` (stateless alignment, units, swipe), `PortraitDashboardTest` (rotation keeps the
  map, Mini headers, grid picker, bottom-sheet dialogs), pill cases in `CallCardTest`. `ResetSettingsRule` resets the orientation to Landscape.

## Phase 4.1: app icon (built 2026-11-15, v4.1, versionCode 24)

- Source `nativeauto.svg` (repo root, mark only: cyan `#4adffc` + white `#fdfdfd`). Generated by `.dev/scratch/icon.py` into vector drawables:
  `ic_launcher_foreground` (mark scaled so its farthest point is 31.5 dp from the centre of the 108 dp icon, inside the 33 dp safe circle),
  `ic_launcher_monochrome` (same shapes, one colour, for Android 13+ themed icons), `ic_launcher_background` (solid black). Adaptive only
  (minSdk 26): the old webp mipmaps are gone. Play Store icon: `app/src/main/ic_launcher-playstore.png` (512 px, black square).
- The icon's cyan is the logo's own; the app accent default is `#3FC8E0` (below).

## Phase 4.1.1: follow-ups (built 2026-11-15, v4.1.1, versionCode 25)

- **Accent default is cyan `#3FC8E0` for every install** (`DefaultAccent`; `initialAccent` ignores its arguments). Green stays a preset. (Replaces
  13c's "fresh install = green".)
- **Settings:** tab order Trip and Garage (opens here), Display, Layout, Navigation, Audio, Backup, Privacy and data (`SettingsCategory.PRIVACY`),
  Debug. Trip page: left Garage + Reset trip (note under the button), right Trip history, Trip options, `settings_auto_start` switch (+ "Start after"
  `settings_auto_start_hold_N` while on). Auto-start is on/off: `AutoStartMode.ODOMETER_ONLY` is still in the enum (monitor code) but a stored
  one reads as TRIP and the UI sets only TRIP / OFF. Privacy page: `settings_privacy_permissions`, `settings_privacy_notifications` (system
  screens), `settings_reset_app` (two taps via `SettingsViewModel.onResetAppTapped`, then `ActivityManager.clearApplicationUserData()`).
  Audio: `settings_call_controls` switch (`SettingsRepository.callControls`, default true; `CallRepository` shows no call when off).
  Device tests: `openSettings()` without a category now picks Display (the sheet itself opens on Trip and Garage).
- **Portrait maximize by pane count (`canMaximize`, `portraitRects`):** 2 panes none; 3 or 4: the maximized card takes `2/n` of the height, the others
  share the rest as `Mini` rows (3 stacked: 2/3 + two 1/6; 4 stacked: 1/2 + three 1/6). In "big + two" the big pane has no button.
  A tap on a card maximizes it where it can.
- **Portrait title spacing:** in portrait `CardChrome` adds 6 dp above the title and a 48 dp minimum header (Mini: 2 dp only).
- **Portrait layout switcher:** one row per layout (thumbnail 54x96 dp, name beside it), scrolls if tall.
- **Empty Garage:** one `garage_add` button (editor with the model menu opens); `garage_add_<MODEL>` buttons are gone.

## Phase 4.2: first-run setup pages (built 2026-11-15, v4.2, versionCode 26)

- **When:** only on a new install (or after Reset app). `setup/SetupState` (own key `setup_done` in the settings DataStore, outside
  `SettingsRepository`): `start()` runs first thing in `NativeAutoApp.onCreate`, before `initialiseDefaults` writes anything; an empty DataStore
  means pending (`setupPendingFor`), any saved preference means done, and the answer is stored so an update half-way keeps it. `MainActivity`
  shows `SetupScreen` instead of `PermissionsGate { DashboardRoute() }` while pending; `SetupViewModel.finish()` completes it. The dashboard's
  permission gate still asks for location afterwards if the rider continued without it.
- **Pages (`ui/setup/SetupScreen.kt`, tags `setup_page_N`, `setup_next`, `setup_back`, `setup_finish`):** 0 Welcome (launcher mark, Next only),
  1 Appearance (orientation / theme / units dropdown rows, `setup_orientation` etc.; Portrait turns the screen at once through the normal
  setting), 2 Location + Notifications (toggles ask Android's prompt; Notifications = POST_NOTIFICATIONS then, if still missing, the notification
  access screen; Next with either off asks "Go back / Continue", `setup_confirm_*`), 3 Calls (call controls setting, READ_PHONE_STATE,
  ANSWER_PHONE_CALLS), 4 Bike (`setup_add_bike` opens the Garage's `BikeEditor`; then the bike card + "Add another bike"), 5 Riding (auto-start,
  Start after, auto layout, voice), 6 Ready (check / dash summary, "Start riding"). Landscape is two columns, portrait one stack; rows 48 dp,
  buttons 52 dp, title 28 sp, body 16 sp, no scrolling (fits 411 dp tall). A toggle that is already on opens the system screen to turn it off.
- **Tests:** `SetupStateTest` (unit), `SetupFlowTest` (device: every page fits with Back / Next in view in both orientations, finishing leaves the
  dashboard, Back, Portrait turns the screen, Riding writes settings, bike card). `ResetSettingsRule` marks the setup done; `ReleaseSmokeTest.launch()`
  walks the pages when a cleared install shows them (UiAutomator can't see the empty container tag, so it keys on `setup_next`).

## Known issues (deferred)

- **Visible map costs ~1 CPU core continuously on the emulator** (about 10% with the map hidden);
  cold start is ~14 s there. MapLibre with real tiles costs the same as Google without a key, so
  the missing key isn't the cause; likely the emulator's software renderer redrawing for the
  1 Hz location updates (the emulator re-sends its last fix forever). Re-measure on the user's
  phone when the app is complete.

## Future features (decided, not scheduled)

- **In-pane turn-by-turn navigation** (Android Auto–style) via the Google **Navigation SDK**,
  rendered inside the map pane. It's a separate product with its own terms and per-trip pricing,
  not covered by free map display. Out of scope for v1 (handoff §1 non-goals). Embedding the
  Google Maps app in the pane is not planned (Android doesn't allow it). Added 2026-11-02 at the
  user's request; revisit after the app is complete.
  Free alternative for the MapLibre engine: **Ferrostar** (open-source, BSD) with a Valhalla or
  OSRM routing server (public demo servers have fair-use limits; or self-host). No live traffic.

## 4.2.1: auto-resume, 10 km/h, landscape media swipe (built 2026-11-16, versionCode 27)

- **Auto-resume (`trip/AutoStart.kt`: `autoResumeAllowed`, `AUTO_RESUME_HOLD_CHOICES_S` 5/10/20, default 10):**
  `AutoStartMonitor.watch()` runs `watchResume()` beside the auto-start watch; a paused trip (manual or auto) resumes
  through `AutoStartActions.resumeTrip()` (`TripService.resume`) after `stepAutoStart` holds. App open only.
  `SettingsRepository.autoResume` / `autoResumeHoldS` (defaulted interface members). Trip options row
  `settings_auto_resume_<0|5|10|20>` (0 = off). `monitor.resumed` → banner "Trip resumed" (`BannerMessage`).
- `AUTO_START_KMH` = 10 (was 15).
- **Media:** landscape Compact / Wide / Expanded / Fullscreen wrap in `SwipeToChange` (`SwipeCentred`), skips kept.
  Test: `PortraitCardsTest.landscapeMediaCardsChangeTrackBySwipeAndKeepSkips`.

## Run 4.3 (built 2026-11-24, unattended; choices in DECISIONS.md "Run 4.3")

### 4.3a: consistency fixes (v4.3, versionCode 28)
- **Settings sheet (`SettingsSheet.kt`):** one height per orientation (94 % landscape, 80 % portrait), the page scrolls inside;
  the tab row fades at its edges (`fadingEdges`); 32 dp between the title and the tabs. Tests scroll a tab into view before
  tapping it (`tab()` / `openSettings`). **One row pattern:** `SettingRow` (title + hint left, control right, 64 dp min);
  `ActionRow` and the new `DangerRow` (Reset trip / Reset app: "Reset" → red "Tap again") are built on it; Backup is two
  `ActionRow`s in `PageColumns` (`backup_export`, `backup_import`).
- **Pane picker:** no drag handle; Fullscreen / Maximize tiles (`pick_fullscreen`, `pick_maximize`) only for a card whose
  header has no zoom buttons of its own. `CardChrome` writes that into `LocalHeaderZoom` (a map provided by the dashboard).
- **Low fuel:** the pump is a header slot (`HeaderRow` `fuel`, `StatusCorner.lowFuel`), dropped first when short; the floating
  icon on the speed card is gone.
- **Trip summary:** `DashTile` ("DASH KM/L" + "+ Add" plate, `sum_dash`; `sum_dash_saved` once answered) inside the figures in
  every mode; Rows top-aligned.
- **Navigation:** `SideTurn` for wide (≥ 520 dp) landscape Expanded / Fullscreen (`next_turn_side`); `nav/RiderPhrasing.kt`:
  "Bear" → "Slight" on the banner (`toInstruction`) and the voice (`RiderPhrasingSpeaker` wraps the observer); `thenLine`.
- **Design screenshots:** `DesignShotsTest` / `DesignCardShotsTest` run only with
  `-Pandroid.testInstrumentationRunnerArguments.designShots=1`; shots land in `/data/local/tmp/design/` (pull with
  `MSYS_NO_PATHCONV=1 adbe pull`).

### 4.3b: look (v4.4, versionCode 29)
- `SettingsRepository.cardTitleStyle` (`CardTitleStyle` TEXT / ICON, Display "Card titles", `settings_card_titles_<NAME>`) and
  `paneWatermark` (Display "Faint card icons", `settings_pane_watermark`), both defaulted members. They reach the cards as
  `LocalCardLook` (`CardLook`, from `DashboardViewModel.cardLook`). Icon titles keep the tag `header_title_<PANE>` and the pane
  name as content description. The watermark (`WATERMARK_ALPHA` 5.5 % of `text`) is drawn behind Expanded / Fullscreen media,
  clock, navigation and Garage cards only (not map, speed, call), in `drawBehind`.
- Media previous / next are `FilledIconButton`s on `buttonQuiet` (52 / 60 dp).

### 4.3c: daily greeting (v4.5, versionCode 30)
- `greeting/Greeting.kt`: pure `shouldGreet`, `greetingFor(hour)`; `GreetingStore` (`greeting_last_day`, own key like the
  changelog's). `GreetingViewModel.onOpened()` runs under `repeatOnLifecycle(STARTED)` from `GreetingSheet`; the day is marked
  when it shows; busy (trip running / paused, navigation) waits for a later open; it closes 8 s after a trip runs. Finishing the
  setup marks the day. The changelog sheet waits while it is up.
- `ui/greeting/GreetingSheet.kt` (`greeting_sheet`, `greeting_title|bike|range|odometer|week|service|done|add_bike`); figures
  beside the range when ≥ 600 dp. Setting `dailyGreeting` (default on) on **Trip and Garage** (`settings_daily_greeting`).
- `ResetSettingsRule` marks today greeted (`SettingsEntryPoint.greetingStore()`); `GreetingSheetTest` clears it and moves the
  activity to CREATED and back to RESUMED to simulate an app open.

### 4.3d: riding load in the range (v4.6, versionCode 31)
- `garage/RidingLoad.kt`: `LoadAccumulator` (O(1) per accepted fix, fed by `TripRecorder.Recording.add`), `LoadSums`
  (`energyPerKm` = J/kg/km), `spanLoads` / `fitLoad` (weighted least squares, ≥ 4 spans, slope < 0, beats the mean),
  `recentLoad` (live trip + newest trips to 20 km), `rangeLoad` (0.8–1.2), `RangeLoad.note`, `LiveRidingLoad` (live sums,
  published at the 60 s checkpoint).
- `SpeedSample.altitudeM`; **Room v7** (`MIGRATION_6_7`: `trips.loadIndex`, `loadDistanceM`, `loadAero`, `loadAccel`,
  `loadClimbM`, `loadStops`, nullable); `GarageDao.observeTripLoads`; backup fields optional (format 2).
- `FuelLevel.loadFactor` multiplies the **range** only; `FuelState.load`; the note `garage_load_note` under the range (bike
  detail, expanded Garage card) beyond ±5 %.
- **Line endings:** Python on Windows writes CRLF in text mode. Edit files with the Edit tool, or `open(p, newline='')`
  keeping the file's own endings; check `git diff --numstat` before a commit.

## Run 4.6.1-4.6.3 (built 2026-11-24, unattended; choices in DECISIONS.md "Run 4.6.1-4.6.3")

**Code and unit tests only so far.** The emulator was off (stopped for low memory; restart only when the rider asks), so device
tests, design shots, the release smoke test and the APK export are pending. Hand over no APK of these before the smoke test.

### 4.6.1 (versionCode 32)
- **Speed or log face (`ui/speed/SpeedFace.kt`, replaces 13a's "always opens on the speed face"):** `SpeedFaceState` (pure) +
  `RidingDetector` (10 km/h for 3 s; the first good fix after opening needs no hold). Stationary / paused / ended = log, riding or
  opened with a running trip = speed; never back to log in traffic; a swipe by hand wins until the next open or trip change. No
  location = speed face (it holds the fix). Tests: `openSpeedFace()` first when a test needs the digits.
- **Low fuel:** `bikes.lowFuelKm` (Room **v8**, `MIGRATION_7_8`, default 30), `FuelLevel.low` is the only rule (header, card, gauge,
  bike detail, greeting). Editor `garage_low_<20|30|40|50|OTHER>`, `garage_low_input` (20 to 150).
- **`RANGE_FOLLOWS_RIDING = false`** until 4.7 (`GarageRepository.rangeFollowsRiding` for tests); trips still record the load.
- Period total is a tile (`log_period`) on Expanded / portrait Fullscreen log faces. Settings sheet: `sheetGesturesEnabled = false`.

### 4.6.2 (versionCode 33)
- `SettingsCategory`: TRIP_GARAGE, DISPLAY, LAYOUT ("Map and layout"), OTHERS, DEBUG. Trip automation (`settings_trip_automation` →
  `trip_automation`): auto-start, resume, pause, end. Trip figures keeps the tags `settings_trip_options` / `trip_options`. Accent
  (`settings_colours` → `colours_dialog`) and route colour (`settings_route_colour` → `route_dialog`) are separate dialogs.
- `GreetingMode` (`settings_greeting_<NAME>`); "every open" needs `GreetingViewModel.awayMs` (60 s) away. Call card switch on Display
  (`settings_call_controls`), call permission on Others (`settings_call_permissions`, `settings_call_status`).

### 4.6.3 (versionCode 34)
- `ui/theme/PortraitSizes.kt`: the portrait sizes by role; `portraitSizes(mode)`; `LocalSlimRow`. New portrait sizes go there.
- Never run a release build while editing sources: lint crashed once when files changed under it.
