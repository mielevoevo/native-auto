# Native Auto

Read `NativeAuto-Handoff.md` first. It holds the architecture rules, layout spec and phase plan.

- Package / applicationId: `com.nativeauto`
- Build: `./gradlew assembleDebug` · Unit tests: `./gradlew testDebugUnitTest`
- **Versioning (user rule, 2026-11-08):** Phase 8b shipped as `versionName` 2.0. Each later sub-phase
  build adds 0.1 to the minor version (8c → 2.1, 8d → 2.2, …) and 1 to `versionCode`, in
  `app/build.gradle.kts`, once that sub-phase's tests pass.
  **Phase 11 is a major version (user, 2026-11-09):** 11a ships as 3.0, then +0.1 again (11b → 3.1,
  11c → 3.2); `versionCode` keeps adding 1 (10b = 11, 11a = 12).
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
  `local.defaults.properties` (placeholders), `CLAUDE.md`, `Rider-Feedback.md`, `DECISIONS.md`,
  `.gitignore`, `.dev/`. Plus Claude Code's own memory folder. Scratch work goes in `.dev/scratch/`.
- On C, only *run* the Android SDK and Android Studio's JBR (installed there); never write there.
  Known unavoidable: the emulator drops a tiny "running" marker in C's temp folder.
- Kotlin compiles in-process (`gradle.properties`), so no Kotlin daemon writes to `%LOCALAPPDATA%`.
  The debug keystore in `.dev/android-user/` is a copy of the original, so builds stay signed alike.
- **Git:** local repo, branch `main`, one commit per finished sub-phase. No remote, never push
  (blocked in `.claude/settings.local.json`); the user decides on GitHub.
- **Unattended phases (8d → 11c):** don't stop to ask. Take the recommended option and log it in
  `DECISIONS.md`. The user tests on the phone once, after 11c.

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

## Phase 8d: smoothness (built 2026-11-09, beyond the handoff)

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

## Known issues (deferred)

- **TomTom key pending (WIP, 10a):** add `TOMTOM_API_KEY` to `local.properties`, rebuild, compare on the
  Search test screen.

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
