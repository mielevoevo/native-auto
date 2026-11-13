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

## 10a · Place search: TomTom behind `PlaceSearch`

- **WIP — TomTom key not added yet.** `local.defaults.properties` has the placeholder
  `TOMTOM_API_KEY=DEFAULT_TOMTOM_KEY`; `BuildConfig.HAS_TOMTOM_KEY` stays false until the user puts the real
  key in `local.properties` (`TOMTOM_API_KEY=...`, from developer.tomtom.com) and rebuilds. Until then search
  runs on Photon exactly as before, and the Search test screen says "No TomTom key yet". **To finish later:**
  add the key, run the Search test on the 20 queries, and confirm TomTom is the better default.
- **TomTom when it has a key, Photon otherwise** (`PreferredPlaceSearch`), and Photon whenever TomTom fails
  (offline, quota, bad key), so search is never worse than before. Reverse lookups follow the same order.
- **TomTom Fuzzy Search**, `typeahead=false` (the UI already waits for a pause in typing), biased (not
  filtered) to the rider's position, 8 results. POIs are named by the POI, addresses by the street.
- **Search test: a debug-only activity** (`src/debug`, not in the release APK), opened from the Debug row
  in settings. 20 fixed queries (13 Yogyakarta, 7 Jakarta: landmarks, abbreviations, small businesses,
  street + number, a gang, misspellings), each biased to its city centre; Photon and TomTom side by side
  with distance from the centre. Scored by hand; queries run only on a tap (fair use / quota).

## 10b · Google Maps links → our navigation

- **Followed the "Decided" section (option C reworked)** over §7's older "C dropped as a navigation
  feature": a place shared (or pasted) from Google Maps starts Native Auto's own Valhalla navigation. The
  roadmap row says the same.
- **Shared → route preview, not straight into guidance:** the rider still presses Start, as with a
  searched place (a wrong link shouldn't start talking). A status pill shows "Opening shared place…" or
  why it failed (5 s).
- **Entry points:** the share sheet (`ACTION_SEND` text/plain: Google Maps sends "name / address / link")
  and `geo:` links ("open with"). Plain shared text without a link is searched as an address. Pasting a
  link into the search box shows it as the one result. No `VIEW` filter for google.com/maps URLs (the app
  would be offered for every maps link in the browser).
- **Reading links:** the place pin (`!3d…!4d…`) beats the camera centre (`@lat,lon`); then `q` / `query`
  / `destination` / `daddr` / `ll` (coordinates, or a name → search, first result near the rider). The
  name comes from the share's first line, else the `/place/<name>` segment.
- **Short links** (`maps.app.goo.gl`, `goo.gl/maps`) are expanded by following redirects by hand (max 5)
  until a full maps URL, passing through Google's consent page via its `continue` parameter.
- **HERE skipped** (card issue, as planned).

## 11a · Bike profiles and odometer (v3.0)

- **No bikes are pre-made:** the Garage starts empty with three quick-add buttons (NMAX 2015–2019 =
  6.6 L, NMAX 2020+ = 7.1 L, Other scooter). You name them (Gen 1, Gen 2). Built-in "Gen 1"/"Gen 2" would be the
  user's private names in the code.
- **Your existing trips go to the first bike you add** (they were all ridden on one bike).
  Deleting a bike keeps its trips in the history, just untagged.
- **Colour / photo left out:** the bike's name shows everywhere, and bike colours would break the theme's
  status-colour rule (amber and red only, no green). Easy to add later.
- **Defaults:** reserve ≈ 20% of the tank (1.3 L / 1.4 L), the Garage card's red last segment in 11c; fuel
  Pertamax; starting 36 km/L (the spec's figure; 11b learns the real one). All editable in the bike editor.
- **Odometer = last reading + GPS km of that bike's trips since × a learned factor.** Each correction
  re-anchors the odometer and, over at least 30 GPS km, moves the factor halfway to the measured ratio
  (recent corrections count more). Ratios outside 0.8–1.2 are ignored as typos or forgotten readings. The
  Garage shows the learned drift ("GPS reads 2.0% short"). As decided, it doesn't touch the range estimate.
- **A reading taken mid-trip (at the pump) is handled exactly:** only the rest of that trip counts after it.
- **Away check after 3 days unused** ("after weeks" in the spec; 3 days catches a long weekend at home and
  the prompt is skippable): switching to a bike idle that long asks "Gen 2's odometer now?", prefilled. A
  higher reading is logged as **away km** (counts for servicing, no trips, and 11b will treat the fuel level
  as unknown until a full refuel); the same value means nobody rode it, so nothing is stored; a lower one is
  just a correction. Skip = counters carry on from the app's own km.
- **"Which bike?" (debug builds only):** at a trip's first fix, if the active bike's last saved trip ended
  more than 50 km away and you have two bikes or more. Compares two points on the phone; nothing is sent.
  Not in release builds until there's a disclosure and an opt-in (§6 privacy note).
- **Switching bike:** in the Garage (tap a bike → "Ride Gen 2"). The settings row shows "Riding Gen 1" and opens
  the Garage. The Garage card's quick picker comes with the card in 11c.
- **History filter by bike:** chips (All bikes · Gen 1 · Gen 2) and the bike's name on each trip, only once
  there are two bikes or more.
- **Odometer always in km** (bikes' own unit), even with mph set for speed.
- **Indonesian number style accepted:** "12.345" in an odometer field is 12,345 km; "6,6" in a litre field is 6.6 L.

## 11b · Fuel, untracked km, trip recovery (v3.1)

- **The untracked-km design (2026-11-09) shipped here**, as the user asked: "Add untracked km" in the bike
  detail, no prompts, the narrower factor band, km/L from odometer deltas, trip recovery.
- **Fuel prices to start from (corrected by the user, 2026-11-09):** Pertamax Rp 15.900 ("almost 16k"),
  Pertamax Turbo Rp 19.600, Pertalite Rp 10.000 (not confirmed). Each refuel stores the price typed at the
  pump for that fuel type, and that price is used from then on.
- **Refuel form:** Full tank (default) / Partial; fuel type (the bike's by default); amount as **Paid (Rp)**
  (default) or **Litres**, the other worked out from the price per litre; odometer prefilled with the
  estimate. An amount over twice the tank (Rp typed into Litres) isn't accepted.
- **The odometer at the pump:** within 1 km of the estimate it counts as the estimate (accepting the rounded
  prefill doesn't log a correction every fill-up); otherwise it's stored as a reading (a correction, which
  teaches the GPS factor, or the first reading).
- **km/L learning:** the first full-to-full measure moves the starting value halfway (it's a guess); later
  ones move it 30% (recent tanks count more, one odd tank doesn't swing it). Ignored: under 10 or over
  100 km/L, and, once learning has started, anything more than 35% off the current figure (forgotten fill-up,
  typo). Shown in the refuel log as "(skipped)". Spans with away km teach nothing.
- **"Correction doesn't change the range" (decided 2026-11-08), read as:** a correction never changes km/L.
  The range still follows the odometer (the agreed "real odometer is the source of truth"), so a correction
  upward means more km ridden since the fill and less fuel left.
- **Range before the first full fill-up:** "—" with "Log a full fill-up to start the tank estimate". After
  away km: "—" until the next full fill. Range turns red at ≤ 20% of the tank (the 11c tank's last segment).
- **Costs stay hidden:** the refuel log shows litres only. "Calculate refill" shows next refill (litres ×
  price = Rp), spend for the last 7 days / this month / last month, and Rp per km (first to last full fill).
  It closes again each time the Garage opens.
- **Range on the dashboard waits for 11c's Garage card** (the log-face stat was the other option in §6).
- **Untracked km:** at most 5,000 km per entry (a typo guard), stored in the existing `awayKm` column with
  kind UNTRACKED, so no reading migration.
- **Trip recovery:** checkpoint every 60 s and at each pause / resume. Checked once per process when the app
  becomes visible (the trip service may only start then). Resumed if the last checkpoint is under 10 min old
  and the phone hasn't rebooted since; otherwise saved as ended at the last checkpointed fix (no end-of-trip
  summary for it). Known edge: if the app is swiped away and the process dies before the save finishes, the
  next launch within 10 min resumes that trip; End then saves it.

## 11c · Garage card, services, backup

- **Quad's default keeps the clock;** the Garage pane is added from the pane picker. Changing a default
  layout under a rider who already arranged it felt worse than one long-press.
- **Tank gauge:** 5 segments, the last one red (matches the 20% reserve from 11b), 2% hysteresis so a
  reading near a boundary doesn't flicker. Unknown fuel draws empty outlines.
- **Service thresholds:** amber at 10% of the km interval (min 100 km) or 14 days left; date items
  (tax, insurance, licence) amber 30 days ahead. Overdue is red.
- **Date input** is typed as d/m/yyyy (Indonesian order), no date picker: one field, fits landscape, big keys.
- **Reminders stay in the app** (badge on the log face and summary, the Garage card's value) — no Android
  notification, which would buzz in a pocket while riding.
- **Marking a service done** at an odometer within 1 km of the estimate uses the estimate; anything further
  is stored as a reading (same rule as refuels, tighter).
- **Backup format 2** carries the Garage; import merges rather than replaces, so importing twice adds nothing.

## 3.2.1 · Release crash fix

- **Cause:** the v3.2 release APK crashed at start on every Android version (reproduced on the emulator),
  not because of Android 13: `minSdk` is 26 (Android 8) and lint finds no unguarded newer API. R8's
  `packageScope` optimization repackaged `kotlin.text.StringsKt__StringBuilderKt` to a top-level class
  while its package-private superclass stayed in `kotlin.text`.
- **Fix:** `-keeppackagenames kotlin.**,kotlinx.**,androidx.**` (keeps the optimization and shrinking;
  only stops moving library classes between packages). Not `optimization` off: 8d chose it for smoothness.
- **Version:** a fix, not a sub-phase, so 3.2.1 (versionCode 15); the next sub-phase stays +0.1 from 3.2.
- **Guard:** `ReleaseSmokeTest` (baselineprofile module) drives the minified build through every screen
  and fails on any crash; it failed on the broken build with the same `IllegalAccessError` and passes
  with the fix. Run it before every APK handed out.

## 12a · True black, drag to swap, adaptive refresh (v3.3)

- **Card colour** is a new token (`card`), not `surface`: sheets and dialogs keep #0A0C10 so they still
  stand out from an all-black dashboard.
- **Hold 1 s by default** (1 / 2 / 3 s setting), from anywhere on the card except the map's body, which
  keeps its long-press for picking a destination. The map lifts from its header strip.
- **Lift and let go = the pane picker.** That replaces the header's long-press, so there is one hold
  gesture. Long-press = fullscreen is gone; fullscreen is its button.
- **Moving before the hold completes belongs to the card** (pager swipe, scrolling): the gesture only
  watches until then.
- **Refresh, Auto idle = the system's choice**, not a forced 60 Hz. Forcing the lowest mode would also
  hold sheet and list scrolling at 60. Power saver does force it; Smooth keeps the fastest. Motion (a pane
  sliding, a hold / drag, the map camera) is the fastest mode in every setting.
- **Screen options dialog** for battery, refresh rate and hold time: three rows added straight to the
  sheet pushed Battery and Voice guidance below the fold in landscape (caught by
  `batteryIndicatorCanBeHiddenAndShownAgain`).

**Bugs found in 12a testing**
1. Settings overflowed in landscape (above). Fixed with the dialog.
2. Not the app: the freshly booted emulator froze its own system UI, launcher and phone services
   ("isn't responding"); their dialogs took focus and failed 23 tests with `RootViewWithoutFocusException`.
   After the emulator settled, all of them passed. When many tests fail with that error, look at the
   emulator first (`dumpsys window | grep mCurrentFocus`).

## 12b · Range, gauge level, dash km/L, period totals (v3.3)

- **Gauge bars to litres:** n bars lit = n − ½ bars of the tank (a bar stays lit until it's empty, so the
  middle of the last one, on the low side). 0 bars = 0 L.
- **"Other" bikes get an 8-bar gauge** until the per-bike setting (after Phase 12).
- **Cautious range** = the lower of the long-run km/L and the last 3 tanks' mean, rounded down to 5 km.
  The level burns at the same figure, so litres left and range agree. No band (rider's call).
- **Dash km/L weight 0.15** (a tank is 0.3): bike dashes usually read a little optimistic, and the figure
  covers whatever the dash's trip counter covers. Asked only after a **saved** trip, only with a bike, as a
  button on the summary (not a pop-up): skipping costs nothing.
- **Range stays in km** like the odometer and everything in the Garage (11a). The period total follows
  the km / mi setting like the other speed-card figures.
- **Period total placement:** Compact shows it unless a service is due (the reminder is rarer and
  matters more); Wide keeps its four figures. Chosen in Trip options, beside auto-pause / auto-end.
- **Gauge readings and dash figures are not in the backup** (format 2 unchanged). A gauge reading is
  replaced by the next full fill anyway; dash figures are a small nudge. Add them if a restore ever
  loses something that matters.

**Bugs found in 12b testing**
1. "Set fuel level" from the Garage screen opened with an empty odometer: the screen reads the estimate as
   a flow that answers after the dialog's first frame. The dialog now fills the field when the estimate
   arrives, unless the rider has typed already. Caught by `theGaugeSetsALevelBeforeTheFirstFullFill`.

## Concerns to check at the phone test (noted after 11b)

Things the emulator can't settle, or that I'm not sure of. None blocks 11c.

1. **Pertalite's default price (Rp 10.000) is unconfirmed.** Harmless: the first Pertalite refuel replaces it.
2. **"Almost 16k" was stored as Rp 15.900** for Pertamax. Correct it at the first fill-up if it's off.
3. **Refuelled sits below the fold in the Garage on a landscape phone** (odometer, ride button, then fuel).
   Fine for the Garage screen; 11c's expanded Garage card puts Refuelled in reach on the dashboard.
4. **Trip recovery only runs when the app is opened.** If Android restarts the process in the background
   (the media listener can do that) the lost trip waits until the app is opened; if that's over 10 min after
   the last checkpoint, it's saved as ended instead of resumed.
5. **The gap in a resumed trip counts as riding time** (and as moving time if the first fix after it is
   moving), joined by a straight line. A long detour while the app was dead shows as a straight segment.
6. **Swiped away + process dies before the save finishes** → the next launch within 10 min resumes that
   trip. Rare; ending it then saves it as normal.
7. **Range follows odometer corrections** (a higher reading = less fuel left), though km/L doesn't change.
   Tell me if the 2026-11-08 "correction doesn't change the range" meant something stricter.
8. **The range is an upper bound between readings:** untracked rides only count once logged.
9. **Reboot path of trip recovery is unit-tested only** (the emulator check covered a kill, same boot).
10. **HyperOS kills:** the app's checkpoint survives a kill, but whether HyperOS lets the trip service be
    restarted on reopen without the app's battery setting on "No restrictions" needs the real phone.
11. **Garage card readability at a glance:** the 5-segment tank and the single value are sized for Quad;
    check they read in sunlight on the mount, and whether "range" is the right default value.
12. **Service intervals are the rider's to enter;** no factory schedule is assumed for the Gen 1.
13. **Drag with gloves (12a):** is a 1 s hold easy to start and hard to trigger by accident on the mount?
    Does a bump mid-hold (road vibration) cancel it too often? Try 2 s if it lifts by accident.
14. **Refresh rate on the Redmi (12a):** check with the developer option "Show refresh rate" that motion
    goes to 120 Hz and drops back when still (Auto, Power saver), and whether Smooth costs noticeable battery.
    The emulator has a single 60 Hz mode, so none of this could be seen there.

- **Flaky under load (11c run):** `DashboardScreenTest.pauseResumeAndEndShowTheSummaryUntilDismissed` hit
  `AppNotIdleException` once late in the full emulator suite; it passed twice on its own. Watch it.

## Run 13a-14a, choices made

Unattended run (rider's "start 13a", 2026-11-14). Each entry: the question, what I picked, and why.

### 13a

- **Splitting 13a from 13b.** I started 13b's code before committing 13a, so the two were mixed in the working tree.
  Picked: save the full tree, strip 13b back out, build / test / export 13a from that, commit it, then restore 13b.
  Why: "one commit and one APK per sub-phase" is the fallback the rider wants if 14b goes wrong.
- **Changelog version codes.** Only 3.0 (12) onward were checked in git; 2.0 = code 3 … 2.8 = 11 are counted back
  from there (8c = 4 … 10b = 11). 3.3 is one entry (code 18) because 12a / 12b / 12c are one version name.
- **Fresh install vs update.** `firstInstallTime == lastUpdateTime` means fresh (nothing shown, the version counts as
  seen). No stored value on an update = "last seen 18" (v3.3), so the rider sees the 3.4.1 entry once, as agreed.
  Why not "DataStore has other keys": a fresh install writes the layout within seconds, so that test flips quickly.
- **Where the last-seen version lives.** `ChangelogStore`, same DataStore file, own key: the plan allowed it and it
  keeps all four fakes of `SettingsRepository` untouched. The view model reads the stored value as a flow, so the
  sheet follows it (found by a device test: an activity-scoped view model survives a recreation).
- **Swap button rule.** `canSwap`: Side by side, Rows, Duo's tall pane. A maximized stacked pane has no swap either.
- **"Fullscreen button stays the fullscreen button".** Read as: no new behaviour. Its icon already switched to "exit"
  in 12a, and the rider's answer says it should not become a different control, so I left it and only hid Maximize.
- **"Compact headers: title plus at most two buttons, the rest in the hold menu".** By construction a Compact pane now
  has only maximize + fullscreen (swap exists only on Expanded / Wide panes), so nothing had to move in 13a. The hold
  menu (picker) gets Fullscreen / Maximize buttons in 13b, where the compact map header is taken by mute + End.
- **Header width rule.** Buttons always fit; title next; battery, then clock, only if they still fit. Title and
  clock never overlap, and a too-narrow header loses the clock first (the battery matters more on the bike).
- **Drop target thresholds.** Overlap ≥ 20% of the *smaller* of the two cards (so a big tall card dragged a little
  over a small one doesn't need 20% of itself); finger-inside second; nearest centre only once the card has travelled
  half the way to it, so a small nudge can never swap.
- **Arming the picker.** 700 ms after the lift, by a coroutine timer in the drag state (not a pointer timeout: the
  gesture loop keeps reading events). Moving at any time ends it. The double buzz is two `LongPress` haptics 110 ms
  apart (the platform has no "double" constant).
- **Speed card opens on the speed face.** Cause found: `rememberPagerState` is saved across recreation, and the
  "trip Ended → log face" effect ran on the first composition too, so an ended trip left in memory (or a rotation
  from the log face) reopened on page 1. Now: the pager isn't saved, and the jump to the log face happens only when
  the trip *becomes* Ended while the card is up (dismissing the summary still lands on the log face, ready for Start).
  I first also sent the card to the speed face on every Start; reverted, because the Start / Pause / End buttons are
  on the log face and the rider presses Start there.
- **Dash km/L on every size.** Compact: the button sits beside the figures (a column under them didn't fit the small
  card). Wide: it was missing altogether, now under the figures.
- **Tank icon setting lives in the expanded Garage card** (under "The small card shows"), not in settings: the settings
  sheet is reorganised in 13c anyway and the choice is about that card.
- **Refuel overflow tolerance 0.2 L.** "More than the estimated room left" is strict in the rider's words, but the
  estimate has gauge-sized noise; 0.2 L stops a fill-up of exactly "the rest" from being re-labelled by rounding.
  When the level is unknown (no full fill yet) the switch decides alone.
- **Muted voice ducked the music (plan item, not in the unattended list).** Cause: Ferrostar's `AndroidTtsObserver`
  calls `requestAudioFocus()` before it checks its mute flag (read from the bytecode). Picked: detach the observer
  from the core while muted, create the TTS only when voice is on. Checked on the emulator only for "no crash":
  ducking itself needs the phone (listed below).

## Concerns to check at the phone test (run 13a-14a)

- **Muted navigation and music:** with Spotify playing, mute the voice (map or header button), start a route, pass a
  turn: the music must not dip at all. Unmuting mid-route should bring the voice back at the next instruction.
- **Header fit on the Redmi:** the corner clock and battery now drop out when a header is too narrow. In Quad the top
  right pane is the tightest; check that the clock still shows, and that the title reads in full.
- **Drag by overlap:** drop a card straight onto a neighbour with a gloved thumb; and check 0.7 s after the lift feels
  like a distinct second step (ring, dim, double buzz) and not like the lift itself.
- **Nav card alignment** (Compact / Wide): the arrow is now centred on the distance + road block; the old screenshot
  can't be reproduced on the emulator (no live route), so check it with a real route.

### 13b

- **Chip position and what stays.** The compact map's chip sits top left under the header (the header's own buttons
  and the corner cluster take the right side). Traffic (Google engine) stays in the bottom-right stack next to the
  follow button. The follow button is shown even while following, in the navy + accent "on" look, so it reads as a
  state (the plan said "recentre / follow").
- **Where maximize and fullscreen go on the compact guiding map.** The header keeps the title plus mute and End (the
  rider's plan, and the "two buttons" rule), so Fullscreen and Maximize moved to the hold menu (the picker sheet),
  which now has both for every pane (also useful for screen readers: they already had the action).
- **"Backdated to the moment the hold completed".** The trip starts at the instant the hold completes (the first 20–60 s
  are not recorded, as the rider's decision says: consent is "app open and already moving"); there is no earlier
  timestamp to back-date to, so no `TripTracker` change was needed.
- **Odometer-only counts from the start of the hold.** With no trip, nothing else would record those first seconds, and
  the proof of riding is the whole run. Chunks of 500 m go to the odometer while riding, the rest when 2 min still or
  when the app leaves the screen. Reuses "untracked km" (re-anchors the odometer, teaches no GPS factor), so fuel and
  services follow with no new storage.
- **"Clean state" definition.** Clean = at app open there was no trip and, since, none has ended while the monitor was
  running. An End seen only while the app was in the background (notification) leaves the state Ended until the
  summary is dismissed, which the next foreground watch still sees, so it counts too.
- **Low-rate watching.** Balanced-power 5 s fixes while stationary (no continuous GPS); full rate once a fix moves
  (speed ≥ 9 km/h, or a 120 m jump of the network position because Wi-Fi fixes have no speed); slow again after a
  minute without movement. The map's own 1 Hz reader (if on screen) is independent of this one; the fused provider
  merges the two requests.
- **Settings placement.** Auto-start is in the Trip options dialog's right column (it scrolls on a landscape phone):
  13c's categories give it a proper place.
- **MAX lag fixed** in `TripReducer` (first fix applies the accurate-speed max rule); moved from "Known issues" to the
  deviations list. The stale "TomTom key pending" known issue was removed too (the rider confirmed search works).

### 13c

- **Which control went where.** Display: theme, Colours, Screen options (refresh rate, battery), What's new. Layout: map
  width (Duo) and the hold-to-move time (moved out of Screen options, it is a layout gesture). Navigation: speed unit
  and Auto layout ("map on screen while navigating"). Trip and Garage: history, Garage, reset, Trip options and
  auto-start. Audio: voice guidance (the only audio setting today; media is controlled on its card). Backup: new,
  Back up / Import. Debug: the debug tools. The plan's "Navigation (units, voice)" and a separate "Audio" overlap on
  voice; Audio won because the rider listed it as its own category.
- **Layout of the settings sheet.** Categories are chips on the title line (title, chips, Done on one row in landscape),
  not a side rail: seven 56 dp rail items don't fit a 411 dp tall landscape screen. Each page has at most four
  rows, in two columns, so no page scrolls on the emulator's landscape size (checked by tests that tap every
  control without scrolling). Below 600 dp wide the chips go under the title and the columns stack.
- **Backup in settings reuses the history's `TripFiles`** (same ZIP, same share sheet); the history screen keeps its
  own buttons (its tests, and it is where someone looks).
- **Auto-start left the Trip options dialog** (13b put it at the bottom of the right column where it scrolled); it is
  now plain rows on the Trip and Garage page.
- **Accent defaults.** Fresh install = no stored accent, no saved preferences at the time of the first start, and a
  first-install time equal to the last-update time. Everything else = cyan. Done once in `NativeAutoApp.onCreate`
  (blocking, a few ms) so nothing else can write a preference first and flip the answer. The rider's phone has
  saved preferences, so it stays cyan until they change it.
- **Presets.** Green, cyan, ice blue, violet, magenta (the plan's list plus green, which is now the default). Each has a
  hand-picked light variant; cyan's is the one 8c shipped, so an existing install looks identical in both themes.
- **Custom colours are adjusted, not refused.** A hex that can't be read on black is lightened in the dark theme, one that
  can't be read on white is darkened in the light theme (contrast 3:1 and 4.5:1 targets); the field says so. A
  colour is applied only once it has six digits, so "#3DD" on the way to "#3DDC84" doesn't flash the shorthand colour.
- **Route colour.** `null` = follows the accent (default); an override is a plain preset or hex and goes through the same
  light / dark adjustment. The "you are here" dot on the map stays cyan: it marks the vehicle, not the UI, and
  recolouring it means a style write while the map is live (the rule that bit us in 8c).
- **Green rule.** CLAUDE.md's "no green anywhere" is changed: green may be the accent; amber and red stay status colours
  (never offered as accents).
- **Upgrade checked on the emulator:** the 3.3 release APK installed and opened (saving its layout), then the 3.4.3 APK
  installed over it: the accent stayed cyan, and the "What's new" sheet showed 3.4.3, 3.4.2 and 3.4.1 once.
  A clean install of 3.4.3 starts green.
