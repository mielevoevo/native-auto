# Decisions made without asking (unattended phases)

During the unattended run (8d → 11c), choices that would normally go to the user are taken here with
the recommended option. Review them before the phone test; any of them can be changed.

Format: **sub-phase · decision** — why, and how to change it.

## Setup (2026-11-09)

- **8d–11c · One phone test at the end** — the user's call, replacing the roadmap's per-phase tests.
- **10a · TomTom key is a placeholder** — the user adds the real key to `local.properties` at the next
  supervised session. Until then search falls back to Photon.
- **10b · HERE skipped** — the roadmap already deferred it until the user's card issue is solved.
- **9a · Auto-pause 5 min / auto-end 30 min** — the proposed values in Rider-Feedback.md ("to confirm");
  editable in settings.

## 8d · Smoothness

- **Release build signed with the debug key** — no release keystore exists, and a sideloaded personal
  app doesn't need one. Same signature means a release APK installs over the debug one (emulator and
  phone). Change: add a real keystore in `app/build.gradle.kts` before ever publishing.
- **The phone test should use the release APK** (`app/build/outputs/apk/release/app-release.apk`) —
  the debug build has no R8 and no baseline profile, the first suspect in §12.1. Debug-only tools
  (Fake GPS, Simulate navigation) aren't in it; install the debug APK for those.
- **Map during a move: laid out at the full window, centred and clipped** (rather than a snapshot
  freeze) — no frozen picture, the map keeps drawing; only two resizes per move.
- **Refresh rate: highest mode while panes move, system default 0.5 s after** (rather than 120 Hz
  always) — saves power on 1 Hz data. Done through `preferredDisplayModeId`, which works on every API
  level the phone has.
- **Thermal: logged only** (`adb logcat -s NativeAuto/Thermal`), no on-screen warning.
- **Benchmarks run on the emulator only** (workspace rule); numbers are relative. Real 120 Hz frame
  times need the phone, at the end-of-run test.
- **Emulator benchmark results (2026-11-09), relative only:** every move sits at SwiftShader's floor
  (CPU frame time P50 44–59 ms at 60 Hz), so the emulator can't show the 120 Hz gains. Settings open/close:
  P50 53.5 ms without the profile → 48.7 ms with it (about −9%). Startup timing and the profiled
  maximize/swap runs didn't complete there: the pre-9a trip service kept the process alive across "cold"
  starts, and one run lost a button under emulator load. Not chased further. The real check is the phone
  (release build, `adb shell dumpsys gfxinfo com.nativeauto` after maximizing / opening settings).


## 9a · Trip state machine

- **Idle speed card: "No trip running · Start trip"** in the hero, styled like the Paused / Ended
  prompts — a stopgap until 9b's log face (▶ ⏸ ⏹). The hero shows the live speed (the map's GPS read)
  without a trip, at the prompt's smaller size.
- **Auto-pause choices Off / 3 / 5 / 10 min (default 5); auto-end Off / 15 / 30 / 60 min (default 30)**,
  as segmented rows in the settings' Trip column. Checked every 10 s. Auto-pause doesn't auto-resume
  (that would need the GPS kept on while paused); the card says "Auto-paused: no movement".
- **Short trips: kept only from 300 m *and* 2 min of moving time** (the roadmap's "under ~300 m or under
  ~2 min" read as "either one drops it"). Constants in `TripRecorder`.
- **Self-close 10 min after a notification End** (the short end of "10–15 min"; the GPS is already off).
  Coming back to the app cancels it. Ending inside the app never self-closes.
- **Closing the app saves the trip**: swiping it away (`onTaskRemoved`) and the activity really finishing
  both end and save. The Save / Discard / Cancel prompt on double back comes with 9b's back hint.

## 9b · Log face, summary, closing

- **Faces switched by a pager** (swipe), with two small dots under the card. The speed face opens
  first; with no trip it shows the live speed full-size and no prompt (9a's "Start trip" hero prompt
  is gone). Paused keeps its Resume prompt on the speed face. After a trip ends, the card lands on the
  log face.
- **Log face controls:** ▶ / ⏸ / ⏹ as 64 dp icon buttons, never hidden, dimmed when they can't act.
  ▶ is Start or Resume. ⏹ turns red on the first tap; a second within 3 s ends (same as Reset).
- **Log face content per size:** compact = controls + time · distance; wide = controls + the inline
  figures; expanded = controls + the six tiles + the last 3 rides; fullscreen = the same side by side.
- **Summary:** covers the card while the trip is Ended (any end: log face, notification, auto-end,
  save prompt). Cyan "Trip complete" title (the card's one cyan element), the figures counting up over
  0.7 s, the route sketch when saved or "Too short to save". Tap or horizontal swipe dismisses it,
  which zeroes the trip (Idle).
- **Back hint:** an in-app pill "Press back again to close" for 2 s (not a system Toast, so it matches
  the theme and tests can see it). Only when back has nothing else to do (no zoom, history or sheet).
- **Save prompt:** a dialog with Save / Discard / Cancel when closing with a trip running or paused;
  closing uses `finishAndRemoveTask` so the app really goes (Android 12+ would otherwise only send a
  root activity to the background).
