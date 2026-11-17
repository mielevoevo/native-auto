# Unattended run: Phase 15a–15e (iOS), 2026-11-16 → 2026-11-17

Mac: Xcode 27.2, simulator **iPhone 17** (iOS 27.2). Commits: `Native Auto iOS 0.1` (15a) … `Native Auto iOS 0.5` (15e).
The run is complete: 15a–15e built, **346 unit tests and 14 UI tests green** on the simulator. Nothing was installed
on a phone and nothing pushed.
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

## 15d (v0.4, build 4)
**Pure core (`Core/History/`, `Core/Garage/`), ported with its Android tests:** trip records and the recorder (save on
End, path from accepted fixes, Douglas–Peucker, short trips dropped, bike tag, checkpoint and recovery, addresses),
GPX, the ZIP backup (format 2, Android-compatible), period totals, day groups; the Garage: odometer estimate and the
learned factor, away / untracked km, fuel level and consumption (full-to-full, partials, gauge marks, dash km/L,
cautious range), refuel amounts and spend, services (due levels, date items rolling on), the Garage backup and merge,
the Garage repository. **335 unit tests** (109 new: `TripRecorderTest`, `GarageRepositoryTest`, `OdometerTest`,
`FuelTest`, `FuelLevel12bTest`, `TankSegmentsTest`, `ServiceTest`, `GarageBackupTest`, GPX / backup / ZIP, an
Android-style backup fixture, the track blob).

**App:**
- Storage: SwiftData (`native-auto.store` in Application Support), tracks as one blob per trip, the live trip
  checkpointed to a file every minute. A trip ended (or the app swiped away) is saved at once; a trip lost with the
  process is resumed within 10 min, else saved.
- Trip history (Settings → Trip history): day groups with totals, cards with a map snapshot of the route and the
  addresses, the detail with the chosen figures, Export GPX (share sheet), Delete (two taps), bike chips with 2 bikes.
  Back up / Import here and in Settings → Backup (share sheet / Files).
- Garage (Settings → Garage, or the Garage card): bikes (add, edit, delete, ride), odometer (set, correct, untracked
  km, the log, the learned GPS factor), fuel (range, Refuel with full refill / paid or litres / price / odometer, Set
  fuel level from the gauge bars, costs on demand, fuel prices, the refuel log with two-tap delete), services (items by
  km / months or by date, Done with odometer / cost / note, history). The away prompt ("Gen 2's odometer now?").
- Garage card pane (from the picker): tank gauge + one chosen value when small; range, facts, Refuel, Set fuel level,
  Service done, bike choice, the value and tank-icon choices, Open Garage when expanded.
- Speed card: "Today 42.3 km" on the log face (Day / Week / Month in Settings), the service reminder badge, a red
  tank when fuel is low and no Garage card is shown, the summary's route sketch and "Bike's km/L", "Too short to save".
- **2 new UI tests** (`GarageHistoryUITests`): a bike from the Garage card, a fill-up → 235 km range on the screen and
  the card; a short scripted trip saved, opened, deleted with two taps. All 10 UI tests pass.

## 15e (v0.5, build 5)
**Pure core:** `CallModel` (call phases from CallKit's facts, ringing-first pick, duration format), the changelog parser
and "what's new since" rules, the first-run rule, the share hand-over (`Shared/ShareLink.swift`). **346 unit tests**
(11 new: `CallModelTests`, `ChangelogTests` incl. the shipped file matching the build, `SetupRuleTests`,
`ShareLinkTests`).

**App:**
- Media card: Apple Music's track, art, previous / play-pause / next; the output picker (AirPods, helmet, speaker) and
  the volume for any app; one line saying Spotify and other apps can't be controlled on iOS. Every card size.
- Call card: "Incoming call" / "Calling…" / "On a call" + the time, taking media's slot (Android's takeover order),
  a pill at the top over a fullscreen card; the rider answers and ends with the phone or the headset.
- Settings in pages (Trip and Garage first, Display, Layout, Navigation, Audio, Backup, Privacy and data, Debug):
  route line colour, call card switch, auto-start on / off + Start after, What's new, open the iPhone's Settings,
  Reset app (two taps).
- Auto-start (off by default): a trip starts once 15 km/h is held for the chosen time, while the app is open, with a
  "Trip started" pill.
- First-run pages on a new install: Welcome, Make it yours, Location, Media and calls, Add your bike, Riding, Ready.
- "What's new" sheet once after an update (never during a trip or navigation); the iOS changelog file.
- App icon from `nativeauto.svg` (black, 1024 px).
- Share extension: Google Maps → Share → Native Auto opens the app with that place's route preview.
- Fixed: the compact speed card cut off "No fix" / "Lost" (Quad); the big media card overflowed in Side by side.
- **4 new UI tests** (`FirstRunUITests`): the first-run pages through to the dashboard, the changelog once after an
  update, the settings pages (route colour, What's new, call switch, Reset app's arm), a call taking media's slot and
  the pill over fullscreen. All 14 UI tests pass.

## Not possible / not ported on iOS
- Rotation-lock-following AUTO with Android's lean-and-hold policy: iOS applies the lock itself, so Auto just follows
  the phone. Refresh-rate control (ProMotion adapts by itself). The double-back close flow (no back button).
- Background tracking: trips and the live speed run while the app is open (iOS stops GPS in the background without
  "Always" location, which we don't ask for). Same rule as Android, but iOS also pauses the app sooner.
- **Media from other apps:** no app can read or control Spotify, YouTube Music etc. on iOS (Android uses notification
  access). Apple Music only, plus the output picker and volume for everything.
- **Calls:** no caller name, no Answer / Decline / End, no mic mute or audio route: iOS tells an app only that a call
  rings or is on (CallKit). The card shows that and the time.
- **Notification access, baseline profiles, refresh-rate control, the debug Search test screen:** Android-only.
- **Reset app** can't wipe the app like Android's `clearApplicationUserData`: it deletes its own files and settings and
  quits (iOS has no other way to restart clean).
- **The share extension opening the app** uses a common workaround (no official API); if a future iOS blocks it, the
  extension copies the link and says to paste it in search.

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

**15d on the iPhone:**
- **Backup moved from Android:** Android Settings → Backup → Back up, send the ZIP to the iPhone (Files / AirDrop),
  iOS Settings → Backup → Import. Trips, tracks (Export GPX of one), bikes, refuels, services should all arrive; then
  the reverse direction.
- End a real ride: the summary, the trip in the history with its addresses and map; the Garage odometer moving on.
- Swipe the app away mid-ride: the trip should be saved (check the history). Force-quit during a ride and reopen
  within 10 min: it should carry on.
- The Refuel dialog with gloves; the range against the bike's own gauge over a few tanks.

**15e on the iPhone:**
- **Install fresh** (delete the old one first) to see the first-run pages and the new icon; then the location prompt
  from the Location switch, the Apple Music prompt from the Media page.
- **Share from Google Maps:** a place → Share → Native Auto (scroll the app row / "More" the first time) → the app
  opens with the route preview. If the app doesn't open, the extension says "Copied": paste in Where to?. Tell me
  which happened.
- **A real phone call** (ring yourself): the call card in media's place, the time running, back to media after; in a
  fullscreen card, the pill. Then turn Audio → Call card off.
- **Media:** Apple Music play / pause / skip and the art; the output picker switching to the helmet; Spotify playing
  with the card's note (and the output picker still working for it).
- **Auto-start** (Settings → Trip and Garage): ride off with it on, about 30 s after 15 km/h the "Trip started" pill.
- The changelog sheet after updating from 0.4 (shows 0.5 once).

**15c on the iPhone:**
- A real route: the voice (English, and that music ducks only while it speaks), rerouting after a wrong turn,
  arrival, the motorcycle costing keeping off toll roads.
- Holding the **map body** (not its header) 1 s to lift it: SwiftUI's hold over MapLibre's own gestures is untested
  by the UI tests (they drag other cards onto the map). If it doesn't lift, the header strip does.
- Long-press to drop a pin in fullscreen; the follow glide's smoothness at 1 Hz; the map's battery and heat cost.

## Left half-done / notes
- No traffic layer (OpenFreeMap has none) and no Google engine on iOS.
- Version 0.5 is not parity with the Android app yet (1.0 = parity, per PLAN.md). Still missing from Android 4.2: the
  portrait media card's swipe-to-change (14b; for Apple Music it could be added) and the call card's controls (iOS
  forbids them).
- Accent / route hex fields apply on Return or Apply, not as you type (Android applies a full six digits at once).
- The share extension, Apple Music and CallKit were checked only through tests and simulated states: the simulator
  can't ring a call or play Apple Music, and the share path from Google Maps needs the real app.
- Swiping the app away saves the trip only if iOS delivers `willTerminate` (it usually does for a foreground app);
  otherwise the minute checkpoint saves it at the next launch.
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
