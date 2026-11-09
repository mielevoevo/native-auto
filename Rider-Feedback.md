# Rider feedback & ideas

Collected from the user's on-bike testing (week of 2026-11-02) and the brainstorm that followed.
Brainstorm in progress: append new ideas here as they come up; nothing below is scheduled yet.

**Field test result:** speed and its stats, the map and the media controls all work on a real bike.
Navigation works but was barely used (see 1). Trips are saved.

---

## 1. Place search is too strict

- Routing works, but Photon (OpenStreetMap) search only finds a place if you type its name almost exactly.
- Google Maps is much more forgiving with typos, partial names and businesses, so navigation was hardly used.
- **Goal:** search about as forgiving as Google's.

## 2. Trip controls

### Problem
- End trip and Reset both leave a new trip running on their own, even when the app isn't being used.
  The GPS stays on and drains the battery.
- The only ways to end a trip are the notification panel and Reset trip in settings. Neither is handy on a bike.

### Behaviour
- No trip starts by itself. A trip starts only when you press Start.
- **End trip** stops recording, but the GPS keeps running for the map (it keeps following you).
- **Reset** must not leave a trip running behind your back either.
- The GPS turns off completely only when the app is closed (see 3).
- **End** uses Reset's double-tap confirmation: the first tap turns the button red ("Tap again to end").

### Speed card: two faces, switched by swiping
- **Speed face:** the current speedometer.
- **Log face:**
  - Shows the current trip's stats; all zero when no trip has been started.
  - Controls are music-player-style icons with no text labels: ▶ start/resume, ⏸ pause, ⏹ end
    (⏹ turns red on the first tap).
  - Both faces share the same trip. Ending a trip on the log face resets the speed face's trip numbers
    (distance, time, averages, max) to zero until the next Start.

### End-of-trip summary
- When a trip ends, a summary fills the Speed card (inside the card, not the whole screen), styled like a
  game's "mission complete" screen.
- Shows the trip's stats: distance, time, average and max speed, the moving stats if their view is on,
  and a route thumbnail.
- Optional: the numbers count up quickly when the summary appears.
- Stays up until you dismiss it (tap or swipe), then goes back to the log face with everything at zero.

## 3. Closing the app

- Double back sometimes leaves the app running.
- **Back hint:** on the home screen (no sheet or other screen open), one back press shows a short message
  "Press back again to close". A second press within about 2 s closes the app.
- When settings or another screen is open, back closes it as usual.
- Closing (double back, or swiping the app away in recent apps) stops everything: the GPS, the tracking
  service and its notification.

## 4. Telemetry: always record everything, choose what to view

- **Every trip records the full preset set of telemetry, always.** Settings only choose what is
  displayed, never what is recorded, so any stat can be pulled up later for any trip.
- **Telemetry settings section:** a list of the stats with a show/hide toggle for each, applied to the
  log face, the summary and the history detail.
- Recorded set:
  - Distance and trip time (excluding pauses).
  - Average and max speed.
  - **Moving time:** time above about 3 km/h.
  - **Stopped time:** time below that on a running trip (lights, traffic). Pause is separate.
  - **Moving average:** distance ÷ moving time.
  - Start and end time and place.
  - **The full GPS track:** every fix with time, position, speed and bearing (needed for export).
- Moving and stopped time add no GPS use. They're worked out from fixes the app already receives.
- **Checked (2026-11-08):** trips store **only a simplified path** (`history/Database.kt`, an encoded
  polyline) with no time or speed per point. A new full-track table (per fix: time, lat/lon, speed, bearing,
  accuracy) is part of this work. Old trips can only export a path without timestamps.

## 5. Trip history

- Group trips by day under headers ("Today", "Yesterday", "Mon 3 Nov"), with each day's total distance.
- **Export** a trip through Android's share sheet (to Drive, chat apps or other apps):
  - **GPX** (default): the standard GPS track format, read by Strava, Komoot, OsmAnd, Google Earth and others.
  - Optional: **KML** (Google Earth / My Maps) or **CSV** (spreadsheets).

## 6. Garage: fuel and maintenance (being expanded)

All of this runs on distance the app already records. No extra sensors.

**User's bikes:** two Yamaha NMAX (scooter, CVT): a 2017 and a 2020.
About 35–38 km/L, more when riding hard. Rides in Indonesia (prices in IDR). Always fills to a full tank,
but partial fills must work too. The Garage must stay lean and expandable to other bike types later.

### Bike profiles (several, swappable)
- The user has two bikes:
  - **"Gen 1"**: NMAX 2017 (gen 1, 6.6 L)
  - **"Gen 2"**: NMAX 2020 (gen 2, 7.1 L)
  - The phone moves with the user, so you pick the **active bike** and everything follows it.
- **Per bike:**
  - Name and photo or colour (optional)
  - Tank capacity and reserve
  - Fuel type
  - Starting km/L
  - Odometer
  - Its own consumption learning, refuel log, service list and service history
- **Trips are tagged** with the bike that was active, so the odometer and service counters only count that
  bike's rides. History can filter by bike.
- **Switching bikes:** a quick picker on the Garage card or screen ("Riding: Gen 1 ▾").
- **"Which bike?" after a big change of location: debug builds only.**
  - Asks at the first trip start after a big change of location (for example YK → home).
  - Gated behind `BuildConfig.DEBUG`, so it's in the user's own build but not in a shipped release.
  - Privacy note for a public release: the check would be on-device only, using the trip's own fix and the
    last trip's start place, with nothing sent anywhere. Even so, inferring "home" from location needs a
    clear disclosure and an opt-in before shipping. Left out of release builds until then.
- **Keep it lean for v1:** scooter-CVT only, as NMAX presets:
  - **NMAX gen 1 (2015–2019, e.g. the 2017):** 6.6 L tank
  - **NMAX gen 2 (2020+):** 7.1 L tank
  - Default CVT service intervals for each (editable).
- **Bike types, WIP for later:** manual-chain, sport and others, each with its own service template. Keep
  the data model ready (the bike has a type field) but don't build the other templates yet.
- **Odometer:** enter the bike's real reading once. After that the app adds every recorded trip to it.
  GPS distance drifts from the bike's odometer by a few percent, so you can correct the reading at any time
  (for example at a fill-up) and the app learns the correction factor.

### Fuel
- **Tank counter:** km since the last fill-up, and the **estimated range left**:
  (fuel in tank × consumption) − distance ridden since.
- **Refuel log** (the Refuelled button on the Garage card or screen, a few taps):
  - **Full tank** (the default; the user always fills up) or **partial**.
  - **Amount, either way:** **money paid** (Rp) or **litres**. One is worked out from the other with the fuel
    type's price per litre (litres = Rp ÷ price/L). The pump's price can be corrected at that moment, and it
    updates the stored price.
  - Odometer is filled in automatically from GPS trips and can be edited.
  - **Every entry feeds the learning:**
    - full-to-full: the km between two full tanks ÷ all litres added in that span (partials included)
      = measured km/L
    - partial fills: add litres to the estimated tank level (capped at a full tank), so the range stays right
      even without a full fill-up
  - From full-to-full fill-ups the app works out your **real consumption**, so the range estimate gets
    more accurate over time.
- **Light learning logic** for consumption:
  - Start from the profile value (36 km/L).
  - After each full-to-full fill-up, blend in the measured km/L as a weighted average where recent fill-ups
    count more (a moving average that follows recent riding).
  - Riding hard for a week lowers the estimate gradually, and calmer riding raises it again. One unusual
    tank doesn't swing it.
  - Ignore obvious outliers (a forgotten fill-up, a typo).
  - Later idea: adjust the range during a tank using that tank's riding style (high average or max speeds).
- **Stats:** km/L trend, fuel cost per month, cost per km.
- **Refill cost estimate: hidden, on demand.**
  - Never shown by default, not even when the Garage opens, so refilling doesn't turn into a sum to worry about.
  - A "Calculate refill" action (behind a button, or turned on in settings) works it out when asked:
    the litres needed to fill up now (tank − estimated fuel left) × the price per litre of the fuel type.
    Example: "Next refill ≈ 5.0 L × Rp 19.600 = Rp 98.000".
  - Fuel types with editable prices per litre (for example Pertalite, Pertamax, Pertamax Turbo). The price
    from the last refuel log is used by default.
  - **Spend per period** (week or month), based on how much and how often you refill, in the same opt-in view.
- **Low-fuel warning:** the tank's last segment (≤ 20%) turns red on the Garage card (see below). No
  pop-up while riding. Whether the speed card also shows it when no Garage card is visible is an open question.
- **Range on the dashboard:** as a stat on the log face or a pane in quad view (10).

### Service reminders
- Items with an interval in **km and/or months**, whichever comes first. Presets you can edit:
  - Engine oil
  - Chain clean/lube and slack check (manual) or CVT belt / rollers (scooter)
  - Tyre pressure check, tyre replacement
  - Brake pads and brake fluid
  - Air filter, spark plug, coolant
  - Custom items
- Each shows **due in X km / Y days**, turning amber when it's close and red when it's overdue.
- **Mark done** logs the date, odometer reading, and optional cost and note. That builds a **service history**.
- **Dates that aren't about distance:** vehicle tax or registration, insurance, licence renewal, as
  date-only reminders.

### When reminders appear (safety)
- Never as pop-ups while riding.
- At **trip start** (a small badge on the log face, for example "Oil due in 120 km") and on the **trip-end
  summary**. Optionally a normal Android notification when the bike is parked.

### Where it lives
- A new **Garage** screen (from settings or history): bike profile, refuel log, service list and history.
- **Garage card (pane type):** shown on demand, for example as the 4th pane in quad view (10), or picked for
  a slot in any layout. No cost figures on the card (see the refill cost estimate above).
- In a normal-size pane, the card shows **one tracked value only**, big. The
  default is **range left**. When the card is maximized, it shows the full set, and there you **choose which
  value** the small card shows (range, km since refuel, next service, odometer, ...). The Refuelled button
  appears when the card is maximized.
- **Range display on the small card:** a fuel-tank icon with **5 chunky segments** (stacked bars, like a
  retro battery meter). Each segment = 20% of the estimated fuel in the tank.
  - The segments are **white**. As fuel is used, one segment disappears at each 20% step.
  - At the **last segment** (≤ 20%), the remaining segment and the number turn **red**. No amber step and no
    blinking.
  - A small hysteresis (~2%) so a segment doesn't flicker on and off around a boundary.
  - Empty segment slots stay as faint outlines, so the tank's shape is always visible. Then
  the icon, the value "148 km" big next to it, and "range left" below in small dim text. The header shows the
  bike's name (e.g. "Garage · Gen 1").

## 7. Weak map data: replace or add to it

- **Real-world workaround today:** on the user's phone (Redmi Note 11 Pro 5G "veux", HyperOS), Google Maps
  runs as a HyperOS floating window on top of Native Auto's map. That says the map card needs Google-level
  local data.
- The search problem (1) and the map data problem are the same issue: OSM's local places and addresses are thin.
- Options (prices and terms to check before choosing):
  - **A. Google Maps (`GoogleDashboardMap`, already built).** Turned on as soon as `MAPS_API_KEY` is in
    `local.properties`. Map display is free, but Google needs a billing account (a card) even for free use.
    Place search (Places API) is billed per request after a free monthly allowance. Blocked on billing.
  - **B. Keep the OSM map, use a commercial search service.** For example HERE, TomTom, Mapbox or Geoapify.
    Their free tiers are generous, and some don't ask for a card (to check). Commercial place data is usually
    much better than OSM, but how good it is locally has to be tested.
  - **C. Send places from Google Maps to Native Auto.** Search in the Google Maps app, then use its Share
    button to send the place to Native Auto, which routes to it with its own navigation. Free, no API key, and
    you get Google's search. It can be built now, alongside A or B.
  - **D. Commercial map tiles too** (HERE, TomTom, MapTiler) as a third map engine, if the OSM map looks too
    empty even with better search.
- **Free search options compared (Nov 2026; sources disagree, so verify on each pricing page):**

  | Service | Free allowance | Card needed | Place data | Fit |
  |---|---|---|---|---|
  | **TomTom** Search | ~20–30k requests/month (moved from per-day to per-month quotas) | Reportedly no | Commercial POIs | **Best first trial** |
  | **HERE** Geocoding & Search | ~30k/month (older sources say 250k) | **Yes** | Commercial POIs, strong in SE Asia | Good, but blocked like Google |
  | **Mapbox** Search | ~100k/month (temporary geocoding) | Likely yes | Own + commercial | Probably blocked |
  | **Geoapify** | 3,000 credits/day | No | **Mostly OSM** | Little better than Photon |
  | **LocationIQ** | ~5k/day | No | **OSM** | Little better than Photon |
  | Photon (current) | Free, fair use | No | OSM | The current problem |

  - The real question is **whose place data**: OSM-based services (Geoapify, LocationIQ, Photon) share the
    same thin local data. **TomTom** is the free, no-card option with commercial POIs.
  - **Routing stays on FOSSGIS Valhalla** (free, motorcycle costing, no-toll option, already working).
    Routing wasn't the complaint; search was. Free commercial routing alternatives (TomTom, GraphHopper)
    are possible later.
  - Plan: build search behind a `PlaceSearch` interface, add TomTom, and A/B it against Photon on local
    YK places.
  - **Java coverage (YK, JKT):** there's no public benchmark for Indonesian cities, so we test it ourselves.
    - **TomTom** lists Indonesia with address-point, house-number, street and city matching. Free monthly
      tier (new pricing since 2026-07-01; exact Search allowance and whether signup needs a card still to
      check on developer.tomtom.com).
    - **HERE** has long had strong Indonesian data, but it needs a card.
    - **GrabMaps** (the best SE Asia POIs, built from Grab rides) is only available through AWS Location
      Service, which also needs a card.
    - **OSM / Photon:** the OSM Indonesia wiki itself notes many Jakarta names are still missing.
  - **Test harness:** a debug-only screen that runs one query on every configured service side by side. A
    fixed list of ~20 local queries: well-known places (Malioboro, Stasiun Tugu, UGM, Monas, GBK), small
    businesses (warung, bengkel, kos), street + number, a gang, and misspellings. Score each service:
    found / right place / position in the list.
- **Decision:** **B is the main fix.** Add a better search service now, test it against local places, and
  use it until Google (A) works.
- **C dropped as a navigation feature:** if a place is already open in Google Maps, it's easier to start
  navigation right there in its floating window. **Kept as an idea** for a different role, to refine later.
  Possible roles:
  - Save a shared place as a favourite or quick destination.
  - Tag a trip with where it was going, so history shows "→ Office" instead of only addresses.
  - Mark refuel stations (links to 6).

## 8. Hide the map: speed + media only

- Option to hide the map card completely, so speed and media sit side by side in two panes.
- It must be easy to bring the map back, by a gloved hand on a moving bike. Ideas:
  - A map button next to the battery and gear in the status corner.
  - A thin "map" tab on the screen edge where the map was; tap or swipe it to slide the map back in.
  - The same switch in settings (always there, but slower).
- **Decided:** done through layout presets (10) plus map sleep, not as a separate feature.

## 9. Clock

- Show the time of day. Options:
  - In the status corner next to the battery (small, always visible).
  - Big on the speed face, or as its own pane in quad layout (10).
  - Both, with a 12/24 h setting.
- **Clock pane styles: digital or analog** (chosen per pane, e.g. by long-press or in the expanded pane).
  - **Digital:** big off-white numerals, date below in dim text.
  - **Analog, edge-tick style** (inspired by the iPhone StandBy analog face): no round dial. The **minute
    ticks run around the edge of the card itself**, following its rounded rectangle, with hour ticks longer
    or brighter. Thin off-white hands, a small accent (cyan or red) second hand that can be turned off (it
    moves every second, so it stays off by default to save power), black background.
  - Our own drawing in Compose `Canvas`: inspired by the layout, no Apple assets.

## 10. Quad pane (2×2) layout

- Support four panes, which also opens room for new pane types.
- Pane types to choose from:
  - **Map** (existing)
  - **Speed** (existing; speed and log faces)
  - **Media** (existing)
  - **Navigation:** large next-turn arrow, distance to turn, ETA. Readable without the map.
  - **Clock** (+ date, trip time)
  - **Compass / heading:** direction of travel (bearing is already recorded).
  - **Trip log** as its own pane instead of a face of the speed card.
  - Later: **weather / rain**, **fuel and tank** (6), **phone and notifications** (caller, incoming message).
- Each slot can hold any pane type.

### Layout switching (main feature to build next; being discussed)
- **Presets in the switcher:**
  - **Duo** (today): map on one side, two panes stacked on the other.
  - **Side-by-side:** two tall panes, e.g. speed | media, no map.
  - **Rows:** two long horizontal cards across the full width, e.g. speed on top and media below, no map.
  - **Quad 2×2.**
  - ~~Map + 3~~ **dropped:** on a ~390 dp-tall landscape phone, three stacked panes are ~110 dp each minus
    headers, too cramped to read at speed.
- **Switcher:** a layout button in the status corner (next to the gear) opens a strip of big preset
  thumbnails; one tap switches. Not a swipe: swipes are already used for map panning and the speed card faces.
- **Pane content:** long-press a pane header to choose what goes in it. Swap, maximize and fullscreen stay.
- **Map sleep:** when the current layout has no map, the map **sleeps**.
  - Within a grace period (~30 s), it's kept alive and comes back instantly (quick peeks).
  - After that, the map view is released (no GPU, no tiles, no draws). Only its camera and follow state are
    kept, so it returns in the same place, with a short fade-in.
  - **On wake after the timeout:** it comes back at the saved camera, takes about a second to get a fresh
    fix, then resumes following. A brief lag while it finds the location is acceptable (user agreed).
- **Switcher position:** confirmed, in the status corner next to the gear.
- **Quad default:** **map / speed / media / Garage** (everyday riding). When navigation starts, it
  auto-switches to **map / next turn / speed (with ETA) / media**, and switches back when navigation ends.
- **Rows layout:** confirmed as mocked up (speed on top with an inline stats line, media below).
- While the map sleeps, the GPS keeps running only if a trip or navigation needs it.
- **Auto layout switching** (allowed if it's cheap):
  - Driven by **events, not polling**: navigation start/end and trip start/end are state changes the app
    already has, so the cost is close to nothing.
  - Example rules (editable, each can be turned off):
    - navigation starts → a layout with the map
    - navigation ends → back to the previous layout
  - **Never** switch again for a while after the user changes the layout by hand (manual always wins).

## 11. Colour scheme: OLED-first

- The current colours feel unpleasant. Redo the theme for OLED screens, for visibility and power.
- **OLED dark (the new default):**
  - **True black `#000000`** backgrounds: black pixels are off on OLED, so they use no power and don't glow at night.
  - Cards are black with **faint outlines** (thin, low-contrast borders, about 15–20% white) instead of
    filled grey surfaces.
  - **Text:** off-white (about `#E8E8E8`) rather than pure white, which reduces glare and smearing on black
    when scrolling. Secondary text is dimmer grey.
  - **Direction (user's vision): black and white first, plus a cool accent so it isn't brutalist.**
    - In the current look (`nativeauto_v1.1.jpg`), the light-blue play button, purple search FAB, mauve
      "Turn on location" pill and navy-grey surfaces don't belong together. The white header icons work.
    - **Base: monochrome.** Black, a few greys, off-white. Buttons are neutral: white or grey fills and
      outlines, with a black or white icon.
    - **Accent family: cool blues.** A deep navy and a cyan, used sparingly so they feel deliberate. A
      starting palette, to tune on the phone:

      | Token | Hex | Used for |
      |---|---|---|
      | `bg` | `#000000` | screen background |
      | `surface` | `#0A0C10` | cards (barely off black) |
      | `outline` | `#262A33` | card borders, dividers |
      | `text` | `#E8EAED` | main text and icons |
      | `textDim` | `#8A9099` | labels, units |
      | `button` | `#E8EAED` fill, `#000` icon | main button (play, trip start) |
      | `buttonQuiet` | `#1A1D23` fill, `#E8EAED` icon | other buttons (skip, search, recenter) |
      | `navy` | `#0F2A4A` | active or selected backgrounds (toggled traffic, selected segment, pressed) |
      | `cyan` | `#3FC8E0` | small highlights: progress bar, active toggle tick, focus ring, live dot |

    - Rule of thumb: **at most one cyan element per card**. Navy fills things in, cyan points at them.
  - **Status colours (separate from the accent):**
    - Amber for "due soon" and low fuel
    - Red for alerts and confirm states (reset, end)
    - Recording: red dot or white (see Decided). No green.
    - Saturated but not neon, so they stay readable in sunlight and don't dazzle at night.
  - The map's dark style is matched to true black.
- **Light theme** as an option (the user's idea; they'll test it in daylight after it's built).
- **Theme setting:** OLED dark, light, or follow the system.
- **Card titles:** smaller and in sentence case ("Media", not "MEDIA"), in the dim text colour, so they label
  quietly instead of shouting. Header icons stay the same size (touch targets).
- **Later: colour presets** chosen by the user (accent colour, possibly whole palettes), then custom colours.
- Keep the haptics and the ≥56 dp targets. The theme only changes colours, not layout.

## 12. Smoothness: 120 Hz where possible

- **Observed on the phone (veux, 120 Hz):** the map pans smoothly, but maximizing a card, opening settings
  and other animations feel sluggish. Possibly the phone heating up, possibly the app.
- **Goal:** fluid motion at 120 Hz on a modest phone, with low power use.
- **Likely causes, to check in this order:**
  1. **Debug build.** The tested APK is the debug build: no R8, no baseline profile, and Compose runs
     much slower in debug. Measure the **release** build first. This alone may account for most of it.
  2. **The map resizing every frame.** During maximize or swap, the MapLibre TextureView is resized on every
     animation frame, which re-renders tiles and reallocates the texture at each size. Fix: animate a
     scaled or clipped layer and resize the map once at the end, or freeze the map to a snapshot during the
     transition.
  3. **HyperOS refresh-rate handling.** HyperOS can drop non-whitelisted apps to 60 Hz outside of touch.
     Ask for the high rate while animating (`Window` preferred display mode / frame-rate hints) and let it
     fall back to a lower rate when idle (saves power, and the 1 Hz data doesn't need 120 Hz).
  4. **Baseline profile and startup:** add a Baseline Profile module for the dashboard, sheet and
     animations (precompiles hot paths, so first animations stop stuttering).
  5. **Thermal:** check whether the phone is throttling on the mount (sun plus charging plus GPS plus
     screen). Log the thermal status and keep the GPU idle when nothing moves.
- **Measure, don't guess:** frame timing (`adb shell dumpsys gfxinfo`, a Perfetto trace) for maximize,
  swap, settings open and layout switch, on the phone, release build.

---

## Roadmap (proposed, 4 phases)

Phases continue the handoff's numbering (0–7 done, plus navigation/history).

**Sub-phases (each ends built + tested on the emulator).** **Phone tests: 4, one at the end of each phase**
(after 8d, 9d, 10b, 11c); the user reports any dissatisfaction then. The ★ marks below are only where
on-phone feedback matters most within that phase's test.

| Sub | Contents | Effort |
|---|---|---|
| 8a | Housekeeping: nav/history tests, CLAUDE.md section, lint | Medium |
| 8b ★ | Layout system: presets, switcher, long-press pane picker, map sleep, auto-switch | High |
| 8c ★ | Theme (OLED dark, light, map styles, titles) + clock card (digital / analog edge-tick) | Medium |
| 8d ★ | Smoothness: release build profiling, map resize fix, 120 Hz hints, baseline profile | High |
| 9a | Trip state machine: manual start/pause/end, Reset = discard, auto-pause/end, short-trip drop, close flow, notification End + self-close | High |
| 9b ★ | Log face (▶ ⏸ ⏹, stats, recent rides) + end-of-trip summary + back hint / save prompt UI | Medium |
| 9c | Full-track recording + DB migration + bike tag field + telemetry show/hide | High |
| 9d ★ | History by day, GPX export, ZIP backup/import | Medium |
| 10a ★ | `PlaceSearch` + TomTom + debug comparison screen (YK/JKT list) | Medium |
| 10b ★ | Google Maps links → our navigation; HERE when the card issue is solved | Medium |
| 11a | Bike profiles (Gen 1/Gen 2), odometer + correction, away-km flow, which-bike prompt (debug) | Medium |
| 11b | Refuel log, km/L learning, range, hidden refill cost calculator, spend | High |
| 11c ★ | Garage card (5-segment tank), expanded view, low-fuel icon, service items + history + date reminders, Garage in backup | Medium |

**Phase 8: Dashboard shell (layouts + look + smoothness)**
- *Start with housekeeping:* tests for the navigation/history code, the CLAUDE.md section, lint.
- Layout presets (Duo / Side-by-side / Rows / Quad), the switcher in the status corner, the long-press pane
  picker, auto-switch rules, map sleep.
- Clock pane plus the small clock beside the battery.
- The pane frame redone in the new theme: OLED black, faint outlines, sentence-case titles, monochrome
  buttons, navy/cyan accents. Light theme plus light map style.
- Smoothness: measure the release build at 120 Hz, stop the map resizing every frame, refresh-rate hints,
  baseline profile.
- *Why together:* every layout change rebuilds the pane frame and its animations anyway, so the theme and
  the animation fixes are done once, in the same code.

**Phase 9: Trips (life cycle + recording + history)**
- Manual Start/Pause/End, no auto-start, Reset = discard, End's double tap, End stops recording but keeps
  the GPS for the map.
- The speed card's log face (▶ ⏸ ⏹, stats, recent rides), the end-of-trip summary.
- Auto-pause / auto-end, dropping very short trips, the save prompt on double back, auto-save when swiped
  away, the back hint, closing the app properly, the app closing itself 10–15 min after a notification End.
- Full-track recording (all telemetry per fix), telemetry show/hide settings.
- History grouped by day, GPX export, ZIP backup and import.
- Bike tag on trips (the field only; the Garage comes in phase 11).
- *Why together:* the recorder, the trip states and the history all touch the same `trip/` + `history/`
  code and database migration.

**Phase 10: Search and links**
- `PlaceSearch` interface, **TomTom** first, the debug test screen with the YK/JKT query list.
- Google Maps links (share or paste) → our navigation.
- **HERE** added later as the more reliable option, once the card issue is solved.
- *Why together:* both feed the same "destination → Valhalla route" path.

**Phase 11: Garage**
- Bike profiles (Gen 1, Gen 2), odometer + correction, Gen 2 away-km flow (debug-only "which bike?" prompt).
- Refuel log (Rp or litres, full/partial), km/L learning, range, refill cost calculator (hidden), spend.
- Service items (user-set intervals), service history, date reminders.
- The Garage card (one value, a 5-segment tank), the expanded view, the low-fuel icon on the speed card.
- Garage data in the ZIP backup.
- *Last because* it runs on phase 9's recorded km and bike tags, and fills a phase 8 pane slot.

## Open questions

- 7: search: **TomTom first, then HERE** as the more reliable option (card issue later).
- Confirm the auto-pause / auto-end times (5 min / 30 min proposed).
- Build order: layout switching first (the user's pick), then? Check the release build's smoothness (12)
  before changing animations.

## Decided (2026-11-08)

**Trips**
- **Full track for everything:** every fix is stored with all telemetry (time, position, speed, bearing,
  accuracy). GPX export carries it all (speed and course in GPX extensions), so any stat can be pulled up
  later.
- **Reset = discard this trip:** ends it without saving (still a double tap).
- **Closing the app during a trip:**
  - **Double back:** asks "Save this trip?" with **Save / Discard / Cancel**.
  - **Swiped away in recent apps:** Android gives no chance to ask, so the trip is **saved automatically**
    (it can be deleted in history).
- **Auto-pause and auto-end** (for "forgot to close the app"):
  - Below ~3 km/h for **5 min** → auto-pause.
  - Paused (auto or manual) for **30 min** → auto-end and save.
  - Times editable in settings; values to confirm.
- **Very short trips are discarded automatically:** under ~300 m or under ~2 min of moving time (to tune).
- **Notification Pause/End stays.** Ending a trip from the notification stops the GPS at once. If the app is
  then left in the background, it **closes itself after 10–15 min** (if HyperOS hasn't killed it first).
- **Switching bike mid-trip:** unlikely; if it happens, the trip is tagged with the **last selected** bike.
- **Recent rides:** only on the **log face** (the speed card's second face), not on the speed face. The last
  2–3 trips as rows; tapping one opens history.

**Garage**
- **Refuelled button only on the expanded card**, to keep the small card uncluttered.
- **Low fuel on the speed card:** just a small red tank icon (no text, no pop-up), shown when no Garage card
  is on screen. Kept low-key because the estimate needs a few fill-ups to learn.
- **The odometer correction does NOT change the range estimate.** Range comes from the learned km/L only.
- **Service intervals are set by the user** (no Yamaha presets): bikes get modified, and the manual's
  intervals stop applying. The service list starts empty, or with item names but no intervals.
- **Backup:** export everything as a **ZIP** (a GPX file per trip plus the Garage data as JSON), and import
  it back.
- **Gen 2 used by others while you're away (decided, keep as is):**
  - Service and fuel counters run on the **odometer**, not only on km the app tracked.
  - When you switch to a bike that hasn't been active for a while (e.g. Gen 1 → Gen 2 after weeks), the app asks
    once: **"Gen 2's odometer now?"** (prefilled with the last known value).
  - The difference becomes an **"away km"** entry: it advances the service counters (oil etc.) but adds no
    trips to history.
  - **The fuel level becomes unknown** (someone else rode it), so the range shows "—" until the next
    **full** refuel. The km/L learning skips that span so other people's riding doesn't skew it.
  - Skippable: if you don't enter it, counters continue from the app's own km.

**Layout**
- **Clock:** its own pane type. When no clock pane is on screen, a small clock sits **beside the battery**
  in the status corner. Text sizes adjusted to fit.
- **Rows layout:** no face swiping on its cards (saves resources). The speed row shows the inline stats line.
- **Long-press on a pane header** opens the content picker (check for clashes with existing long-presses
  when building).

**Look**
- **Recording indicator: red** (a classic "REC" dot) **or white**, to try both on the phone. Not cyan:
  cyan is for other accents. (Green dropped from the status colours.)
- **Light map style:** to design, paired with the light theme.

**Search and links (7)**
- **Favourite places: not built.** Google Maps already does that; the app stays lean.
- **Open Google Maps links instead (option C reworked):** share a place or a maps link from Google Maps
  (or paste it) → Native Auto reads the coordinates or place name and **starts its own navigation**
  (Valhalla).
  - Short `maps.app.goo.gl` links have to be expanded first (one HTTP redirect).
  - If a link has only a place name, it goes through the search service.

## Decided (2026-11-09): riding without the app (untracked km)

Covers the phone left at home, the app not running, and the app killed mid-ride. Agreed with the user;
built in 11b (v3.1), trip recovery included.

- **No prompts, ever** (not at launch, not at a reading). Tracked trips keep adding to the odometer as now.
- **"Add untracked km" button** in the Garage's bike detail, next to Correct. The rider uses it when they
  remember. Stored as an odometer entry (kind UNTRACKED, re-anchors at estimate + km), with no trips.
  Counts for servicing and fuel (the rider's own riding, so km/L still applies: fuel is not marked
  unknown, unlike away km). Logged as "Untracked +42 km". Hidden until the odometer is set.
- **The real odometer is the source of truth:** every refuel (11b) asks for it, prefilled with the
  estimate.
- **The factor learns only from small gaps:** narrow `learnFactor`'s band (about 0.9–1.1 instead of
  0.8–1.2), so an untracked ride that goes unlogged isn't learned as GPS drift.
- **km/L from odometer deltas** between full fills (not GPS km), so untracked rides can't skew it.
- **Range is an upper bound between readings:** untracked km only show up at the next reading or button
  press. 11c's Garage card gets a quick odometer button.
- **Trip recovery (separate small step):** save the trip in progress to the DB about once a minute. On
  relaunch after a kill: last save under 10 min old → resume the same trip (straight-line join);
  otherwise save it as ended at the last saved point.

## Decided (2026-11-09): app font, to apply later

- **Geist** (Vercel, SIL Open Font License, free for a public release) replaces the default system font,
  so the app feels less industrial and less "default Android". Picked by the user over Switzer / Satoshi.
- **Applied 2026-11-09 (after 11c).** Was: not applied yet (the user's call: after the phase work). When it is: bundle the font files in
  `app/src/main/res/font/`, set it in the theme's `Typography` (every Material text style, plus `PaneTitle`),
  and check:
  - tabular figures still work (`fontFeatureSettings = "tnum"` on speed, clock, odometer);
  - the big digits (1, 4, 7) at clock and speed sizes, up to 180 sp fullscreen;
  - nothing that fits today wraps or clips (pane titles, compact media, settings columns in landscape).
- Optional later: a soft serif (Instrument Serif) for pane titles only, if Geist alone still feels plain.
