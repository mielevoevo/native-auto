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

## 9c · Full track, migration, telemetry

- **Full track = every fix the reducer accepted**, unsimplified (rejected spikes and poor fixes left out,
  so it agrees with the distance). About 10 000 points for a 3-hour ride, held in memory until the trip
  is saved, then written with the trip in one transaction. Times are wall clock, derived at save time.
- **No foreign key** from `track_points` to `trips` (a simpler, safer migration); deleting a trip deletes
  its points in the same transaction.
- **Stopped time = trip time − moving time** (pauses already excluded), so it includes GPS gaps. Old trips
  are backfilled the same way by the migration. It adds no GPS use.
- **Bike tag:** `trips.bikeId`, nullable, always null until the Garage (11a) exists.
- **Telemetry show/hide:** seven figures; **moving time and stopped time hidden by default** (they're
  new), the rest shown as before. Applies to the log face, the summary and the history detail; the speed
  face keeps its fixed grid. The compact log line and the compact summary always show time and distance.
- **Settings layout fix:** since 9a the Trip column ran off a landscape screen (auto-end was cut off).
  Auto-pause, auto-end and the figure toggles now sit in one **"Trip options" dialog** (two columns, Done
  beside the title), so the sheet fits again in a release build.

## 9d · History by day, GPX, backup

- **Day headers:** "Today", "Yesterday", then "Tue 3 Nov" (year added when it isn't this year), each with
  the day's total distance, grouped by the phone's local day of the start. English labels, like the rest
  of the UI.
- **GPX only** (the default the spec names); KML and CSV left out (they were "optional"). GPX 1.1 with
  `<time>` per point and speed (m/s) and course (°) as plain `<extensions>` elements, which OsmAnd and
  most importers read. Trips from before 9c export their path without times.
- **Export goes through the share sheet** (Drive, Files, chat apps), from a cache file served by a
  FileProvider; each export clears the old cache files.
- **Backup ZIP = `backup.json` + `gpx/` (one per trip).** Import reads only the JSON (every figure and the
  full track, compact arrays), so a restore loses nothing; the GPX files are for other apps. No GPX
  parser needed.
- **Import skips trips already in the history** (same start time), so importing twice is harmless. A
  newer backup format is refused with a message. Settings (layout, units...) aren't in the backup; the
  Garage joins it in 11c.
- **Back up / Import buttons** sit in the history's top bar (56 dp); results show as a line next to the
  title.
