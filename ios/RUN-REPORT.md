# Unattended run: Phase 15a + 15b (iOS), 2026-11-16; 15c, 2026-11-17

Mac: Xcode 27.2, simulator **iPhone 17** (iOS 27.2). Commits: `Native Auto iOS 0.1` (15a), `Native Auto iOS 0.2` (15b),
`Native Auto iOS 0.3` (15c).
Choices and reasons: `DECISIONS.md` → "Phase 15 (iOS)".

## 15a (v0.1, build 1)
- The Windows-written project generated with XcodeGen and compiled first time; all 16 tests passed unchanged.
- Checked line by line against the Kotlin: `TripReducer`, auto-start, polyline / simplify use the same constants.
- Added `Local.xcconfig` (git-ignored) from the example with an empty team ID: enough for the simulator.

## 15b (v0.2, build 2)
**Pure core (`NativeAuto/Core/`, Foundation only), ported with its Android tests:**
- Trip life cycle `TripTracker` (start / pause / resume / end / discard / restore, Android's retry rules for location
  off, no permission and other errors), `autoAction` + `AutoTrip` (auto-pause 5 min, auto-end 30 min, checked every
  10 s), the scripted fake drive, and the SI → display mapping (`toUiState`, stale rule, telemetry, `formatElapsed`).
- Layout: panes, the four landscape presets and five portrait ones, `computeRects` (incl. portrait maximize 2/n),
  `DashboardLayoutState` (zoom, swap, picker, rotation, auto-layout override, call takeover), `SavedLayout` with
  Android's keys and codec text, `AutoLayout`, `pickDropTarget`.
- Theme maths (accent presets, hex, WCAG contrast, light / dark variants), clock edge geometry, hand turns, time / date
  formatting.
- **186 unit tests**: the full Android `TripReducerTest`, `AutoStartTest`, `TripTrackerTest`, `AutoTripTest`,
  `SpeedometerUiMappingTest`, `CardFormattingTest` (minus media), `LayoutModelTest`, `DashboardLayoutStateTest` (minus
  the Compose Saver), `CanSwapTest`, `PaneDragTest` / `DropTargetTest`, `PortraitLayoutTest`, `CallTakeoverTest`,
  `AutoLayoutTest`, `AccentColorTest` (minus the DataStore migration), `EdgeGeometryTest`, `ClockFormatTest`.

**App (SwiftUI):**
- Dashboard: every pane always present and placed from its rectangle; layout changes slide with one spring; cards on
  true black with a 1 pt outline; Geist everywhere (bundled TTFs).
- Card header: title, swap / maximize / fullscreen where Android has them; the top-right card carries the status
  corner (corner clock when no clock pane shows, battery, layouts, settings).
- Drag to swap: hold 1 / 2 / 3 s (ring), the card lifts (haptic), drag onto another card to swap; held still another
  0.7 s it arms (second ring, dims, double haptic) and letting go opens the pane picker. In portrait a tap maximizes.
- Speed card: speed face (hero per mode, landscape and portrait forms) and log face (Start / Pause / Resume / End with
  two taps), swiped; the summary while a trip is ended (figures count up, tap to dismiss).
- Clock card: digital and the analog edge-tick face with the optional accent second hand, chosen in the large card.
- Themes Dark (true black, default) / Light / System; accent presets + hex with readability warnings.
- Orientation Landscape (default) / Portrait / Auto; rotation keeps the same panes in the same order.
- Settings sheet: theme, unit, accent, battery; orientation, hold time; auto-pause, auto-end, figures, Reset trip (two
  taps); debug Fake GPS.
- Screen stays on while the app is open; swiping the app away ends the trip.
- Map, navigation, media, Garage and call panes are placeholders that say which build brings them.
- **5 UI tests** (`NativeAutoUITests`) on the simulator: trip life cycle through the log face, drag to swap, picker
  after a still hold, layout switcher to Quad, rotation in Auto mode.

## 15c (v0.3, build 3)
**Pure core (`Core/Nav/`, `Core/Map/`), ported with its Android tests:** nav models and `NavState`, distance / duration /
ETA formatting and banner text, Photon parsing, Google Maps / `geo:` link parsing, the simulated drive, the follow
camera, recent places. **226 unit tests** (37 new: `ParsePhotonTest`, `MapsLinkTest`, `NavFormatTest`,
`SimulatedDriveTest`, `FollowCameraTest`, plus recent places, renames, the switchable source).

**App:**
- Map pane: MapLibre 6.31 with OpenFreeMap tiles (no key). Dark = the stock dark style recoloured to true black with
  the roads lightest (Android's layer table); light = Positron. Cyan dot with a heading chevron, follow camera
  (heading-up, 45° tilt, breaks on a pan or pinch, Recenter restores it), route in the accent over a dark casing, red
  destination pin, OpenStreetMap credit kept. The map sleeps 30 s after leaving the screen and wakes where it was.
- Navigation: Ferrostar 0.59 with FOSSGIS Valhalla **motorcycle** costing, Photon search. Search sheet (recent places,
  results after a pause in typing, Paste for a Maps link) → route preview (Cancel / Start) → guidance: banner, ETA
  card, Mute / End / Recenter on a big map; one chip plus Mute / End in the header on a small map; English voice,
  muting never ducks music; reroutes at least 8 s apart. Failures say why (no GPS yet, offline with Retry, no route).
- Navigation pane: "Where to?" when idle, the same preview and status cards, the turn face in every card size (Compact,
  Wide, Expanded / Fullscreen with "Then …" and End, portrait Mini / Narrow / row centred).
- Auto layout: navigation start shows the map (Quad → map, Navigation, speed, media), the end gives the rider's layout back.
- Links: pasted text or Maps links in search, `geo:` URIs, `nativeauto://open?text=…`; a status pill while one resolves.
- Long-press the map in fullscreen to drop a destination pin (named afterwards from Apple's geocoder or Photon).
- Settings: Navigation (voice guidance, auto layout), Debug (Simulate navigation).
- **3 new UI tests** (`NavigationUITests`, live services, skip offline): link → preview → guidance → End; the Navigation
  pane taking over from a small map; search → preview. All 8 UI tests pass.
- Fixed on the way: the live GPS read could fail to start at launch (15b timing bug), launch-argument switches,
  parked panes exposing a second "Start" to VoiceOver. Android bug noted in DECISIONS (a `geo:` label lost).

## Not possible / not ported on iOS (so far)
- Rotation-lock-following AUTO with Android's lean-and-hold policy: iOS applies the lock itself, so Auto just follows
  the phone. Refresh-rate control (ProMotion adapts by itself). The double-back close flow (no back button).
- Background tracking: trips and the live speed run while the app is open (iOS stops GPS in the background without
  "Always" location, which we don't ask for). Same rule as Android, but iOS also pauses the app sooner.

## What to check
**In the Simulator now** (`open NativeAuto.xcodeproj`, run on iPhone 17):
- Settings → Debug → Fake GPS: the hero climbs to 100 km/h, two spikes never show, "Lost" during the tunnel.
  Or Simulator menu → Features → Location → Freeway Drive with Fake GPS off.
- Swipe the speed card left: Start, Pause, Resume, End (twice); the summary; tap to dismiss.
- Hold a card 1 s and drag it onto another; hold still and let go for the picker; the layouts button.
- Settings: Light theme, the accent presets and a typed hex, Portrait / Auto (Device → Rotate in Auto).
- The analog clock: put the clock in Side by side, choose Analog and Second hand.

**On the iPhone later:**
- Real GPS speed against the bike's speedometer; the stale "--" in a tunnel or under cover.
- Rotation in Auto with the rotation lock on and off; the fixed modes ignore the phone.
- Drag with gloves: the 1 s hold, the swipe between faces not lifting a card, the header buttons' size.
- The safe-area margins either side of the Dynamic Island in landscape (look right, or wasted room?).
- Auto-pause after 5 min stopped, auto-end 30 min later (only while the app stays open).
- Battery reading in the corner (the simulator has none).

**15c on the iPhone:**
- A real route: the voice (English, and that music ducks only while it speaks), rerouting after a wrong turn,
  arrival, the motorcycle costing keeping off toll roads.
- Holding the **map body** (not its header) 1 s to lift it: SwiftUI's hold over MapLibre's own gestures is untested
  by the UI tests (they drag other cards onto the map). If it doesn't lift, the header strip does.
- Long-press to drop a pin in fullscreen; the follow glide's smoothness at 1 Hz; the map's battery and heat cost.
- Sharing from Google Maps: until the share extension (15e), copy the link and use Paste in "Navigate to…".

## Left half-done / notes
- Google Maps' share sheet can't target the app yet: a share extension is planned for 15e (paste works meanwhile).
- No traffic layer (OpenFreeMap has none) and no Google engine on iOS; the route colour setting comes with 15e.
- The trip isn't saved yet (history is 15d); ending or swiping away just ends it.
- The settings sheet is one page; the categories (13c) come with 15e.
- A static simulator location (simctl `location set`) can show "No fix": CoreLocation sends that fix once, cached, and
  fixes older than 10 s are skipped on purpose. Moving locations (Freeway Drive) or Fake GPS work.

## Things that slowed the run (worth fixing before 15c–15e)
- `ios/CLAUDE.md` named an "iPhone 16" simulator; this Mac has iPhone 17 / 17e / Air / 18 Pro. Updated to iPhone 17.
- XcodeGen's generated Info.plist pinned version 1.0 over `MARKETING_VERSION`; fixed in `project.yml`
  (`CFBundleShortVersionString: $(MARKETING_VERSION)`).
- For 15c (MapLibre, Ferrostar via Swift Package Manager): the first package resolve needs network and can take
  minutes; Ferrostar's Swift package ships a binary core. If the run should not wait on GitHub, resolve the packages
  once by hand first (`xcodebuild -resolvePackageDependencies`).
- Permission prompts: none hit in this run with the current `.claude/settings.local.json`. 15c will also run
  `xcodebuild -resolvePackageDependencies` and maybe `curl` (checking FOSSGIS / Photon); allow those to stay
  unattended.
