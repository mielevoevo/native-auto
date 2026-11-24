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

### 14a

- **Notification first.** The notification listener is already granted for media, covers every dialer (HyperOS, One UI,
  Pixel...) and every VoIP app that posts a call notification, and carries the call's own Answer / Decline / Hang up
  intents: pressing them is exactly what tapping the notification does, so it is the same on every skin. Telecom
  (`acceptRingingCall` / `endCall`) is the second choice, for a plain phone call whose notification has no such action,
  and only with the rider's permission. No per-skin code anywhere.
- **Permissions asked for by a button, not at startup.** READ_PHONE_STATE and ANSWER_PHONE_CALLS are dangerous
  permissions; a rider who only wants the notification route never sees a dialog. The Audio page says what works
  and offers "Allow".
- **Which pane gives way.** The plan's own wording was "the lowest-priority pane (e.g. speed before navigation, or replaces
  media)". Order picked: media, clock, Garage, speed, Navigation, map. Media first (a call silences it anyway), speed
  before the Navigation pane (as the plan's example), the map last (it is the main view, and a call during navigation
  must not hide the route). Only panes with room count, so with a maximized neighbour the card takes a visible pane.
- **Fullscreen.** Never taken over (rider's rule). While a fullscreen pane stays, the card waits; it appears the moment
  fullscreen ends, if the call is still on.
- **Derived, not stored.** The takeover is part of the derived `view` (like auto layout's override but separate): the
  arrangement is never touched, a swap or drag while the call is up edits the real arrangement, and the pane returns
  by itself when the call ends. The call card can't be dragged or picked.
- **Mute and audio route are hidden unless they work.** After a mute attempt the state is read back; a phone that
  ignores an ordinary app's request (some skins only let the dialer do it) loses the button for the rest of the call.
  The audio button shows only with two or more real routes and one tap cycles them (a menu would be a second target
  on a glove-sized card).
- **Plain-phone fallback text.** With only the cellular state (no notification we can read) the card says "Incoming call"
  / "Phone call": the number needs READ_CALL_LOG, which the app does not ask for.
- **Reading notifications, privacy.** The listener now sees every notification the system gives it, but only those that
  are calls are kept (in memory, until they go away); the rest are dropped at once; nothing is stored or sent anywhere.
  The manifest comment and the class doc say so.
- **Tests that needed a workaround.** A CallStyle notification can only be posted by a foreground service or with a
  full-screen intent, so the debug manifest declares USE_FULL_SCREEN_INTENT and the test sets the app-op. The
  compose test clock stops while the test thread sleeps (a screenshot taken during `Thread.sleep` shows a frozen
  UI): wait with `waitForIdle` in a loop when looking at the screen.

Needs the phone (also listed in the closing concerns section at the end of this file):
- The three real skins: a **call on the Redmi (HyperOS)**, and if the rider has them a One UI or near-stock phone: does the
  card appear on ringing, do Answer / Decline / End work from it, does the dialer's own full-screen incoming screen
  still show (it is not affected by the card).
- **Mute** from the card during a real call: does the mic actually go quiet (some skins ignore it).
- **Audio output** from the card on a cellular call and on a WhatsApp call, and a Bluetooth headset in the helmet.
- **Answer / End without the notification route:** with the phone permission allowed, a call from a dialer that posts no
  call notification.
- Whether a ringing call over the Navigation pane (Quad with navigation) takes the right pane.

## Concerns to check at the phone test (run 13a-14a, from 13b on)

13b
- **Auto-start on the bike:** with the app open and Auto-start = Start trip, ride off: does it start about 30 s after 15 km/h
  and say "Trip started"? Does Odometer only add the km (check the odometer after the ride, no trip saved)? After
  pressing End does it stay quiet until the app is opened again?
- **Low-rate watching battery:** the 5 s balanced-power request while stationary should be cheap; check the phone's
  battery stats after an hour at a red light / parked with the app open. HyperOS may also throttle location for
  an app whose screen is off, but this feature is for the screen being on.
- **The compact map while navigating:** chip, mute + End in the header, the big follow button. Is the chip readable at a
  glance? Can Fullscreen / Maximize be found in the hold menu without hunting?
- **Wi-Fi fixes with no speed** make the "moving" jump rule (120 m) fire: check it doesn't keep the high-accuracy
  request on in a parking lot with a bad network position.

13c
- **Accent in sunlight:** green on black reads well on the emulator; check it in daylight, and the light theme's darker
  variant on white cards.
- **Settings in landscape on the Redmi:** every page fits without scrolling on the emulator (411 dp tall); the Redmi's
  height is similar, check Trip and Garage (four rows on the left, auto-start on the right) and Display.
- **Backup from Settings:** the share sheet opens over the app; check saving to Drive works from here as it does from
  the history.
- **Existing install:** after updating from 3.3, accent still cyan; the "What's new" sheet shows once (3.4.1 to 3.5.1).

14a: see the "Needs the phone" list under "14a" above.

## 14b: portrait (v4.0, versionCode 23), run 2026-11-14

Spec and reasons agreed with the rider in chat before the run (see `.dev/Phase-14b-Portrait.md`). Choices made while building
are logged below, each with its reason.

### 14b decisions so far (portrait 4.0)

- **Real card sizes, not the mockup's.** The mockups drew the four-stack at about 85 dp a card; on a 400 x 860 dp phone each is about 215 dp (a row of
  three is 285, two stacked 430, a half-width card under "big + two" is 200 x 385). So **Mini** keeps a normal-height header (title, the status
  corner on the top card) and drops only the pane buttons; its content is the one-line essentials. Reason: nothing is gained by cramming a
  215 dp card down to 85 dp.
- **Card modes:** `Mini` (4-stack) and `Narrow` (half-width under the big pane) are new `CardMode`s; a portrait row of three is `Wide` and a portrait
  one / two stacked / big pane is `Expanded`. `LocalPortrait` (a composition local the dashboard provides) lets a card shape its Wide / Expanded /
  Fullscreen form for portrait without threading a flag through every call. Landscape forms are untouched.
- **No Maximize in portrait.** A stack's column is the whole screen, so maximize would equal fullscreen; fullscreen is the only zoom (button, or
  the hold menu on cards with no header buttons). Swap exists only for two stacked panes; the others rearrange by drag and the picker.
- **Rotation maps layouts by pane count, keeping the order** (`rotatedView`): Duo and "big + two" <-> three; Side by side / Rows <-> two; Quad <-> four.
  Where an orientation has two shapes for a count (3: stack or "big + two"; 2: side by side or rows) the shape the rider last used there wins, else
  the plain stack / side by side. Landscape has no one-pane layout: one portrait pane becomes Side by side with a neighbour (media, speed or map).
  A maximized pane is dropped, a fullscreen one stays. Each orientation's last preset is saved (`layout_preset_portrait` next to the old
  `layout_preset`, which stays the landscape one so older data reads the same).
- **Auto orientation:** with the phone's rotation lock on, the app asks for no orientation (`UNSPECIFIED`), so the system's locked orientation holds:
  the most literal "follow the lock". With it off, `OrientationPolicy` reads gravity (about 5 Hz, only while the app is started) and asks for sensor-landscape
  or sensor-portrait after a steady, generous lean: `ZONE_DEG` 30 (the phone must be within 30 degrees of upright to go portrait, within 30 of sideways to
  go landscape; the 30..60 band decides nothing, so a mount tilted 5 degrees never flips), `STEADY_MS` 1500, and nothing when the phone lies flat
  (`MIN_IN_PLANE` 3 m/s2). The rider tunes these on the bike (`OrientationTuning`).
- **configChanges** (`orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|density`): a rotation never recreates the activity.
  The Orientation setting is read once before the first frame (a fixed mode is requested in `onCreate`), so a portrait rider never sees a landscape start.
- **Call pill:** over a fullscreen pane the call is a slim top pill (ringing: Decline + Answer; on the call: duration, Mute where it works, End). It
  covers the fullscreen card's header meanwhile (Back and the hold menu still leave fullscreen), never moves or resizes the card, and goes when the call ends.
  The rider asked for Answer / Decline only; the on-call form is the small addition that keeps the call controllable when the card can't appear.
- **Dialogs and sheets:** one shell (`AppDialogSurface`): landscape is the old centred card; portrait is a bottom sheet at most 90% of the screen high, one column
  (`DialogTwoColumns` stacks what was two columns). Forms with Cancel / Save (`GarageDialog`: bike, refuel, level, service, untracked km...) use
  `PinnedActionDialog` in portrait: the action sits in a footer above the keyboard and the fields scroll above it. The settings sheet already stacked to one
  column below 600 dp, so it only gained the Orientation row.
- **Map in portrait:** a narrow card (Mini / Narrow) shows only the recentre button (accent ring while following); guiding it adds the slim chip and a small
  End in the header that asks twice (armed 3 s, red), no mute. A fullscreen map has "Where to?" along the top, and, guiding, one bottom row whose items
  share a top edge: the trip's figures with Mute and End on the left, traffic and recentre on the right (the rider's "button top edge aligned with the
  navigation info box"). A portrait row of three counts as a compact map while guiding.
- **Tilt sensor axes:** gravity is read in device coordinates, so "upright" means the phone's natural orientation (right for phones).
- **Release smoke:** `ReleaseSmokeTest` gained `portraitOpensWithoutACrash` (turn to portrait from the settings, every portrait layout, the grid picker,
  two dialogs as sheets, Auto, back to landscape), next to the landscape journey.
- **Regression run, one finding (2026-11-14):** the device regression (call, Garage, dashboard) failed 5 tests once; the emulator had been up 15 h with a load
  average of 22 (CLAUDE.md "ANR after a day of uptime"). After a cold boot the four older tests passed unchanged; the fifth was my new call-pill test,
  whose "no call card over a fullscreen pane" check counted nodes (the CALL pane is always composed) instead of measuring its area, as the 14a test does.

## Concerns to check at the phone test (14b, portrait)

1. **Mini legibility** (4-stack): ~85 dp cards on a typical phone. If the figures are too small, the 4-stack is the one to drop or reduce to 3.
2. **Tilt tuning on the mount** (`OrientationTuning`: `ZONE_DEG` 30, `STEADY_MS` 1500, `MIN_IN_PLANE` 3): check that a bike propped 5 degrees off never turns the app,
   and that a real lean does. Auto with the system lock on follows the lock (no sensor).
3. **Portrait map controls:** narrow map (recentre only, End with two taps while guiding), fullscreen map (search bar on top, bottom row top edges aligned).
4. **Call pill** over a fullscreen pane: it covers the card's header (Back / hold menu still exit). Check ringing and on-call forms with gloves.
5. **Bottom sheets:** the 90% height cap and the pinned Save / Done above the keyboard in the Garage dialogs.
6. **Swipe on media** (Mini and Narrow): the 64 dp swipe distance, the cue, accidental changes while riding.
7. **Rotation:** panes slide, the map keeps its camera and the trip / navigation carry on (activity is never recreated; first time on a real phone).

## Run 4.1 -> 4.1.1 -> 4.2 (unattended, started 2026-11-14)

Spec: `.dev/Phase-4.1-4.2-Unattended.md`. Order and versions chosen by the rider: logo = v4.1 (versionCode 24), follow-ups = v4.1.1 (25),
first-run setup pages = v4.2 (26). Rule for failures: fix at once, narrowest re-test first. The uncommitted v4.0 follow-up draft in the working
tree is stashed during build 1 so the logo APK is built from clean code, then restored for build 2.

### 4.1 (logo), 2026-11-14/15
- Icon built as vector drawables from `nativeauto.svg` by `.dev/scratch/icon.py` (adaptive foreground, separate single-colour monochrome layer,
  solid black background). The mark is centred by its bounding box and scaled so its farthest point sits at 31.5 dp from the centre (safe circle
  is 33 dp). It is a wide mark, so that rule makes it smaller than a "61% square" would; chose the circle rule so no launcher mask can cut it.
  The user's "nudge up 12%" applied to the old PNG, whose mark sat low; with a tight cutout the bounding-box centre is right.
- Stale Android-default webp launcher icons deleted (minSdk 26 uses the adaptive icon only). Play Store PNG: `app/src/main/ic_launcher-playstore.png`
  (512 px, black square, no rounded corners).
- Checked on the emulator launcher: icon renders; the cyan ring around it is that launcher's own styling (also seen with the themed layer on).
- The first ReleaseSmokeTest run failed ("No settings_open") on a freshly booted emulator (cold first launch over the 30 s wait, no crash in the
  log); the rerun passed both tests. Not a code problem.
- Working-tree draft for 4.1.1 was stashed (`git stash`, "v4.0 follow-up draft") so the 4.1 APK is built from committed code; restored for 4.1.1.
- Concern for the phone test: the icon's size inside the mask (mark ~55% of the icon width) and the themed-icon look on the rider's launcher.

### 4.1.1 (follow-ups), 2026-11-15
- **Portrait maximize:** the rider's rule by pane count, read as "two cards' worth of height" = 2/n (3 stacked: 2/3, 4 stacked: 1/2), others slim Mini
  rows sharing the rest. "Big + two": the small cards get the same rule (the layout turns into rows while one is maximized); the big pane has no
  button because it has nothing to grow into. Two panes: no button (fullscreen does the same).
- **Accent default:** `#3FC8E0` (the rider's choice over the logo's `#4adffc`). Existing installs already had it, so nothing migrates.
- **Auto-start:** the enum keeps ODOMETER_ONLY (the monitor and its tests still use it) but nothing writes it; read-mapping turns a stored one into TRIP.
- **Portrait title spacing:** the rider asked for "a bit more space, like landscape" without numbers; chose 6 dp extra + 48 dp minimum header (landscape's
  header is as tall as its 48 dp buttons). Mini cards (85 dp) get 2 dp only so their content doesn't lose room. Concern for the phone: judge the amount.
- **Privacy page:** Reset app uses `clearApplicationUserData()` (kills the app, wipes DataStore, Room, permissions). Two taps within 3 s, like Reset
  trip. Not exercised by a device test (it would wipe the test app); only the button's presence is checked.
- **Settings tab strip:** eight tabs scroll sideways in landscape ("Privacy and data" is cut off at the right edge on the emulator). Kept the rider's label;
  Debug is debug-builds only. Concern: check the strip on the phone.
- **Auto-start hint** shortened (the odometer sentence is gone, since that mode is gone) so the Trip page fits.
- Tests changed: `PortraitLayoutTest` (3 new maximize cases), `PortraitDashboardTest`, `DashboardScreenTest` (settings tab order, switch, call-controls switch,
  cyan default), `GarageScreenTest` (one Add bike button), `AccentColorTest`, `ReleaseSmokeTest` (opens Display first, Privacy page visit).

### 4.2 (setup pages), 2026-11-15
- **When to show:** "DataStore empty at app start" (not install-time comparison) so a Reset app shows the pages again and an update from any build with saved
  preferences never does. Stored once, so an update half-way through keeps the pending state.
- **Permissions:** the Notifications toggle is two steps because the platform splits them (POST_NOTIFICATIONS prompt, then the notification-access
  screen, which is a system settings screen, not a prompt). "Call controls" is a plain setting (no permission); Phone state and Answer and end calls ask.
  A toggle already on opens the system app/notification screen (the app can't revoke itself).
- **Gate afterwards:** a rider who continues without location is still asked by the dashboard's existing `PermissionsGate` (unchanged); judged better than
  a dashboard with no speed and no explanation.
- **Sizes:** the rev 2 mock's "2.3 em" rows became 48 dp rows / 52 dp buttons (Material's minimum touch target) at 16 sp body, 28 sp title; Riding, the
  longest page, leaves ~80 dp spare on a 411 dp tall phone. Dropdown menus open under their row and cover the rows below while open (normal).
- **Row colour:** first draft used the bright "main button" token and the text vanished (seen on the emulator); rows use `buttonQuiet`.
- **Smoke test:** the first release run after a rebuild failed again on the cold first launch ("No settings_open") and the rerun passed (same as 4.1 and
  4.1.1: emulator dex-opt after install). Not a code problem; if it recurs, run the smoke test twice.
- **Concerns for the phone test:** (1) the notification-access screen opens in the middle of the setup on the Notifications toggle: check it returns to the
  page and the toggle reads On; (2) Android 13+ shows the permission prompt as a dialog over the setup, check both orientations; (3) the confirm dialog wording.

## Phase 15 (iOS)

Unattended run 15a -> 15b on the Mac (2026-11-16). Simulator: iPhone 17 (iOS 27.2, Xcode 27.2); the README's
"iPhone 16" isn't installed here.

### 15a (v0.1, build 1)
- **Local.xcconfig** created from the example with an empty team ID (git-ignored). `project.yml` lists it as the
  config file, so XcodeGen needs it to exist; the simulator doesn't sign, so the empty ID is enough. Reason: the
  rider's real ID goes in later, for the phone.
- The Windows-written 15a compiled and passed all 16 tests unchanged. Checked against the Kotlin: `TripReducer`,
  `stepAutoStart`, `autoStartAllowed` and the polyline match line for line (same constants).

### 15b (v0.2, build 2)
- **Testable core without async:** `TripTracker`, `AutoTrip` and the fake drive run on a small `TaskScheduler` /
  `MonotonicClock` pair (`Core/Common/Scheduling.swift`); tests use `VirtualScheduler`, the app the main queue.
  Reason: Android's tests run in virtual coroutine time; this keeps the same tests (same numbers, e.g. one fake-drive
  loop) deterministic on iOS without Combine or Swift concurrency in `Core/`.
- **Source = callbacks, not a stream:** `SpeedSampleSource.subscribe` delivers `.fix` / `.failure(.permissionDenied |
  .disabled | .other)`; the tracker does Android's retry rules (3 s while location is off, 5 s on other errors, none
  without permission). Reason: Foundation-only core; same behaviour as `retryWhen`.
- **One CoreLocation manager** for the trip and the live hero (Android's single GPS read). Fix times are converted to
  the monotonic clock (`now − age`); fixes older than 10 s on arrival are skipped (CoreLocation hands out a cached fix
  first, which would start a trip with a stale point). Reason: trip maths never uses the wall clock (rule #8).
- **Layout state is a value type** (`struct DashboardLayoutState`); SwiftUI animates when it changes. The Compose
  `Saver` tests are not ported: iOS doesn't recreate the screen on rotation, and what is saved between launches is
  `SavedLayout` (same keys and the same `LayoutCodec` text as Android, tested).
- **Ported ahead of their sub-phase, because the layout tests need them:** call takeover (`callTakeoverPane`) and
  `AutoLayout` (pure). The call card itself (15e) and navigation (15c) wire them up later.
- **Orientation:** Landscape (default) / Portrait / Auto. Auto = every orientation but upside down; iOS applies the
  rider's rotation lock by itself, so Android's lean-and-hold `OrientationPolicy` isn't ported. Reason: an app can't
  read the lock on iOS, and the system already does what the policy approximated.
- **Safe area:** the dashboard keeps clear of the notch / Dynamic Island sides and uses the bottom edge (home indicator
  hidden). Reason: cards under the sensor housing or the rounded corners would lose their corner buttons.
- **Panes are placed with `.position`, and every gesture / accessibility modifier comes before it.** Found by the UI
  tests: after `.position` a modifier covers the whole dashboard, so every card's tap and hold area was the full screen.
- **Speed faces:** a small custom pager (not `TabView`), so it can be switched off while a card is lifted and a drag
  never turns the page. Android consumes the touch after the lift; this is the SwiftUI equivalent.
- **Settings sheet for 15b** is one scrolling sheet (Display, Layout, Trip, Debug). Android's category tabs (13c) come
  with the other settings in 15e.
- **Not ported (Android-only):** the double-back close flow and its save prompt (iOS has no back button; swiping the
  app away ends the trip, as on Android), refresh-rate control (iOS ProMotion adapts by itself), `SelfClose`,
  the foreground service (iOS trips run while the app is open, like Android's rule).
- **Debug launch arguments** (UserDefaults argument domain, e.g. `-use_fake_gps 1 -debug_start_trip 1 -layout_preset
  QUAD`) drive the simulator screenshots and the UI tests; `-debug_start_trip` is compiled into debug builds only.
- **UI tests added** (`NativeAutoUITests`, 5 tests): trip life cycle through the log face, drag to swap, picker after a
  still hold, layout switcher, rotation in Auto. Reason: the drag and the hold can't be checked by unit tests.

### 15c (v0.3, build 3), 2026-11-17
- **Map: MapLibre's UIKit `MLNMapView` in a `UIViewRepresentable`, not the SwiftUI DSL or `FerrostarMapLibreUI`.**
  Only Ferrostar's `FerrostarCore` product is linked. Reason: the dashboard needs Android's own map rules (OLED
  recolour by layer id, route under the labels, cyan dot with a heading chevron, follow camera breaking on a gesture
  only, map sleep), which the ready-made navigation view doesn't offer. The DSL still comes in as a Ferrostar
  dependency; command-line builds pass `-skipMacroValidation`, Xcode asks once to trust its macro.
- **Exact package pins:** Ferrostar 0.59.0 (Android's), MapLibre 6.31.0 (what Ferrostar 0.59 resolves to). Reason:
  the resolved-versions file lives in the git-ignored `.xcodeproj`, so only `project.yml` fixes the versions.
- **One location read for everything:** `TripStore.fix` (the trip's fix while it runs, the live read otherwise) feeds
  the map and Ferrostar (`FixLocationProvider`); Ferrostar never opens its own `CLLocationManager`. `SwitchableSource`
  now moves open readers over mid-stream, so debug "Simulate navigation" (beats Fake GPS) drives the trip, the map and
  guidance along the planned route, like Android's `SwitchableSpeedSampleSource`.
- **15b bug fixed:** `TripStore.appActive()` started the live read before the 1 s ticker existed, and the read only
  runs while the ticker does, so the hero and the map had no fix unless a permission callback came later. It worked in
  15b by luck of timing; 15c's extra start-up work changed the order. Now the ticker comes first.
- **Voice:** Ferrostar iOS checks its mute flag before it asks for audio focus, so a muted route never ducks the music:
  Android's 13a workaround (detaching the observer) isn't needed; mute is `toggleMute()`. `EnglishSpeech` puts a US
  English voice on every utterance (the narration is Valhalla's English).
- **Recent places in `UserDefaults`** (JSON, 8 kept, one per ~10 m: `rememberPlace`, tested), not a database. Reason:
  eight small rows; storage arrives with 15d and they can move then.
- **Naming a dropped pin:** Apple's `CLGeocoder` first, Photon reverse as fallback (Android: the platform `Geocoder`
  first, then Photon). Same User-Agent rule for FOSSGIS and Photon: `NativeAuto/<version> (iOS dashboard; personal use)`.
- **Google Maps links without a share extension (for now):** the search sheet takes a pasted link (field or the
  system Paste button, which needs no paste permission), and the app opens `geo:` URIs and
  `nativeauto://open?text=…` (a Shortcut can hand a shared link over). Reason: Google Maps on iOS offers only apps
  with a share extension in its share sheet; an extension is its own target with an app group, which fits 15e better.
  Logged as left for 15e.
- **Android bug found while porting (not fixed in `app/`, for the Android update):** `findMapsUrl` trims a trailing
  `)` from every link, so `geo:…(Warung Bu Ning)` found in shared text loses its label. iOS keeps a `)` the link opened
  (`MapsLinkTests.testAGeoLabelSurvivesBeingFoundInText`).
- **Map buttons while guiding, as on Android:** a big map's 56 pt column holds Mute, End (two taps) and Recenter
  (Android `MapButtons`); a small map's header holds Mute and End (13b). End is two taps everywhere on iOS: the 14b
  rule for the portrait header, applied to every End so one tap of a glove never drops the route.
- **Parked panes draw no navigation controls** (`paneOnScreen` environment). Found by the UI tests: a parked
  Navigation pane still offered VoiceOver a second "Start"; `accessibilityHidden` on the slot didn't remove it.
- **No traffic button:** OpenFreeMap has no traffic data (as on Android's MapLibre engine). No Google Maps engine on iOS.
- **The route line follows the accent;** a separate route colour (13c) comes with the 15e settings.
- **Launch-argument switches:** `SettingsStore` read booleans with `as? Bool`, which misses text from launch arguments
  ("-voice_guidance 0"), so those fell back to the default. Now `bool(forKey:)` (also fixes 15b's battery switch).
- **Debug launch arguments added:** `-debug_open_link "<text>"` (opens it as if shared), `-debug_start_nav 1` (presses
  Start on the preview), `-simulate_navigation 1`. Debug builds only.
- **UI tests added** (`NavigationUITests`, 3 tests, against the real FOSSGIS and Photon, skipping when offline): link →
  preview → guidance on the simulated drive → End (two taps); the Navigation pane taking the turn from a small map
  (auto layout to Quad's guiding set, the map's Mute / End header); search → preview. The 15b rotation test now pins
  the portrait shape (the last one used wins, and a manual run had saved another).

### 15d (v0.4, build 4), 2026-11-17
- **Storage shape:** the pure core talks to two synchronous protocols (`TripDao`, `GarageDao`, the Room DAOs' contract);
  `AppDatabase` implements both over SwiftData and keeps the (small) rows in memory as value records, writing and
  saving each change at once. Reason: the Garage maths and the recorder stay pure Swift, tested against an in-memory
  fake like Android's `FakeGarageDao`, and a trip ended by swiping the app away reaches the disk before the process
  goes. Screens read an `ObservableObject` store (`GarageStore`, `HistoryStore`) republished after each write and every
  10 m of a live trip (the Garage odometer runs during a ride), instead of Room's Flows.
- **Full tracks as one blob per trip** (`TrackCodec`, 36 bytes a fix, external storage), not one SwiftData object per
  fix. Reason: a two-hour ride is ~7 000 fixes; thousands of objects make SwiftData slow to save and to delete. The
  backup and GPX carry every fix as on Android.
- **The trip checkpoint is a file pair** in Application Support (figures as JSON, fixes appended one JSON line each),
  not SwiftData: appending a minute of fixes is cheap and a line cut off by a kill is just skipped. Same rules as 11b
  (every 60 s and at pause / resume; resumed within 10 min of the same boot, else saved as ended).
- **Same boot:** a checkpoint whose monotonic time is later than the clock now comes from before a reboot (the clock
  restarts at boot), as on Android; then the trip is saved, not resumed.
- **Backup = Android's format 2, byte for byte where it matters:** ZIP with `backup.json` + `gpx/*.gpx`, written and
  read by our own small ZIP code (DEFLATE via Foundation, CRC-32; reading goes through the central directory because
  Android's `ZipOutputStream` uses data descriptors). Longs are written as JSON integers (kotlinx reads them as
  `.long`). Tested against a fixture laid out like Android's output. Import merges as on Android (start time for trips;
  ids / keys for the Garage). A damaged Garage row is skipped instead of failing the whole file (Android fails the
  file); reason: one bad row from a future version shouldn't block the trips.
- **Not in the backup (as on Android):** fuel marks (gauge readings) and dash km/L figures.
- **Recent places stay in UserDefaults** (15c), not SwiftData: eight small entries read at launch.
- **A store that can't be opened** (damaged file) is moved aside with a date suffix and a new one started, so the app
  always starts; the old file stays for a later look.
- **Addresses:** Apple's `CLGeocoder` first ("Street, Area" by the same `shortAddress` rules), Photon reverse as the
  fallback; retried when the history opens, as on Android.
- **Trip overview maps:** MapLibre's `MLNMapSnapshotter` with the dashboard's look (OLED recolour in dark), path drawn on
  top, cached per trip and theme in Caches; the plain sketch shows until it's ready, and stays if offline (20 s limit).
- **History and Garage are full screens over the dashboard** (`fullScreenCover`): the map stays put underneath and
  sleeps after 30 s, like Android's overlay. Opened from Settings (Trip and Garage) and the Garage card.
- **"Which bike?" in debug builds only**, as on Android (inferring where the rider lives needs a disclosure first).
- **Dash km/L on the summary** goes to the trip's own bike (the one it was saved with), else the active one.
- **Summary route sketch only where there's room** (Expanded / Fullscreen); the compact summary keeps the figures and
  the Bike's km/L button, as Android's compact summary does.
- **Debug launch arguments added:** `-reset_data 1` (empty history and Garage), `-debug_min_trip_m` /
  `-debug_min_moving_ms` (save a few seconds of the scripted drive in UI tests), `-debug_end_trip_after_s N` (with
  `-debug_start_trip 1`), `-debug_open_screen GARAGE|HISTORY` (screenshots). Debug builds only.
- **Found while porting:** an accessibility identifier on a plain SwiftUI container is copied to every child, which
  hid the buttons inside from the UI tests; containers now say `.accessibilityElement(children: .contain)` first.

### 15e (v0.5, build 5), 2026-11-17
- **Media card = what iOS allows an app** (default in `ios/CLAUDE.md`): play / pause / skip and the track with its art
  for **Apple Music** (`MPMusicPlayerController.systemMusicPlayer`), the system output picker (`AVRoutePickerView`) and
  volume (`MPVolumeView`) for whatever plays. Spotify and other apps can't be read or controlled by another app on iOS,
  so the card says so in one line. Track details need media-library access: asked from the card's own button or the
  first-run page, never at launch. Previous goes back to the start of the track after 3 s, like a car stereo.
- **Call card from CallKit only** (`CXCallObserver`): ringing / calling / on a call and the call's time. iOS gives an
  app no caller name and no Answer / Decline / End (the Android card's main buttons); the card says to use the phone
  or the headset's button. Same takeover rule as Android (`callTakeoverPane`: media first, never over a fullscreen
  card, the pill over a fullscreen card). The "Call controls" switch (Audio) turns the card off. Pure part ported with
  tests (`CallModel`: phases and the pick order). The Android notification classifier isn't ported (no source for it).
- **Not ported (Android-only):** the notification listener (media sessions and call notifications), answering and
  ending calls, mic mute / audio route on the call card, refresh-rate control (ProMotion adapts by itself), baseline
  profiles, the debug Search test screen, the double-back close flow. The `ODOMETER_ONLY` auto-start mode is gone from
  the UI on Android too (4.1.1); iOS has on / off only.
- **Settings in categories** as 4.1.1 orders them: Trip and Garage (where the sheet opens), Display, Layout,
  Navigation, Audio, Backup, Privacy and data, Debug (debug builds). A row of chips on top; each page two columns at
  600 pt and wider, one below; pages scroll where they're taller than a landscape phone (Trip and Garage does: the
  figure chips sit low on the left), rather than Android's split into Trip options / Screen options dialogs. Reason:
  one level fewer to reach a setting with gloves, and the pages fit the iPhone's width well.
- **Route colour (13c):** "Follow the accent" switch, else the accent presets + hex for the line; stored as
  `route_color` (absent = follow). In the light theme the line gets the colour's light variant, like the accent. The
  hex fields warn only for a typed colour (presets carry their own light variants; Android's warning showed for the
  default cyan too).
- **Auto-start wired up** (the core was ported in 15b): on / off + Start after 20 / 30 / 45 / 60 s in Trip and Garage,
  off by default; only while the app is open (iOS has no background location without "Always"), from a clean opening
  (no trip, none ended since), same `stepAutoStart` rules (15 km/h held). Announced with a "Trip started · riding
  detected" pill at the top (3 s, tap or swipe to dismiss) instead of Android's banner over the speed card.
- **Privacy and data:** opens the iPhone's Settings for the app (iOS has one screen for all its permissions), and
  **Reset app** (two taps): deletes the database, checkpoint, caches and every setting, then the app quits (iOS has no
  `clearApplicationUserData`; quitting is the only way to start clean). The next start is a first run.
- **First-run pages (4.2):** Welcome, Make it yours (orientation / theme / units), Location, Media and calls (call
  card switch, Apple Music access), Add your bike (the Garage's editor), Riding (auto-start, auto layout, voice),
  Ready. Shown when the app's own defaults are empty at launch (a new install or after Reset app), decided before
  anything writes a setting and stored (`setup_done`), as on Android. Android's Notifications page has no iOS
  counterpart (no notification access; trips don't need a notification here). Location is asked only from the
  switch; refused before, the switch opens the iPhone's Settings.
- **Changelog (13a):** `Resources/Text/changelog.md` in Android's format (`## <name> (<code>) · <date>`), its own iOS
  entries (0.1–0.5). The sheet after an update shows only entries newer than the last one seen (at most three), never
  during a trip, a pause or navigation; a fresh install counts as seen. An update from 0.4 (no changelog) shows 0.5
  once. "What's new" in Display shows the whole file. A unit test fails if the top entry isn't the build's version.
- **App icon:** `nativeauto.svg` rendered at 1024 px on black (no SVG tool on the Mac, so AppKit draws it; the mark's
  longer side is 64% of the icon, inside iOS's rounded mask), saved without an alpha channel (App Store rule). The
  first-run Welcome uses the SVG itself (vector asset `LaunchMark`).
- **Google Maps share extension** (`ShareExtension` target, "Native Auto" in the share sheet): joins the shared name
  and link like Android's `EXTRA_TEXT` and opens `nativeauto://open?text=…`, which the app's existing link resolver
  takes (preview, then Start). iOS has no supported way for a share extension to open its app; it calls
  `UIApplication.open` found up the responder chain (what share extensions do in practice). If iOS refuses, the text is
  copied and the extension says to paste it in search. An App Group hand-over was not used: it needs a capability on
  the rider's signing team that may not exist (a free team can't add one), and would break the phone install.
  `Shared/ShareLink.swift` is compiled into both targets and tested (round trip through `incomingLinkText`).
- **Compact speed face fix:** in a compact card (Quad) the hero size is now taken from the card's height, so the
  state line ("No fix", "Lost") stays in view; it was cut off below the card.
- **Media card's big form tightened** (title scales down, two lines max; the access button or the note in one line):
  it overflowed in Side by side.
- **Debug launch arguments added:** `-debug_call RINGING|ACTIVE`, `-setup_done 0|1` (also skips the changelog unless
  `-changelog_seen_code` is given), `-setup_page N`, `-debug_open_screen SETTINGS`, `-settings_category <NAME>`.
## 4.2.1 (minor update, 2026-11-16)

- **Auto-resume covers manual pauses too** (rider's choice): steady movement means riding again, whoever paused.
- **Auto-resume runs only while the app is open**, through the same watcher as auto-start (`AutoStartMonitor`).
  Reason: a pause stops the GPS, and the rider's rule is no background location. A trip paused with the app closed
  resumes once the app is opened and the bike moves.
- **Same proof as auto-start** (over 10 km/h, held, inaccurate fixes skipped), own hold 5 / 10 / 20 s, **default on at
  10 s**. Reason: the rider asked for it, and a shorter hold than auto-start's because the trip already exists. On for
  existing installs too: the rider's request, and it only acts on a paused trip.
- **One settings row (Off / 5 / 10 / 20 s)** instead of a switch plus a row: the Trip options dialog has to fit a
  landscape phone without scrolling. Stored as two settings (`auto_resume`, `auto_resume_hold_s`).
- **No clash with auto-pause / auto-end:** a resume restarts auto-pause's stationary clock, auto-end only fires while
  still paused, and auto-pause needs minutes below 3 km/h while resume needs 10 km/h held, so they can't flip-flop.
- **"Trip resumed / Moving again"** reuses the auto-start banner and pill (`BannerMessage.resumed`).
- **Auto-start threshold 10 km/h** (rider). The basement miss was the GPS (no usable fix underground), not the
  threshold; the hold time stays so drift at a standstill can't start a trip.
- **Smoke test fix:** the Trip options row went to the right column (the left one pushed Period total below the fold).
  Leftover plate tags (`garage_bike_<name>` etc.) in three tests renamed; the history rewrite had matched whole words only.
- **Landscape media swipe:** Compact, Wide, Expanded and Fullscreen landscape cards take the portrait swipe; unlike
  portrait's small cards they **keep** their skip buttons (room for them, and gloves). No other horizontal gesture on
  those cards to clash with.

## Run 4.3 (unattended, 4.3a -> 4.3d, started 2026-11-24)

Spec: `.dev/Phase-4.3-Unattended.md` (rider's screenshots + notes, `Rider-Feedback.md` "Phase 4.3 plan"). Every UI change
reviewed against `DESIGN.md`; each build's critique is logged below.


### 4.3a (v4.3, versionCode 28): consistency fixes

- **Settings sheet height:** fixed at 94% of the screen in landscape and 80% in portrait, whatever the tab, with the page scrolling
  inside it. *Reason:* the rider saw the sheet jump between tabs; one height is the simplest rule that never jumps (C6).
- **One row pattern (C4):** `SettingRow` (title + hint left, control right, min 64 dp) for every setting. Reset trip / Reset
  app are `DangerRow`s ("Reset" → red "Tap again"); Trip history got a hint and an "Open" button; Backup is two `ActionRow`s
  in the page's columns. *Reason:* the bare Reset button and the unstyled Backup page were the rider's two examples.
- **Tabs:** the row fades out at the edges (`fadingEdges`) instead of cutting a tab; 32 dp from the title. Tests scroll a
  tab into view before tapping it (`everySettingsCategoryHasItsPage` failed once on this: Privacy sat under the fade).
- **Hold menu:** Fullscreen / Maximize tiles only for a card whose header has no zoom buttons of its own (Mini / Narrow
  portrait rows, a guiding map). Read from `LocalHeaderZoom`, filled by `CardChrome`. *Reason:* the rider called them
  redundant; dropping them everywhere would have left slim portrait rows without fullscreen.
- **Low fuel:** the pump moved from floating on the speed card into the header's status corner, beside the battery, and is
  dropped before the clock and battery when the header is short. *Reason:* the rider found its spot random; the corner is
  where status already lives. Range text in the header was rejected by the rider (cramped), so only the icon.
- **Dash km/L:** a `DashTile` (label + "+ Add" on a quiet plate, 56 dp min) inside the summary figures in every mode. Rows
  summary aligned to the top. *Reason:* the old text link in Rows was too small for gloves.
- **Fullscreen navigation (landscape ≥ 520 dp):** `SideTurn`, glyph left, distance / road / then-line right, arrival and End
  under the text column. Narrower falls back to the centred stack. *Reason:* the stacked fullscreen left the screen half empty.
- **Phrasing:** "Bear left/right" → "Slight left/right" on screen and in the voice (`RiderPhrasingSpeaker` wraps Ferrostar's
  observer); "Then turn left" lower-cased after "Then", road names keep their capital.
- **Emulator:** the system server kept dying (Wi-Fi network stack crash, `Could not create NetworkMonitor`). Cold boot +
  `svc wifi disable` fixed it; mobile data is enough for routing. Not an app issue.

**Design review (DESIGN.md):** gates G1–G6 pass (tokens only, no new accent, touch ≥ 48 dp, sentence case, no "!", both
orientations shot). Consistency C1–C7 pass; C4 is the point of the build. Clarify pass: three hints shortened (Backup,
Trip options, Auto-start) so no row wraps to three lines in landscape.

| Screen | Hier. | Glance | Consist. | Touch | Copy | Calm |
|---|---|---|---|---|---|---|
| Settings, Trip and Garage | 3 | 2 | 3 | 3 | 3 | 3 |
| Settings, Backup | 2 | 2 | 3 | 3 | 3 | 3 |
| Hold menu (landscape / portrait grid) | 3 | 3 | 3 | 3 | 3 | 3 |
| Trip summary, Rows | 3 | 3 | 3 | 3 | 3 | 3 |
| Fullscreen navigation | 3 | 3 | 3 | 3 | 3 | 3 |

Backup "Hierarchy 2": two equal actions side by side, which is honest; a primary/secondary split would be invented.

**Tests:** unit all green; device 49 (Dashboard subset, Portrait dashboard/cards, TripSummaryDash, Garage) green after the
tab-scroll fix; ReleaseSmokeTest 2/2; the exported APK launched without a crash entry.
**Phone checks:** the low-fuel icon in the header at real width; Slight left spoken by the phone's TTS; the fullscreen
nav at the phone's real aspect.

### 4.3b (v4.4, versionCode 29): look

- **Card titles:** `SettingsRepository.cardTitleStyle` (`CardTitleStyle` TEXT default / ICON), Display → "Card titles". The
  icon is the picker's (`Pane.icon`), 24 dp, `textDim`; its content description is the pane name, tag `header_title_<PANE>`
  kept. Reaches the cards through `LocalCardLook` (`CardLook`, from `DashboardViewModel.cardLook`), like the status corner.
- **Media plates:** previous / next are `FilledIconButton`s on `buttonQuiet` (52 / 60 dp as before, icon 50 %), gap 12 dp.
  The call card already had plates on every button (Mute and Audio on `buttonQuiet`), so nothing changed there.
- **Faint card icons:** `paneWatermark` (off by default), Display → "Faint card icons". Pane icon at 5.5 % of `text`, 80 %
  of the card's short side, bottom right and partly past the edge; drawn in `drawBehind` (no recomposition). Expanded /
  Fullscreen only. *Not* on the map (full-bleed, it would sit on tiles), the speed card (the spec forbids it behind the
  hero number, and in Expanded the hero fills the card) or a call (urgent). Logged instead of half-hiding it on speed.
- Display page in landscape: Theme, Colours, Card titles left; Screen options, Faint card icons, What's new right. Fits
  without scrolling (shot `look_settings_DISPLAY`).

**Design review:** G1–G6 pass (tokens only; the watermark is far below text contrast, G3 unaffected; both themes shot).
C1–C7 pass: plates reuse play's shape and the quiet token; the icon set is the picker's (C7).

| Screen | Hier. | Glance | Consist. | Touch | Copy | Calm |
|---|---|---|---|---|---|---|
| Dashboard, icon titles | 3 | 3 | 3 | 3 | 3 | 3 |
| Media, plated skips (dark / light) | 3 | 3 | 3 | 3 | 3 | 3 |
| Media Expanded + faint icon | 3 | 3 | 3 | 3 | 3 | 2 |
| Settings, Display | 3 | 2 | 3 | 3 | 3 | 3 |

Faint icon "Calm 2": it is decoration by definition; kept because it is an opt-in the rider asked for, off by default.

**Tests:** unit green; device 21 (new `cardTitlesCanBeIconsAndTheFaintIconIsASetting`, the 52 dp plate check in
`MediaCardTest`, CallCardTest, PortraitCardsTest, settings pages) green; ReleaseSmokeTest 2/2; the APK launched clean.
**Phone checks:** whether 5.5 % is visible on the phone's OLED in daylight (it's a constant, `WATERMARK_ALPHA`).

### 4.3c (v4.5, versionCode 30): daily greeting

- **Rule (`greeting/Greeting.kt`, pure):** `shouldGreet(today, lastDay, enabled, busy)`; busy = trip running / paused or
  navigation not idle, which makes it wait for the next open that day. `greetingFor(hour)`: morning 04–11, afternoon
  12–17, evening otherwise. `GreetingStore` keeps `greeting_last_day` (ISO date) in the settings DataStore file, outside
  `SettingsRepository`, like the changelog.
- **"Not on the setup day":** `SetupViewModel.finish()` marks today greeted, instead of a setup-day parameter in the rule.
  *Reason:* one stored fact, no second key, and the pure rule stays four inputs.
- **When it's checked:** `GreetingSheet` calls `onOpened()` under `repeatOnLifecycle(STARTED)`, so every return to the app
  counts as an open (rotations don't recreate the activity). The day is marked the moment it shows, so a process death
  never repeats it.
- **Before the changelog:** the dashboard composes `ChangelogSheet` only while the greeting is down.
- **Auto close:** 8 s after the trip state turns Running (`GreetingViewModel.AUTO_CLOSE_MS`). "Moving" = Running: auto-start
  only starts a trip on movement, and a manual Start means the rider is leaving.
- **Content:** bike name, `TankGauge` + cautious range (56 sp, red in the last segment), odometer, this week (Monday on,
  saved trips + the live one) in km like the Garage, service only when SOON / OVERDUE. No bike: "Add your bike to see its
  range here" + Open Garage. Wide (≥ 600 dp): figures beside the range so the sheet stays short.
- **Setting moved:** "Daily greeting" lives on **Trip and Garage**, not Display (spec). *Reason:* on Display it fell below
  the fold in landscape (4.2 no-scroll rule), and the greeting is bike status. Display's Theme row is now inline on wide pages.

**Design review:** G1–G6 pass (tokens; red only for low fuel, amber / red for service status; sentence case, no "!").
C1–C7 pass (one sheet pattern with Done like the changelog; spacing 16/20/24/32).

| Screen | Hier. | Glance | Consist. | Touch | Copy | Calm |
|---|---|---|---|---|---|---|
| Greeting, landscape | 3 | 3 | 3 | 3 | 3 | 3 |
| Greeting, portrait | 3 | 3 | 3 | 3 | 3 | 3 |
| Settings, Trip and Garage / Display | 3 | 2 | 3 | 3 | 3 | 3 |

**Tests:** `GreetingTest` (unit); `GreetingSheetTest` (device: once a day, no bike → Garage, bike → range first, off stays
away), settings pages, `SetupFlowTest`; ReleaseSmokeTest 2/2; APK launched clean.
**Phone checks:** the greeting the morning after installing (it skips the setup day on purpose); that it doesn't pop up
when coming back from Maps mid-day (same day = no).

### 4.3d (v4.6, versionCode 31): riding behaviour in the fuel estimate

- **Model (`garage/RidingLoad.kt`):** per trip, running sums over the accepted fixes (`LoadAccumulator`, no allocation per
  fix): distance, Σv²·Δd, Σmax(0,Δv)·v on an exponentially smoothed speed (α 0.5, so GPS jitter isn't "speeding up"),
  climb (10-fix moving average of altitude, 2 m dead band, descents not credited), stops (1 m/s after 3 m/s). A gap over
  10 s (pause, tunnel) adds no distance and restarts the smoothing. **Load = J per kg per km**: g·Crr + (½ρCdA/m)·v² +
  accel + g·climb, with scooter constants (Crr 0.015, CdA 0.6 m², 200 kg). *Reason:* a physical sum puts the four parts on
  one scale without tuning weights by hand; its absolute size doesn't matter, the fit calibrates it.
- **Stored:** Room v7 (`MIGRATION_6_7`): `trips.loadIndex` (the J/kg/km figure) + the raw sums, all nullable.
  `SpeedSample.altitudeM` (defaulted). Backup format stays 2 with optional fields.
- **Not "relative to the bike's baseline" (spec):** the fit uses the absolute figure, and the factor compares the recent
  load with the spans' own mean load. *Reason:* a median-of-trips baseline would drift as trips are added and change the
  fit's input after the fact; a ratio of two fitted km/L values needs no baseline and means the same thing.
- **Learning:** used full-to-full spans (`TankMeasure` gained `startTimeMs`), each with the distance-weighted load of the
  trips ending inside it, only if those trips cover ≥ 50 % of the span's odometer km. **Weighted least squares** in
  closed form (recent spans weighted (1 − 0.3)^age, like `learnConsumption`) instead of recursive least squares. *Reason:*
  with 4–30 spans, recomputing from scratch is cheap and gives the same answer, and it can't drift from a bad old state.
  Kept only with ≥ 4 spans, distinct loads, slope < 0 (harder riding costs km/L) and a residual per degree of freedom
  below the plain mean's.
- **Range:** `factor = kmPerL(recent load) / kmPerL(mean load)`, clamped 0.8–1.2, applied to the **range only**
  (`FuelLevel.loadFactor`). Litres left still burn at the cautious km/L. *Reason:* the level is a measurement walked from
  the last fill; the range is the forecast.
- **Recent load:** the live trip (published by `TripRecorder` at each 60 s checkpoint through `LiveRidingLoad`, so the
  Garage doesn't redraw at 1 Hz) and then saved trips, newest first, until 20 km.
- **Note:** "Riding hard: −8%" / "Riding easy: +6%" (`garage_load_note`, textDim) under the range in the bike detail and
  the expanded Garage card, only beyond ±5 %. No colour: it explains the number, it isn't a warning.
- **Cost:** per fix one haversine, a few multiplies and a 10-slot ring buffer; no new GPS request, no per-fix storage
  beyond the track that was already kept.

**Design review:** G1–G6 pass (text only, tokens). C1–C7 pass (same text style as the fuel line). Garage detail with the
note: Hierarchy 3, Glanceable 3, Consistency 3, Touch n/a (3), Copy 3, Calm 3.

**Tests:** `RidingLoadTest` (sums, smoothing, gap, climb + dead band, fit rules, clamp, note, recent load, span coverage),
`GarageRepositoryTest` (3 spans: factor 1.0; 4 spans + hard recent riding: lower range + note), `TripRecorderTest` (sums
saved), `GpxBackupTest` (fields round-trip, old trips without them); device `MigrationTest` (v1 under v7, load query),
`GarageScreenTest.hardRidingLatelyLowersTheRangeWithANote`, TripFilesTest, trip/summary/history tests; ReleaseSmokeTest 2/2.
**Phone checks:** whether the phone's altitude is steady enough for climb (if it is noisy, the dead band can grow, constant
`LOAD_ALT_DEAD_BAND_M`); the note only appears after four full fills with trips recorded on v4.6, so not on the first rides.

### Run 4.3 summary (finished 2026-11-24)

| Build | Version | Commit | APK |
|---|---|---|---|
| 4.3a consistency fixes | 4.3 (28) | see git log | `.dev/apk/NativeAuto-4.3-4.3a-release.apk` |
| 4.3b look | 4.4 (29) | | `.dev/apk/NativeAuto-4.4-4.3b-release.apk` |
| 4.3c daily greeting | 4.5 (30) | | `.dev/apk/NativeAuto-4.5-4.3c-release.apk` |
| 4.3d riding load | 4.6 (31) | | `.dev/apk/NativeAuto-4.6-4.3d-release.apk` |

- Every build: the tests of what it touched, ReleaseSmokeTest 2/2 on the minified build, the exported APK launched with no
  crash entry. Design shots in `.dev/scratch/design/` (overwritten per run; the scores above are the record).
- **Emulator:** the system server crashed in a loop at the start (Wi-Fi network stack). Cold boot + `svc wifi disable`.
- **Line endings:** Python rewrites on Windows turned some files to CRLF in the 4.3 commits; the four commits were rewritten
  locally (not pushed) to keep each file's original endings, so their diffs show only real changes.
- **Deviations from the spec, each with its reason above:** Daily greeting switch on Trip and Garage (not Display); load
  compared with the spans' mean, not a trip-median baseline; closed-form weighted fit, not RLS; no watermark on the speed,
  map or call cards; the call card already had plated buttons.

**Phone checks, all builds:**
1. Settings: every tab the same height; nothing cut off; Reset trip / Reset app turn red on the first tap.
2. Hold a card: no Fullscreen / Maximize tiles where the card has its own buttons; portrait slim rows still offer them.
3. Low fuel: the red pump in the top bar beside the battery, and gone when the Garage card is on screen.
4. Rows trip summary: the "+ Add" km/L tile is easy to hit with gloves.
5. Fullscreen navigation (landscape): arrow left, distance and road beside it; "Slight left" spoken.
6. Icon titles and faint card icons (Settings, Display): readable in sun; the faint icon really faint on OLED.
7. Media skips on their plates; swipe still changes track.
8. Next morning: the greeting with the range; not again that day; not during a trip.
9. Range: unchanged until four full fills on v4.6; then a "Riding hard / easy" note only past 5 %.

## Run 4.6.1-4.6.3 (unattended, rider's "Start 4.6.1 to 4.6.3 unattended", 2026-11-24)

**The emulator part came later the same day** (Claude Code had stopped the emulator for low memory; the rider restarted it
twice). The notes "shots pending" / "not run" under each build are from before that; the results are in "Device check" at the
end of this section.

### 4.6.1 (versionCode 32)

- **Settings jump:** not reproduced. `sheetGesturesEnabled = false` on the settings sheet only: its nested-scroll link is the
  suspect, and cutting it removes the hand-over of a fling. Cost: no swipe-down to close (Done, Back, tap outside). Each tab
  also keeps its own scroll position now.
- **Face rule (`ui/speed/SpeedFace.kt`, rider):** `SpeedFaceState` (pure) + `RidingDetector` (auto-start's 10 km/h proof, held 3 s;
  the first usable fix after opening needs no hold). Replaces 13a's "always opens on the speed face". No location access or
  location off shows the speed face: that is where Allow / Turn on sits.
- **Today tile (rider, option A):** `periodStat` is the last tile on the Expanded log face and portrait Fullscreen. Tile size
  follows the number of rows (rule #6, never the measured height); a short phone scrolls. Landscape Fullscreen keeps the big
  line: it has half a screen to itself. Small log faces label two lines, "Trip 3.4 km · 12:04" and "Today 42.3 km". *Reason
  against the one line proposed earlier:* it would lose the trip time or overflow a half-width card.
- **Header:** four stacked cards get the same 6 + 48 dp as the other portrait cards; only slim rows beside a maximized card stay lower.
- **Low fuel (rider; 30 km proposed, taken with "start"):** `bikes.lowFuelKm` (Room **v8**, `MIGRATION_7_8`), `FuelLevel.low`
  (the range shown <= the warning) is the one rule for the header pump, Garage card, bike detail and greeting. `TankGauge` turns
  red on it, not on the last segment. Own values 20 to 150 km. Backup field optional, format 2 unchanged.
- **Range adjustment off (`RANGE_FOLLOWS_RIDING`)** until 4.7; trips still record the load. The 4.6 changelog's "stop-and-go" was
  wrong (stops were counted but unused) and is corrected.

**Design review (by reading; shots pending):** G2 pass (choices 56 dp), G4 pass (one low-fuel rule; Today labelled), G6 pass (the
slide happens once on setting off, at the rider's request). C1, C4 pass. G1, G3, G5 and the critique scores wait for the shots.

**Tests:** `SpeedFaceTest` (8), GarageRepository / backup cases; 507 unit tests green. Device tests changed, **not run**:
DashboardScreenTest, GarageScreenTest, TripSummaryDashTest, MigrationTest (v1 to v8).
**Phone checks:** the settings scroll; the face slides when setting off and stays at lights; the 30 km warning against the dash.

### 4.6.2 (versionCode 33)

- **Four tabs (rider: too empty, wrong pages; merge proposed, taken with "start"):** Trip and Garage, Display, Map and layout, Others
  (+ Debug). `SettingsCategory` kept TRIP_GARAGE / DISPLAY / LAYOUT, gained OTHERS. A tab remembered from an older version that no
  longer exists falls back to the first (it would have crashed on `first {}`).
- **Trip automation** (`trip_automation`): auto-start + hold and auto-resume on the left, auto-pause and auto-end on the right. "Trip
  options" became **Trip figures** (distance total + figure chips); its tags (`settings_trip_options`, `trip_options`) kept.
- **Display balance (rider):** left are rows about the cards (theme, titles, faint icons, call card), right speed unit, accent, screen
  options. "Colours" split in two: **Accent colour** here, **Route colour** on Map and layout (it is the map's). *Reason:* one
  dialog reached from two pages would be two names for one thing. Accent dialog is one column, 640 dp at most.
- **Map and layout:** left the arrangement (orientation, map width, hold), right map and navigation (voice, auto layout, route colour).
  The orientation hint is one line, so the column fits a landscape phone.
- **Others:** left greeting, What's new, Back up, Import; right call permission, app permissions, notification access, Reset app. Four
  rows of 64 dp plus the stacked greeting is a little taller than a landscape phone's page: it scrolls. *Reason for accepting it:*
  squeezing Back up and Import into one row would cut "never overwrites", the line that makes Import safe to tap.
- **Call permission:** one row with its state in the hint. Refused earlier: Android answers without a prompt (under 0.6 s), and the
  button then opens the app's page in Android's settings. Granted: the button opens the same page.
- **Greeting:** `GreetingMode` ONCE_A_DAY / EVERY_OPEN / OFF. The 4.3c switch is read once as the starting value (off stays off).
  "Every open" = a fresh start or back after a minute away (`AWAY_MS`). *Reason:* a permission screen or the Import file picker
  also counts as leaving the app, and a greeting over Settings on the way back would be noise.
- Inline choice rows are 64 dp like the other rows (C4).

**Design review (by reading; shots pending):** G2 pass (56 dp), G4 pass (each control in one place; names say what they do), C2 pass
(no new accent use: the two dots show the colours themselves), C4 pass. G1, G3, G5 and the critique scores wait for the shots.

**Tests:** `GreetingTest` (modes, carry-over), unit tests green, device tests and smoke test compile. Device tests changed, **not
run**: DashboardScreenTest, GreetingSheetTest, DesignShotsTest, ReleaseSmokeTest.
**Phone checks:** every tab in both orientations; the call permission after a refusal; "Every open".

**Build note:** the 4.6.1 release build started in the background failed once in `lintVitalAnalyzeRelease` (a lint detector crash) while
4.6.2 files were being edited under it. On a still tree the same task passed, so the edits are the likely cause. No source was
changed for it. Release builds of all three commits are repeated at the end of the run with nothing else writing.

### 4.6.3 (versionCode 34)

- **One portrait scale (`ui/theme/PortraitSizes.kt`):** sizes by role (hero, value, value with unit, long value, shared value, title,
  secondary, figure, date, glyph, art, play) for four shapes: Slim, Mini, Narrow, Row. `portraitSizes(mode)` picks one; 4.9 adds
  tablet shapes there. Landscape untouched. Tall portrait cards (Expanded, Fullscreen) keep their sizes: they were not small.
- **Slim rows are their own shape** (`LocalSlimRow`: Mini while another portrait card is maximized, from the layout state, rule #6).
  *Reason:* they have about 120 dp of content against 161 in the four-stack; one size for both is either too small or clipped. They also
  get back the 4.1.1 header: the one from 4.6.1 cost them about 14 dp.
- **Not enlarged: the speed digits.** Three digits plus the unit already fill their column in Mini, Narrow and Row.
- **Garage:** the value is 56 / 72 sp (was 36 / 56); a value over 7 characters (an odometer) takes the smaller "long" size. Half width
  stacks the gauge over the value, because side by side "185 km" at 44 sp doesn't fit 190 dp.
- **Navigation:** line heights are explicit (they inherited 24 sp whatever the text size), which is where the room came from. Slim
  rows give the road one line.
- **Log face:** a half-width card splits trip distance and time (a long trip was cut); slim rows use the smaller controls.
- **Faint icon (rider: on the edge, not obstructing):** 96 dp (128 in fullscreen) instead of 80 % of the card, cut by the right edge;
  media and clock lift it above their button row. In the scrolling Garage card it can pass behind a row while scrolling.

**Design review (by reading; shots pending):** G2 pass (Play 60 to 72 dp, controls 54 to 64 as before), C1 and C3 pass (one scale, one
title size), G5 to check on shots (faint icon). Hierarchy, glanceability and calm scores wait for the shots.

**Tests:** `PortraitSizesTest` (order, minimums, and the fixed-height stacks add up), unit tests green, device tests compile. **Not
run:** PortraitCardsTest, PortraitDashboardTest, DesignCardShotsTest.
**Phone checks:** every portrait layout, also with one card maximized; 12-hour clock in a half-width card; the faint icon on each card.

### Device check of 4.6.1 to 4.6.3 (2026-11-24, emulator)

- **Device tests, all green on the final code or on code the later edits don't touch:** MigrationTest (v1 to v8),
  TripSummaryDashTest, PortraitCardsTest, PortraitDashboardTest, SetupFlowTest, CallCardTest, GreetingSheetTest, MapPaneTest (7 + the
  traffic test skipped: no traffic on the free map), then after the last edits again DashboardScreenTest (54), GarageScreenTest and
  MediaCardTest (19 together). 514 unit tests green. They ran on the code as it stands after 4.6.3, not on each build separately.
- **App bug found and fixed:** location refused and then allowed left the speed card on the speed face while standing still.
  `SpeedFaceState.onBlocked(false)` now goes back to the log face unless moving, riding a trip or swiped by hand.
- **Failures that were not the app:** "System UI isn't responding" dialogs held the focus (11 tests); the emulator repeats its last
  fix with its speed, so the app was "riding" at 37 km/h (the face test now skips itself then); the Garage odometer test expected
  12.200 instead of 12.210; the "every open" greeting test changed the setting before the launch was handled (cause inferred,
  the changed test passes); a layout test expected the speed face.
- **Design review on shots, fixed:** "Press play to resu..." cut in portrait (now "Press play"); the faint note ran through the
  progress line and the total time (lifted 184 dp, portrait fullscreen keeps 108); the picked settings tab could stay half under
  the fade (it is brought clear of it); the Garage row's button said "Garage" beside "Open" buttons (now "Open").
- **Logged, not fixed:** half-width log controls are about 54 dp (three don't fit 190 dp at 56: G2 exception, as since 14b); a landscape
  settings page that is too tall ends mid-row at the fold (it scrolls; C6 allows it, a bottom fade would be nicer); the four-stack clock
  shows no date (since 14b).
- **Scores (0 to 3, hierarchy / glance / consistency / touch / copy / calm):** 4.6.1 log face 2/2/3/2/3/3; 4.6.2 settings 2/2/2/3/3/2;
  4.6.3 portrait cards 3/3/3/3/3/3. Gates pass except the G2 note. Colour tokens unchanged (G3). **Not seen on a shot:** the new dialogs (Trip
  automation, Trip figures, Accent, Route), portrait guidance, the call card, portrait in the light theme; the device tests open them
  or measure them, except the light theme.
- **Fix placement:** one follow-up commit on 4.6.3, same version (its APK had not left the PC). *Reason:* nothing to rewrite, and the
  4.6.1 / 4.6.2 APKs are fallbacks. They therefore **keep the location bug and the four shot findings that apply to them.**
- **Release:** ReleaseSmokeTest 2 of 2 on each of 4.6.1, 4.6.2 and 4.6.3 (each at its own commit). The exact APKs were installed over a
  fresh v4.6 one after the other and launched, no crash, the right "What's new" each time. That install held no bikes or trips: old data
  is covered by MigrationTest only. APKs in `.dev/apk/` (`4.6.1-461`, `4.6.2-462`, `4.6.3-463`).
- **Memory:** the PC ran out again once. Small batches, Gradle heap 2 to 2.5 GB, 2 workers, `gradlew --stop` between and a check
  of the free memory before each batch got it through.
