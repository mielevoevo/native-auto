# Native Auto iOS (for Claude Code on the Mac)

Read `ios/PLAN.md` (phase plan, iOS limits, versioning) and `ios/README.md` (Mac setup). The root `CLAUDE.md` is the
Android app's history and spec; its Windows-only workspace rules (`.dev/env.sh`, emulator, drive D) don't apply on
the Mac.

- Build: `cd ios && xcodegen && xcodebuild -scheme NativeAuto -destination 'platform=iOS Simulator,name=iPhone 17' test`
  (XcodeGen is at `~/bin/xcodegen/bin/xcodegen` on the rider's Mac; iPhone 17 is the installed simulator). The test
  action runs the unit tests (`NativeAutoTests`) and the UI tests (`NativeAutoUITests`, real holds, drags and taps on
  the simulator). Install on the phone from Xcode (⌘R).
- Simulator checks: launch arguments set any setting for one launch (UserDefaults argument domain), e.g.
  `xcrun simctl launch <sim> com.mielevoevo.nativeauto -use_fake_gps 1 -debug_start_trip 1 -layout_preset QUAD`.
- Panes are placed with `.position`; any modifier that hit-tests (gestures, taps, accessibility) must come before it,
  or it covers the whole dashboard (15b).
- Never commit `NativeAuto.xcodeproj` (generated) or `Local.xcconfig` (the rider's team ID).
- **Privacy (rider, 2026-11-16):** never write the rider's bike plate initials, home base or real email into files or
  commits. Bikes in tests and examples are "Gen 1" / "Gen 2". Commits use the GitHub no-reply address.
- Git: branch `main`, one commit per finished sub-phase, push to the private GitHub repo when the rider says so.
  Licence is WIP (see `Rider-Feedback.md`): keep MIT for now, don't add another.
- `ios/NativeAuto/Core/` is pure Swift (Foundation only), ported from the Kotlin with its tests; UI and platform code
  live elsewhere. Keep the figures identical to Android (same thresholds, same filters).

## Unattended run 15a → 15e (rider, 2026-11-16)

The rider starts it once and checks on the phone at the end. Same rules as the Android unattended runs:
- **Don't stop to ask.** On a choice, take the default below or the recommended option, and log it with the *reason* in
  `DECISIONS.md` under "Phase 15 (iOS)" (the rider reads it later). A failing build or test is fixed at once, never
  handed back. Simulator only; no device install, no `git push`.
- **Per sub-phase:** build + all unit tests green on the simulator → bump `MARKETING_VERSION` (15a 0.1, 15b 0.2, 15c 0.3,
  15d 0.4, 15e 0.5) and `CURRENT_PROJECT_VERSION` (1..5) in `project.yml` → one commit "Native Auto iOS 0.N: …" → next.
- **Port by reading the Android code** (`app/src/main/java/com/nativeauto/...`) and the root `CLAUDE.md` section for that
  feature; port its unit tests from `app/src/test/` alongside each pure-logic file. UI: SwiftUI, look per root
  `CLAUDE.md` "Phase 8c" / "12a" (true black, one accent per card, 56 dp ≈ 56 pt touch targets for gloves).
- **If something truly can't be done** (a library won't build, an API doesn't exist on iOS): build the closest
  version, log it, move on. Never stall the run.

### Defaults for the open choices
- **Storage:** SwiftData (iOS 17, no dependency). Settings: `UserDefaults` behind one `SettingsStore`.
- **Libraries (Swift Package Manager, in `project.yml` `packages`):** MapLibre
  (`https://github.com/maplibre/maplibre-gl-native-distribution`) and Ferrostar (`https://github.com/stadiamaps/ferrostar`,
  its Swift package; FOSSGIS Valhalla `motorcycle` costing, Photon search, same User-Agent and rate rules as Android).
  Pin exact versions. Google Maps / TomTom: not on iOS for now.
- **Backup:** same ZIP format as Android (format 2), so a backup moves between the phones; GPX identical.
- **Media card (15e):** iOS can't read or control other apps' playback. Card = system output picker
  (`AVRoutePickerView`) + volume, plus play / pause / skip and title for **Apple Music** only
  (`MPMusicPlayerController.systemMusicPlayer`); otherwise a short note "Spotify can't be controlled on iOS".
- **Call card (15e):** only "a call is on" from `CXCallObserver` (no answer / end; iOS forbids it). Takeover rule as Android.
- **Font:** Geist TTFs from `app/src/main/res/font/` copied into the iOS target, registered in the Info.plist keys.
- **App icon:** from `nativeauto.svg` (repo root), black background, 1024 px asset.
- **Not ported:** Android-only parts (notification listener, call answering, refresh-rate control, baseline profiles,
  debug search test screen); log each in DECISIONS.md.

### At the end
Write `ios/RUN-REPORT.md`: what each sub-phase built, what's not possible on iOS, what to check on the phone
(GPS, rotation, map, navigation on a real route, backup moved from Android), and anything left half-done.
