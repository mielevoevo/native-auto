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

## Revisions after the first look (v3.2.1, 2026-11-09)

The rider has looked at v3.2.1 on the phone, not yet on a ride: "overall it already looks like what I
envisioned", and it feels much snappier. Four revisions, planned as one phase (12) after this note.

### R1 · Fuel range card
- **Asked:** a card for the fuel range left, with its values taken from the Garage (no second calculation
  anywhere), and a way to handle consumption changing from ride to ride.
- **Plan:** the 11c Garage pane already reads `GarageRepository.activeCard` (the same `fuelLevel` the Garage
  screen uses) and can show range as its one value. R1 turns that into a proper range card: range big,
  litres left and the 5-segment tank under it, and "≈ km/L" with where that figure comes from.
- **Changing consumption:** today `learnConsumption` gives one smoothed km/L from full-to-full fills. Proposed:
  1. a **recent** figure (the last 2–3 full-to-full spans) next to the long-run one; range uses the recent one;
  2. a **range band** ("120–150 km") when the spans disagree by more than ~10%, so the card doesn't promise
     the optimistic end;
  3. later, with the 9c full track: km/L by riding style (city stop-go vs. steady cruise) from average speed
     and stops, used to adjust the estimate for the current trip.
  - **Open:** a band, or one number that leans to the cautious end? Should the card replace the Garage pane,
    or be a second pane (Garage = overview, Range = fuel only)?

### R2 · Speed card: period totals instead of recent rides
- **Asked:** replace "Recent rides" on the log face (cut off when the card isn't maximized) with a one-line
  "timed odometer": km ridden today / this week / this month. The choice is a setting.
- **Plan:** sums of saved trips (plus the live trip) from the trip history, so it matches the history screen.
  One line fits Compact, e.g. "Today 42 km". Setting (speed card): Day / Week / Month, maybe "all three" when
  Expanded. Week starts Monday (Indonesian calendar), month = calendar month.
  - **Open:** "units as options in the settings": does that mean the period (day / week / month), or km vs.
    miles (the speed unit setting already exists)? Assumed: the period.

### R3 · Card template: true black, drag to rearrange
- **Asked:**
  - Every card's background is pure black #000000 (today #0A0C10), same outline as now, so only text lights
    the OLED. Light mode stays as it is.
  - Cards can be dragged after a long hold (3–5 s) and dropped on the card they should replace. Both cards
    slide to their new places; e.g. in Quad, dragging the lower-right card onto the upper-left swaps the two
    with a slide.
- **Plan:** `OledDark.surface` → #000000 (outline #262A33 stays; check that the sheets and dialogs still stand
  apart from the dashboard). Drag: after the hold (see Conflict), a lifted card follows the finger, the
  card under it highlights, drop = swap through the existing assignment model, which already animates every
  pane to its new rect (`animateRectAsState`), so the slide comes for free. Rule #3 holds: panes are moved,
  never recomposed or reparented (the map isn't recreated).
- **Conflict:** today a long-press on a card's header opens the pane picker and a long-press on the card
  toggles fullscreen. The hold for dragging must not fight them. Proposed: drag starts from a **long hold on
  the header (≈ 1.5 s)**, the picker stays on a normal long-press (0.5 s) and opens if the finger lifts before
  the drag starts. 3–5 s, as suggested, is long with gloves on a stopped bike; it can be a setting.
  - **Open:** hold time, and which part of the card starts the drag (header only, or anywhere).

### R4 · Adaptive refresh rate (LTPO-like)
- **Asked:** like LTPO: low power when the screen barely changes (media only), balanced normally, smooth
  (120 Hz) when things move (map rotation, layout animation), to save battery and heat.
- **Plan:** 8d already asks for the fastest mode while a pane moves, then hands back to the system. R4 makes
  it a three-level policy chosen from what's on screen and moving:
  - **Low:** no map visible or the map asleep, nothing animating → ask for the lowest mode the display
    offers (on the Redmi Note 11 Pro 5G: 60 Hz; it has no LTPO panel, so 1 Hz isn't possible there) and keep
    our own redraws to the clock tick.
  - **Balanced:** map visible but not moving (stopped, not following) → 60 Hz.
  - **Smooth:** map following / rotating, a layout animation, a drag (R3) → the fastest mode (120 Hz).
  - Through `Window.preferredDisplayModeId` today; on Android 15+ also `View.setRequestedFrameRate` per view.
    Also: lower the map's own render rate when it's static (MapLibre redraws for every 1 Hz fix).
  - **Open:** a setting (Auto / Battery saver / Always smooth), or automatic only?
- **Measure:** frame stats and battery on the phone (release build), before and after.

### Decided (2026-11-09, rider's answers)
- **R1 range:** one **cautious** number, no band (bike ranges are short; a 30 km spread says nothing).
  - At the end of a ride, an **optional** prompt asks for the bike's own trip average (km/L from its
    dash); answers train the consumption estimate alongside full-to-full fills.
  - Later (**WIP, logic to be discussed**): average speed, climbs and spirited riding lower the range.
  - Focus now: the range card concept with the single cautious number.
- **R2 period totals:** "units" = the period: **Day / Week / Month**, a setting; the speed card shows that
  period's total. All distances in the app follow the existing km / mi setting.
- **R3 drag:** from **anywhere on the card**. It replaces long-press = fullscreen (fullscreen keeps its
  button). Proposal, open to the rider's call:
  - hold **~1 s** with a filling ring and a haptic tick, then the card lifts;
  - drag onto another card → both slide to swap;
  - lift and release without moving → the pane picker (replaces today's header long-press, so there is one
    hold gesture, not two);
  - hold time as a setting (1 / 2 / 3 s), default 1 s with gloves in mind (3 s suggested by the rider is the
    longest option).
- **R4 refresh rate:** a setting **Auto / Power saver / Smooth**.
  - Power saver: the lowest display mode, redraws only on real changes (clock tick, new fix), map at
    reduced frame rate.
  - Smooth: the fastest mode while anything moves.
  - Auto (default): LTPO-style, per scene: low when static (media, map asleep), balanced (60 Hz) for a
    still map, fastest only while the map follows / rotates or cards animate / drag.
  - In every mode: no needless redraws (nothing drawn when nothing changed); smooth only raises the rate
    while there is motion.

### Decided (2026-11-09, later): Phase 12 = v3.3
- **R4 refresh rate, revised:** animations always look 120 Hz. LTPO logic: any drag, card move or map
  motion jumps to the fastest mode at once, and it drops back down as soon as demand ends. The setting
  (Auto / Power saver / Smooth) decides how low it drops and how quickly. Nothing is drawn when nothing changes.
- **R3 drag:** the proposal above is accepted (1 s hold with ring + tick, lift-release = picker, hold time
  setting 1/2/3 s).
- **R1 / R2:** build as planned (range card with one cautious number + optional end-of-ride km/L;
  Day / Week / Month total on the speed card).
- **Version:** Phase 12 ships as **3.3** (a QOL phase, one version). Sub-phase builds keep versionName 3.3,
  versionCode +1 each.
- **Sub-phases:** 12a (look and feel: true black, drag to swap, refresh-rate policy and setting), then
  12b (numbers: range card, end-of-ride km/L prompt, period totals). Each ends with the release smoke test
  and a testable APK.
- **R1 addition (rider, 2026-11-09): a fuel level without a full fill.** The odometer alone can't say
  what's left, so the rider sets a starting level from the bike's own fuel gauge ("Set fuel level":
  bars out of the gauge's total, or a fraction) together with the odometer. Range works from there, rounded
  down (cautious). The first full fill replaces this estimate with a measured one. Part of 12b.
  - **Gauge bars (rider):** Gen 1 6 bars, Gen 2 8 bars. In 12b these are fixed by model: NMAX gen 1 = 6 bars, NMAX gen 2 = 8 bars;
    a per-bike gauge setting in the editor is **WIP, after Phase 12**.

## Phase 12c (planned 2026-11-09, build on the rider's "start"): adjustable fuel prices

- **Why:** fuel rates change; prices must never need a rebuild. Today the last pump price per fuel type
  (`fuel_prices`) already becomes the price, but there is no way to change it without a fill-up, and the
  defaults are stale.
- **Fuel prices section in the Garage:** a row for each fuel type (Rp/L, last updated); tap → edit dialog. Writes the same
  `fuel_prices` store (a fill-up at a new price still updates it). Past refuels keep their own price.
- **Defaults (rates as of 2026-11-09):** Pertalite Rp 10.000/L, Pertamax (92) Rp 15.950/L, Pertamax Green
  Rp 19.150/L, Pertamax Turbo Rp 19.600/L. Only used until the rider has a price of their own for that fuel.
- **Fuel types (rider, 2026-11-09):** four fixed types: Pertalite, Pertamax, Pertamax Green, Pertamax Turbo
  (Green added as a built-in type; no "add fuel type" UI yet). Cleaner labels: two lines in each segment,
  brand small on top, grade big below ("Pertamax" / "Turbo"; Pertalite as one word).
- **Text:** "Refuelled" → **"Refuel"** (button and card). Bike models: "NMAX Gen 1" / "NMAX Gen 2" instead of
  the year ranges.
- **Prices persist:** an edited price is stored on the phone (`fuel_prices`) and kept until changed again.
- **WIP, after 12c:** fuel types typed in by the rider. 12c keeps prices keyed by name (any string) so a
  custom type needs no migration later.
- Still v3.3, versionCode **18** (rider confirmed; Phase 13 moves to 19 / 20). Effort: medium.
- Tests: editing a price changes the refuel prefill and the refill estimate; defaults; release smoke edits a price.

## WIP: two-step feedback for the card hold (rider, 2026-11-09)

- **Problem:** after the hold, the card lifts, and letting go without moving opens the picker. On the mount
  it's hard to tell whether the hold has registered, and whether a release will mean "drag" or "pick".
- **Proposal:** two visibly different stages, each with its own buzz:
  1. **Lifted = drag mode** (after the hold time): ring completes, card scales up with a cyan outline, one
     short buzz. Moving now drags; releasing here does nothing (the card settles back).
  2. **Still held ~0.7 s more without moving = picker armed:** a second ring (or a grid icon) fills in the
     card's centre, a double buzz, the card dims slightly. Releasing now opens the picker.
  - Moving at any time drops back to drag mode, so a drag can't open the picker by accident.
- Applies to `ui/dashboard/PaneDrag.kt` (a new `armedPicker` state), `DragOverlay`, haptics. Timing as a
  setting only if the default doesn't suit gloves.

## Phase 14 (WIP, planned 2026-11-09, not started): call controls + portrait

**Versions (rider, relabelled 2026-11-14):** 14a ships as **3.5.1**, 14b as **3.5.2** (versionCode 22, 23; Phase 13 takes 19-21).

### 14a · Call controls (modular, any Android skin)
- **Goal:** works on HyperOS, near-stock (Motorola, Pixel), One UI and others: not tied to one dialer.
  - Modular: a `CallSource` interface with interchangeable backends, picked at runtime by what the phone allows:
    1. Notification listener (access already granted for media): read the call notification
       (`Notification.CallStyle` on 12+, or category `call`) and fire its own Answer / Decline / Hang up actions.
       Covers any dialer and VoIP apps that post CallStyle notifications (WhatsApp, Teams, Zoom, Meet...).
    2. `TelecomManager` + `ANSWER_PHONE_CALLS` / `READ_PHONE_STATE` (cellular): answer / end, call state.
    3. Audio route + mic mute via `AudioManager` (communication device, speaker / Bluetooth / earpiece) where the
       OS allows a third-party app; hidden when it doesn't.
  - Each control is shown only when some backend supports it on this phone (capability flags).
- **Behaviour (rider):** an incoming call doesn't override fullscreen on its own. Whether the call is answered
  from the notification (then back to the app) or from the app's card, a **call card takes over the
  lowest-priority pane** on screen (e.g. speed before navigation, or replaces media), like Auto layout's
  override, and gives it back when the call ends.
- **Call card:** caller / app name, duration, mute mic, end call, audio output (speaker / Bluetooth / phone);
  while ringing: Answer / Decline. Big glove targets.
- To check per skin: CallStyle actions present, HyperOS's full-screen incoming UI, One UI's dialer notification.

### 14b · Portrait (for the bike mount) — v4.0, versionCode 23 (major drop). Spec agreed 2026-11-14
Full working spec: `.dev/Phase-14b-Portrait.md`. Mockups: https://claude.ai/artifact/BaRB7AspLDQUmtosnQbAkA
- **Orientation setting** Landscape / Portrait / Auto. Fixed modes ignore the sensor and the system lock. Auto follows the
  system rotation lock; when unlocked it switches only after a delay and a very tolerant tilt (a mount propped ~5 degrees
  never flips). Everyone (existing and fresh) starts Landscape; no re-setup.
- **Portrait presets:** 1 pane, 2 stacked, 3 stacked, 3 = big on top + two side by side (exact 50/50), 4 stacked. Each
  orientation remembers its own preset and pane order; rotating keeps the pane count and order.
- **Mini cards** (4-stack): title only, no header buttons (Fullscreen / Maximize through the hold picker); media = art +
  title + play with a horizontal swipe for next / previous; speed unit always visible.
- **Alignment:** one axis per card. Speed (3+ cards): value centred in its column, figures aligned left. Navigation (narrow):
  arrow, route name, then navigation info (distance, arrival time, time remaining), all centred.
- **Map:** narrow card = recentre only (End only, two taps, while guiding with no Navigation pane). Fullscreen portrait: top bar
  close / search / layers, button column top edge aligned with the navigation info box.
- **Dialogs and sheets** follow portrait (bottom sheets, one column, pinned primary action); Settings gets an Orientation row.
- **Calls:** ringing Decline + Answer; on call name above duration, controls below; during fullscreen a slim Answer / Decline pill.
- **Refuel:** "Filled to the top" becomes "Full refill" (logic unchanged).
- Tablets and foldables: WIP, not designed for yet.
- Tests: only changed features, plus a portrait journey in the release smoke test and baseline profile.

## Later phases (planned 2026-11-09)

- **Phase 13: feedback update** (collected 2026-11-14 after a week of riding v3.3 plus a second rider; details
  in "Phase 13 plan" below). Built before Phase 14 (portrait rewrites the geometry that
  13a touches). Versions 3.4.1-3.4.3, versionCode 19-21.
- **Phase 15: app logo** (its own phase). The rider supplies the artwork:
  - a square **SVG** (best) or a **1024×1024 PNG** with a transparent background, the mark kept inside the
    central ~66% (Android crops adaptive icons to circles, squircles etc.);
  - optionally a background colour, and a one-colour version for Android 13+ themed icons (else derived).
  - Build: adaptive icon (foreground / background / monochrome in `res/mipmap-anydpi-v26`), legacy PNGs for
    older launchers, the splash-screen icon, the notification icon (white silhouette), and a check on
    HyperOS / One UI / Pixel launcher shapes.

## Phase 13 plan (collected 2026-11-14, WIP, not started)

Rider notes: the green pin at a pane's top-right in screenshots is Android's privacy indicator (location in use). Ignore it.

### 13a · Fixes and tweaks
- **Nav voice muted must not duck music.** Muted = no TTS and no audio-focus request at all (check the mute flag before taking focus).
- **Speed card opens on the log face.** Code has speed as page 0; find what lands on page 1 (pager state restore, an Ended/Paused trip at launch) and make the speed face always the start.
- **Nav card alignment** (screenshot 2026-11-09): ETA line starts at the far-left edge while the text above is indented; arrow sits low against the distance text. Align all text to one left edge and centre the arrow on the distance + road block. Header title is cut ("Next tu") by the corner clock.
- **Fullscreen:** hide Maximize; the fullscreen button becomes "exit fullscreen".
- **Drag drop target:** choose by overlap of the lifted card's rect (largest overlap, ~20% threshold), finger-inside still counts, nearest-centre fallback; highlight before release. Today dropping a card straight on top of its target can miss.
- **Map hold:** the map body's long-press (dropped pin) only in **fullscreen**; in Compact/Expanded the whole card behaves like the others (hold = drag / picker). Pan still wins on any move.
- **Dash km/L after a ride on every card size** (speed card Compact, Wide, Expanded, Fullscreen): `sum_dash` must be reachable in all of them (compact form of the prompt).
- **Garage:** "Add bike" becomes a card at the end of the bike list (replaces the top-right button).
- **Tank icon option:** Gauge (today) or Plain (fixed fuel-pump icon, still red in the last segment). Default Gauge.
- **Rename the Next turn pane** (short, fits the header): candidates Route / Navigate / Guide. OPEN: rider to pick.
- **Crowded controls (open):** proposals: drop the header swap button (drag + picker replace it; keep the accessibility action), fullscreen shows only exit, map controls move to small header buttons (below), optional "minimal headers". OPEN: rider to confirm.

### 13b · Quad navigation, auto-start
- **Compact map during navigation (option A, decided):** route line + one slim chip (turn arrow + distance); no banner, no ETA card. Header gets small buttons: **mute** and **End** (moved up from the bottom-right). The bottom-right big button becomes **recentre / follow my position** (replaces the X). Expanded/Fullscreen map keeps the full banner and ETA. No "Show navigation card" chip: Auto layout's snap already covers it.
- **Auto-start logging (rider: important):** setting + first-run setup choice. **Only while the app is open** (never assume background running; no background location, no activity recognition). Two behaviours:
  1. odometer-only counting (km add to odometer, fuel level, services; no saved trip),
  2. auto-start trip once speed stays at or above a threshold (proposal 15 km/h) for a time (proposal 30 s); saved to history as usual; auto-pause / auto-end already exist.
  Battery: low-rate location while stationary, high accuracy only once moving. This reverses Phase 9a's "no auto-start", by the rider's request.

### 13c · Settings
- Categories: Display (theme, **accent colour**, screen), Layout, Navigation (units, voice), Trip and Garage, Audio, Backup, Debug.
- **Accent colour: user-selectable (WIP, future QOL).** Replaces the cyan token; offer cyan, ice blue, violet, magenta; amber / red stay status-only, no green.

### Phase 16 (after the logo): welcome screen + first-run setup
- Welcome: time-of-day greeting + readiness strip (GPS, media access) and data: cautious range with tank, next service due, odometer, today / week km. Show: Always / First open of the day / Never; self-dismiss ~3 s or tap; never blocks the dashboard. Android 12 splash icon from Phase 15.
- Setup pages (skippable, re-runnable from settings): welcome, permissions, units + theme, bike + fuel prices, layout, auto-start choice, finish.

### Parked
- **Rider chat:** needs a server, accounts, moderation; typing while riding is unsafe; voice apps cover it (Phase 14's call card helps). Possible later: one-tap "share my ETA" link.

## Phase 13 decisions (rider answers, 2026-11-14; they supersede the plan above where they differ)

**Versions / order:** Phase 13 is built before Phase 14. 13a = 3.4.1 (vc 19), 13b = 3.4.2 (vc 20), 13c = 3.4.3 (vc 21). Phase 14 moves to 3.5.1 / 3.5.2 (vc 22 / 23).

**13a**
- Next turn pane is renamed **Navigation**. The header title must never be cut by the corner clock (width rule: title first, clock shifts or title shortens in Compact).
- **Header swap button:** kept only in two-pane layouts (Side by side, Rows) and on Duo's tall pane; removed in Quad and on Duo's stacked panes (drag and picker stay). Compact headers: title plus at most two buttons, the rest in the hold menu. Header variants shown to the rider before building.
- **Fullscreen:** the button stays the fullscreen button (no "exit" swap); only Maximize is hidden. Map keeps its X and recentre buttons in fullscreen.
- Dash km/L reachable on every speed card size; Garage "Add bike" card; tank icon option Gauge (default) / Plain; speed card always opens on the speed face; nav card alignment; drop target by overlap; map hold = pin only in fullscreen.
- **Two-step hold feedback** (the WIP "lifted = drag, held 0.7 s more = picker armed", double buzz) is built here.
- **Refuel:** the Partial / Full choice becomes one toggle "Filled to the top" (default on). The litres input range is fixed 0 to the tank size (e.g. 0-6.1 L), never limited by the estimate. If the rider enters more than the estimated space left, the level after the fill is capped at a full tank, the fill counts as a measured full anchor, and the +-35% outlier rule decides whether that span teaches km/L.

**13b**
- **Auto-start (app open only; no service, no background location):** setting Off / Odometer only / Auto-start trip. Starts when speed stays at or above **15 km/h** for **30 s** (hold time adjustable, 20 / 30 / 45 / 60 s). The trip is backdated to the moment the hold completed (the first 30 s are not recorded: an auto-start is consent verified by "app open and already moving"). Works **only from a clean state** (app opened with no trip, no trip ended this session). After the rider presses End, auto-start is suppressed. Default Off (first-run setup asks later, Phase 16).
- **Start announcement:** the speed card briefly slides in a banner over the speed face (same animation as speed to log face): "Trip started" with "Riding detected" below it, about 3 s, short buzz, swipe to dismiss. With no speed card on screen, a pill at the top centre (like the link status pill).
- **MAX lag fixed here** (first fix after start/resume sets max too): auto-start begins mid-motion, so the lag would show on every auto-start.
- Compact map during navigation, option A (as planned above).

**13c**
- **Colours (one settings section):** accent colour = presets plus a hex code field, **default green** (this deliberately drops the old "no green anywhere" rule; amber and red stay status colours). Map route line colour is adjustable in the same section (default: follows the accent, with an override). Light theme gets an auto-adjusted variant of the chosen colour for contrast; the hex field warns when a colour is hard to read on black or white.
- Settings categories as listed above.

**Phase 14 (portrait is for a vertically mounted phone on the bike, like delivery riders do)**
- Portrait-only layouts derived from the originals: 1-4 cards stacked (Quad does not fit portrait as a grid). Rotating mid-ride keeps the same number of panes in the same order (2 landscape cards = 2 stacked).
- Each orientation remembers its own last preset; a clean start in portrait with none saved derives it from the landscape one.
- Portrait-only card forms: Map as it is; Speed with two cards = value on top, figures below; three or more cards = value left, figures right.
- Open: the Phase 14 section gets rewritten as its own deep-dive before building.

**Later phases**
- Phase 17: range adjusted by riding style (average speed, climbs, spirited riding), only if it proves suitable (complex; the cautious range is already safe).
- Phase 18: iOS port (Swift, Mac Air M2); prep a handoff when the rider says ready.
- After that: rider chat and proximity (WIP, undecided). Rider chat parked until then.
- TomTom search works (rider-tested 2026-11-14, better local knowledge); no key pending any more.

### Addenda (rider, 2026-11-14)
- **Existing installs keep their settings.** Every new setting gets a migration default that equals the old behaviour: an install with saved preferences and no accent key stays **cyan**; auto-start Off; Gauge tank icon; and so on. Only fresh installs get the new defaults (green accent), then Phase 16's setup asks. Implementation: a one-time check "preferences exist but this key doesn't" in `SettingsRepository`, with a test.
- **Portrait 3 panes:** the plain stack is the first preset; "big pane on top, two side by side below" is added as a second 3-pane portrait preset in Phase 14 (it is just a preset in the layout model).
- **Changelog (rider, 2026-11-14; first item of 13a):** `assets/changelog.md` (per version: number, date, plain-language bullets, backfilled from 2.0). Settings row "What's new" = full history, newest first. On the first open after an update a dismissible sheet shows only the new version's entries (last-seen versionCode in DataStore); deferred while a trip or navigation runs; none on a fresh install (Phase 16 welcome covers it); an upgrade from v3.3 sees it once on 3.4.1. Rule for CLAUDE.md: every sub-phase build adds its entry with the version bump.

## Phases 4.1 / 4.1.1 / 4.2 (rider decisions, 2026-11-14)

- 4.1 logo: adaptive icon from `nativeauto.svg` (mark cyan #4adffc + white), black background, themed icon, Play Store 512 px.
- 4.1.1: default accent cyan #3FC8E0; Settings tab order Trip and Garage, Display, Layout, Navigation, Audio, Backup, Privacy and data, Debug;
  portrait maximize by pane count (2: none; 3-4: a card takes two cards' space); one Add bike button on an empty Garage; call-controls switch;
  auto-start on/off merged; Privacy and data tab; more title space in portrait; portrait layout switcher rows.
- 4.2: first-run setup pages (7), landscape first, no scrolling on any page. Details in `.dev/Phase-4.1-4.2-Unattended.md`.

## Next update (planned 2026-11-16, not started; version number: rider decides)

Short overview. Details are added once the rider's latest phone-test feedback is collected.
- **Rider feedback from the latest phone test** (v4.2): the main content, to be listed here.
- **Media card swipe in landscape:** the portrait `SwipeToChange` (swipe to change track) also on the landscape media card.
- **Auto-resume:** a paused trip resumes when steady movement is seen again (same detector as auto-start: speed over
  the threshold, held). Must not fight auto-pause / auto-end: resuming restarts the stationary clock; auto-end only
  fires while still paused. **Decided (rider):** manual pauses auto-resume too, by the same movement rule. Its own
  hold time, shorter than auto-start's and set by the rider: 5 / 10 / 20 s (default 10), plus an on/off switch, in
  Trip options beside auto-pause.
- **Auto-start threshold 15 → ~10 km/h** (rider). Note from the test: no auto-start in a basement. Likely no usable GPS
  there (no fix, or fixes skipped as inaccurate), not the threshold; it should start soon after leaving. Keep the hold
  time so GPS drift at low speed doesn't start a trip.
- **Privacy clean-up in the app:** the bike editor's name hint drops the old example (it becomes "Name (e.g. Gen 1)" or
  no example). Git history is already cleaned before the first GitHub push (bike names → Gen 1 / Gen 2, no home-base hints,
  no-reply author email), but APKs built up to v4.2 still carry the old hint. **Don't attach v4.2 or earlier APKs to a
  GitHub release;** the first release APK should be this update.
- Version bump + changelog entry as usual; release smoke test before the APK.

Status: the media swipe, auto-resume and the 10 km/h threshold shipped in **4.2.1** (versionCode 27). Auto-resume
is under Trip options ("Auto-resume": off / 5 / 10 / 20 s). If the phone's Trip options row doesn't show it, the
phone has an older build than 4.2.1.

## Phase 4.3 plan: coherence, greeting, riding behaviour (rider, 2026-11-24; DONE 2026-11-24: v4.3 to v4.6, see DECISIONS.md "Run 4.3")

Comes from the rider's phone screenshots (`screenshots/nat-auto1..7`) and notes. **Versions (agreed
2026-11-24):** 4.3a = v4.3 (vc 28), 4.3b = v4.4 (vc 29), 4.3c = v4.5 (vc 30), 4.3d = v4.6 (vc 31). Same rules as before: one commit,
a changelog entry and a release APK per sub-phase.

### 4.3a · Fixes and one design system for settings and dialogs

Overall finding: everything works, but the screens don't follow one pattern. This sub-phase picks a pattern and
applies it everywhere.
- **Settings sheet:**
  - one fixed height for every tab (it grows and shrinks per tab now);
  - more room between "Settings", the tab row and the page;
  - the tab row fades out before "Done" instead of cutting a tab off ("Privac").
- **One settings row pattern:** title + description on the left, the control on the right, the same height
  everywhere. Applies to:
  - Reset trip (now a bare button with the note under it);
  - Backup (buttons are below the text now; Back up / Import go to the right of their descriptions);
  - Reset app (button above its text);
  - "Trip" + Trip history (the row has no description).
- **Pane picker ("Show in this pane"):**
  - less space above the title; drop the drag handle;
  - remove Fullscreen and Maximize: the card's own buttons do this. Portrait Mini cards that have no buttons keep
    those two, as icon tiles at the end of the grid, not header links.
- **Dash km/L button in Rows:** the button is tiny there. Move it into the summary's figure row as a figure-sized
  tile ("km/L +"), the same in every layout.
- **Low-fuel icon:** it now floats at the speed card's top right, apart from everything. **Decided (rider,
  2026-11-24):** the red pump icon alone in the header's status items (beside the battery), no range text: the header
  is already full. Dropped first, like the battery, when the title needs the room.
- **Trip complete in Rows** sits low, in the lower 60% of the card. Centre the block vertically, or start it under
  the header like the other faces.
- **Fullscreen Navigation card:** the arrow, distance, road, "Then" and arrival line are stacked in one column now.
  Use the Compact/Wide arrangement instead (arrow on the left, distance + road beside it, arrival line under that
  block), scaled up.
- **"Bear left" → "Slight left"** (the same for right) in the banner text and the voice. Valhalla's English says
  "bear". Rewrite it in `toInstruction` and in the spoken text before TTS. Also lower-case "Then Turn left" →
  "Then turn left".

### 4.3b · Look: icon titles, media buttons, faint icons

- **Card titles as icons** (setting: Text / Icon, default Text so nothing changes until chosen). Icon mode uses the
  picker's icons (map, note, gauge, turn, clock, pump). This also frees header room for the battery and clock.
- **Media buttons with clear touch areas:** skip and previous get the same round, filled `buttonQuiet` plate as
  play/pause (smaller), so all three read as buttons. The same in the call card.
- **Faint pane icons (option, off by default):** a large, very low-contrast (~5–6% white on black) icon of the pane
  behind its content (clock dial outline, gauge, note…). Only in Expanded / Fullscreen, never behind numbers in
  Compact. Settings → Display.

### 4.3c · Daily greeting (once per day)

- On the first app open of the day (local date, stored in its own DataStore key like the changelog's), a sheet or
  card: greeting by time of day plus a bike status, most of all the **cautious fuel range** and the tank gauge. Then
  odometer, the next service (if due soon), and yesterday's or this week's ride total.
- Not while a trip runs or navigation is active; not on the same day as first-run setup. If the changelog is
  pending too, the greeting comes first and the changelog after it.
- Dismiss with a tap or swipe; it goes away by itself after ~8 s once moving. No bike → a short greeting only.
- Setting to turn it off (Display).

### 4.3d · Riding behaviour in the fuel estimate (design first, kept light)

Goal: the range reacts to *how* the bike is ridden now, not only to the long-run km/L. Constraint: no new sensors
or extra GPS. Use only the fixes the trip already gets, O(1) work per fix, no per-fix storage beyond what the
track keeps.
- **Per fix (1 Hz), running sums only:** speed² (aerodynamic load ∝ v²·distance), positive acceleration × speed
  (energy spent speeding up), climb (Δaltitude > 0, smoothed; GPS altitude is noisy, so a ~10 s moving average and
  a 2 m dead band), stop-start count.
- **Per trip:** a "load index" = the energy estimate per km against the rider's own baseline (median of past
  trips). Stored on the trip row (Room migration, one or two columns).
- **Learning:** at each full-to-full refuel span, fit km/L against the span's mean load index (one-variable
  least-squares, updated by recursive sums, weighted like `learnConsumption`). Until there are enough spans
  (≥ 4), the factor stays 1.0. The model is not used if the fit doesn't explain anything.
- **Range now:** `cautiousKmPerL × f(load of the last ~20 km)`, clamped to ±20%. It is shown as the same range
  number, so the screen doesn't change. A small note ("riding hard: −12%") is shown only when the factor is beyond
  ±5%.
- First step: record the sums on trips for a while (cheap), check them against real refuels, and only then switch
  the range over. Pure Kotlin with unit tests (`RidingLoadTest`).

### WIP ideas (not scheduled)

- **Repo screenshot:** replace `nativeauto_v1.1.jpg` (old v1.1 look, a real album cover in the media card) with a current
  emulator screenshot that has no third-party artwork, and show it in the README.

- **Export a ride as a game track.** A cleaned centreline (projected to metres, resampled, smoothed, a minimum bend
  radius, optional closed loop), as GPX or CSV for **Assetto Corsa** / BeamNG tools, or for GT6 through eran0004's
  *Track Layout Creator* (GPX/CSV in → `.ted` out; GTPlanet "Course Maker Toolbox"). The rider has a HEN PS3; test the
  chain by hand first (GPX from History → Track Layout Creator → `.ted` → PS3). Writing `.ted` from the app comes
  only if that works.
- **"Ride as a circuit" view.** The trip drawn as a circuit (no map), coloured by speed, with 3 sectors, top speed,
  the sharpest bend, start/finish, and a badge when the ride is a closed loop. A face in the trip summary / history
  detail.

## WIP: licence (rider, 2026-11-16; decide before the repo goes public)

**Done (2026-11-24):** `LICENSE` is **PolyForm Strict 1.0.0** (rider chose the standard licence over the custom notice:
noncommercial use only, no changes, redistribution or derived works; name and logo not licensed); README explains it in a friendly tone (issues welcome, no pull requests). History rewritten and force-pushed (no MIT, no plate initials); old history purged locally. Commits before
this one carry the earlier custom all-rights-reserved notice. GitHub releases start at v4.3; earlier builds stay private test builds (no reason given publicly).

## Plan v4.6.1 to v4.7 (rider, 2026-11-24; kept, NOT started: waits for "start" after the v5.0 brainstorm)

Found on the phone with v4.6. Proposals the rider has not explicitly confirmed are marked (proposed).
- **v4.6.1 fixes:** settings sheet jumps when scrolled up (not reproduced; suspect the sheet's nested scroll: stop swipes
  moving it); 4-stack (Mini) headers too close to the top, match landscape margins; the speed card shows the log face first
  (a trip's end scrolls to it and it stays while the process lives); Today as the sixth figure tile on the half-size log
  face (rider: option A), nothing clipped. Range adjustment off until v4.7 and changelog corrected (proposed).
  Speed or log face first: **decided**, see "Roadmap v4.6.1 to v5.0". Cards kept together (proposed).
- **v4.6.2 settings:** no Audio, Backup or Privacy tabs; Others holds What's new, Backup, permissions, Reset app; voice
  guidance with navigation; call controls to Display; permission named "Call permission"; auto-start moved; Colours no longer
  between Theme and Card titles; every menu balance-checked with DESIGN.md. Greeting: once a day / every open / off.
  Proposed: merge Layout and Navigation (four tabs); "Trip automation" holds auto-start, pause, resume, end.
- **v4.6.3 portrait:** larger text, uniform sizes per role; faint icon stays on the card edge (rider), smaller, clear of
  the buttons (proposed).
- **v4.7 fuel:** traffic jams, uphill jams and other factors (rider). 4.3d counts stops but does not use them, and idling is
  ignored, so a jam can read as easy riding. Discuss the starting costs before building.
- **Later:** Google SDK replaces OpenFreeMap eventually (rider). iOS on hold (rider).

## Idea: v5.0 car mode (rider, 2026-11-24; PARKED until the rider calls for it)

- An extension of the app, not a second app. For the rider's car with no head unit (phone, sound over AUX): one central
  control. Also useful for the tablet idea. Motorcycle stays the focus; the rider tests in the car later.
- Overlaps AGAMA, DMD2, MotoNAVApp: fine as a category, but copy no names, icons, artwork, themes or distinctive layouts.
- Proposed (not confirmed): the vehicle chosen in the Garage sets routing, fuel and layout; Bluetooth picks the vehicle;
  range left at the destination. With AUX, Bluetooth can't identify the car, so it needs another trigger.
- Needs: car routing (tolls), car Garage, car fuel figures, larger text, tablets. No electric cars in v5.0.
- **Garage polish (rider, motorcycle first; to discuss):** more logic and fine-tuning options for added vehicles.

### Garage direction (rider, 2026-11-24; NOT started)

- **Universal bikes**, not NMAX only: general all-rounders such as Vario 110 to 160, BeAT, PCX.
- **Low-fuel threshold chosen by the rider**, with a default. The warning at about 50 km left felt too cautious.
- v4.x is expected to be mostly QoL updates.
- Proposed: pick a type and tank size (model presets only prefill, sourced from spec sheets); threshold in km, per bike,
  default 30 km (20 / 30 / 40 / 50 / custom); gauge, fine-tuning and threshold in v4.7, fill-up review, range caution,
  service sets and custom fuels in v4.8.

## Roadmap v4.6.1 to v5.0 (rider, 2026-11-24; agreed; 4.6.1 to 4.6.3 done 2026-11-24, ridden, 4.6.4 from that ride done; 4.7 done 2026-11-25; the rest waits for the rider's "start")

| Version | Contents |
|---|---|
| 4.6.1 (done) | Fixes, Today tile, low-fuel threshold (30 km default), range adjustment off until 4.7 |
| 4.6.2 (done) | Settings tabs, greeting choices, call permission |
| 4.6.3 (done) | Portrait sizes as one shared scale (4.9 reuses it), smaller faint icon on the card edge |
| 4.6.4 (done) | Ride feedback on 4.6.3, below |
| 4.7 (done) | Traffic recording (range not changed yet), universal bikes, gauge per bike, Fine-tuning |
| 4.8 | Fill-up review, range caution, service sets, custom fuels; API key scrubbing (4.8 or 4.9, rider) |
| 4.9 | Tablet preview: large-screen foundation for car mode, below |
| 4.10 | Google map, navigation and search (plan only, billing not settled), below |
| 5.0 | Car mode (parked until the rider calls for it); public keyless release (WIP), below |
| 5.1 | App tutorial: how to use the app, on first install or after Reset app (rider; WIP) |

Speed or log face (4.6.1, rider): opened while stationary shows the log face (Start); while moving shows speed.
Proposed details: moving = 10 km/h for 3 s; no fix yet counts as stationary; a running trip stays on speed at stops, a
paused one shows the log face; a swipe by hand wins until the app is next opened or the trip changes.

Still open: fuel starting costs, and whether the range uses the traffic 4.7 records (discuss with recorded rides).

### 4.6.4 · Ride feedback on 4.6.3 (rider, 2026-11-24; DONE the same day, see DECISIONS "4.6.4"; versionCode 35)
Ridden with 4.6.3: "much cleaner", auto-resume and the speed card's switch back are good. **Keep those as they are.**
The rider's points are first; how to do each is (proposed).
- **Portrait card titles: slightly more space above** (rider: top only, the left margin is fine). Now 6 dp. (proposed) 10 to 12 dp above,
  left stays 16, same for icon titles, same gap on every shape; content may not shrink below the 4.6.3 scale. Check the 4-stack (Mini) first: least
  room. Slim rows beside a maximized card have almost none: 4 dp above at most, measured. Landscape unchanged.
- **Orientation moves to Display.** (proposed) First in the left column: orientation, theme, card titles, faint icons; right:
  unit, accent, call card, screen options (4 and 4). Map and layout keeps map width, hold time | voice, auto layout (2 and 2).
  The orientation row is tall (hint under it): check Display's landscape fold and, if it cuts a row, add the bottom fade.
- **Route colour goes; the route always follows the accent.** (proposed) Remove the row, its dialog and the setting; a colour
  stored earlier is ignored and cleared. The light-theme contrast variant stays. Say in the changelog that an own route colour is lost.
- **Greeting card size in portrait.** Now about a third of the screen, figures bunched left, the right half empty. (proposed) Taller
  (about 45 %), range larger, Odometer and This week as two equal halves across the width, and Done full width at the bottom, in
  thumb reach. Sizes from `PortraitSizes`. Also align "range left" with the digits. Empty (no bike), unknown range and service-due
  forms checked too. Landscape unchanged. **Confirmed: larger in portrait; landscape stays as is (rider).**
- **"Trip automation" becomes "Trip behavior"** (row, dialog, hint; tags keep their names). American English. CLAUDE.md / DESIGN.md wording follows. Past changelog entries are not rewritten.
- **American English for all names and text in the app (rider):** "Accent color" and any other British spelling in strings, code-set text
  and setup pages. Code names, tags and stored keys stay.
- **Date on the four-stack clock (rider: yes).** Slim rows only if it fits. With it, the open review (proposed): portrait navigation, call
  card, new dialogs, light theme.
- Tests affected: `DashboardScreenTest` (settings rows, route colour), `PortraitDashboardTest` (orientation row), `PortraitCardsTest`,
  `GreetingSheetTest`; portrait design shots in both themes; release smoke test.

### 4.9 · Tablet preview (foundation for car mode)
Test device: the rider's Huawei MatePad 11 (exact model and year to confirm; 2021 Wi-Fi assumed); Indonesian unit, not the China HarmonyOS 2 version, so Google apps still work (rider, 2026-11-24).
- **Detection, no mode switch:** window's smaller side >= 600 dp. Split screen falls back to the phone layout. Layouts saved
  separately from the phone's. One setting, **only on large screens**: Layout > "Large-screen view" Auto / Phone view.
- **Enlarged by role, not zoom:** speed, clock and turn distance most, text moderately, headers least (4.6.3 scale).
- **Layouts:** map + three (default, map ~60 %), map + four (2 x 2), six grid (3 x 2), wide map + strip of four; portrait: big map
  over a 2 x 2 grid. Phone layouts that still work stay. Settings, Garage and history adapted to large screens.
- **GPS without Google services:** `FusedSpeedSampleSource` is the only reader and needs Play services; fall back to Android's
  LocationManager. No GPS: say so plainly. Keep MapLibre as the fallback when Google's map / Navigation SDK arrives.
- The rider also has the app on a Galaxy Tab S8 (2026-11-25): **a preview device for the look only; its GPS test is skipped for now (rider).**
  The MatePad may look similar; check it there too. GPS box: separate idea, car mode only, `.dev/GPS-Receiver.md`.
- **Feedback, v4.7 on the Tab S8 (rider, 2026-11-25; installs and works). NOT started:**
  - Portrait / Auto don't turn the screen; only turning the tablet does. Likely cause: the app targets SDK 37, and Android 16+ ignores an
    app's orientation request on large screens (no opt-out from Android 17). (proposed) On large screens follow the system's rotation, lay
    out from the window's shape and say so in the Orientation row. Tablet: One UI 8 (rider, exact version unknown), i.e. Android 16, which fits. Workaround to try: Android's
    aspect ratio setting for the app. Landscape can't be forced either.
  - The UI is still phone-sized: adjust for large screens (layouts and role sizes below).
  - No GPS fix, Wi-Fi model (rider wrote XM-7000; probably SM-X700, to confirm). Sources disagree on whether it has satellite GPS.
    Tested indoors only (rider), so not a verdict yet. Test outdoors, precise location on, for some minutes, beside another GPS app. (proposed) Say "no GPS on this device" when there is
    none, and consider a phone or external receiver as the location source.
  - **Wi-Fi only tablets (rider):** e.g. the rider's iPad Air 4: maps and position work but lag far behind. (proposed) Tell coarse Wi-Fi
    positions from satellite fixes: show the map position as approximate, no speed, trip, auto-start or guidance from it, and a plain
    "needs GPS" message with the phone / receiver option. Applies to the iOS port too.
  - **Tablets suit car mode (rider): reconsider the UI elements with that in mind.** Car mode (5.0) itself stays parked; 4.9's bike
    preview and 5.0's car UI should share one large-screen scale. **Decided (rider): 4.9 is the
    foundation for car mode, an early preview;** car routing, car Garage and the rest of car mode stay in 5.0, parked.
- **Decided (rider, 2026-11-25): 4.9 is the prototype for larger screens; the iPad follows the Android tablet layout.** The iPad preview
  mockups (car mode, companion, portrait, split screen) are mostly good; tweaks come at 4.9.
- WIP for the iOS port: try Spotify's iOS SDK (media control there; possible five-user limit). Later idea: a phone sends its GPS to the tablet.
- Rider's check beforehand: does v4.6 open on the MatePad, does the map draw, does the speed move (outdoors)?

### 4.8 · Brief (rider, 2026-11-25; NOT started; one version, 4.8)
- **Decided (rider):** range caution per bike, new bikes start on Normal. Service intervals stay empty; the usual routine parts are offered and
  follow the motorcycle type. Custom fuels are shared by all bikes. The range should follow riding behavior (below), kept light.
- (proposed) Caution: Normal, Cautious (-10 %), Very cautious (-20 %). Custom fuels: own name and price, old fill-ups keep theirs.
- (proposed) Service: only `SCOOTER_CVT` exists, so add geared (chain), underbone (chain) and Other. Lists of parts per type, no numbers.
- **Decided (rider: yes):** fill-up review for abnormal entries: before saving, ask only when an entry looks wrong: Fix, Save anyway, Don't learn from this; past fill-ups editable.
- Key scrubbing (see 5.0).
- **Range and riding behavior (rider's list; math to discuss):** uphill, short bursts in traffic, uphill speeding, downhill normal and fast,
  traffic lugging. (proposed) One demand figure instead of a rule per case:
  - Per 5 s window: max(0, rolling + air drag + slope, signed + change of speed, signed) + a low-speed and idling cost per second. Today climbing
    counts but descending is never credited, and crawling and standing are recorded but unused.
  - Slope from smoothed altitude over 100 m or more; phone altitude is noisy, so no slope without enough distance.
  - **Idling (rider): no start-stop logic; while a trip runs, the engine runs.** (proposed) Paused time costs nothing.
  - **Idle and moving fuel are learned per bike from its own fill-ups, no assumed L/h (rider; also not by class or displacement).** (proposed) Until
    learned, idling adds nothing beyond what the bike's km/L already holds. Per full-to-full span, fit liters per km on work per km and standing
    hours per km; only time above the bike's usual counts, so it isn't charged twice. Idle joins after about 6 covered spans whose standing time
    differs, only if positive and it beats the fit without it; otherwise left out. Low-speed cost is learned the same way, later, if at all.
  - **Passenger / cargo load: not counted (rider).**
  - Light: cheap sums per accepted fix (already done at 1 Hz, nothing extra from the GPS); range recalculated once a minute, earlier only when the
    last 1 km differs by 25 % or more from the figure in use for 30 s, at most every 15 s; no work without a trip. The fit runs at fill-ups.
  - Skipping fixes instead would miss the bursts. Range drops sooner than it rises, **within -20 / +10 % (rider: good).**
  - **Range shown in 2 km steps (rider, if light).** (proposed) Rounded down; 5 km until the estimate is learned; falls at once, rises only by 4 km or
    more, a fill-up resets it. One rounding rule for every range on screen.
  - Work per km uses fixed general scooter constants (rolling, drag): only a relative scale, each bike's fill-ups set what it means in fuel.
  - Same gate as 4.3d: four usable tanks, must beat the plain figure. 4.8 computes it beside the plain range and compares at each fill-up; the
    range shown follows it only after that. Trips saved before 4.8 lack the new sums, unless rebuilt from their track (no altitude there).

**Prep for the session that builds 4.8 (waits for "start"):**
- Order, one version and one changelog entry: key scrubbing, custom fuels, fill-up review, service types, range math last.
- Start at: `garage/Fuel.kt`, `garage/RidingLoad.kt`, `garage/Service.kt`, `garage/Garage.kt` (`BikeType`, `FuelType`), `history/TripRecorder.kt`,
  `history/Database.kt` (Room v9 to v10), `garage/GarageBackup.kt`, `ui/garage/`, `app/build.gradle.kts`.
- Watch: `RefuelRecord.fuel` turns an unknown fuel into Pertamax, so custom fuels need that first. `RANGE_FOLLOWS_RIDING` stays false until the gate is met.
  Old trips lack the new sums. Old backups must still import.
- Tests: unit (fit, gate, rounding, review rule, migration, backup), the Garage device tests, keyless build (map, route, search), release smoke.
- Before building: look at real fill-ups and recorded trips; unattended or reviewed run is the rider's choice.
- Still proposed: caution levels, the rounding rules, service lists, learning gates.

### 4.10 · Google map, navigation and search (rider, 2026-11-25; PLAN ONLY)
- Own version, if possible; waits until Google billing works. The map engine and its traffic button are already in the app and turn on
  with a key (`MapEngine`); to do: a restricted key, a test on phone and tablet, and which engine each build uses. MapLibre stays the fallback.
- (proposed) Restrict the key to the package, signing certificate and the Maps SDK, with a quota and budget alert. A shipped key stays readable,
  so decide whether v5.0's keyless public build leaves it out too. 
- **Scope (rider): also Google navigation and search,** so it feels familiar to Google Maps users; inside our own cards, not the Google Maps app
  embedded. The open stack (MapLibre, Ferrostar / Valhalla, Photon) stays as the fallback.
- Navigation SDK contains the Maps SDK and can't sit beside `play-services-maps`: the Google map card must be rebuilt on it, and `maps-compose` tried
  or dropped. Billed per destination. To check: two-wheeler routing in Indonesia, free amount, minSdk, terms on mixing Google search or routes with another map.


### 4.9 / 5.0 · GPS and companion mode (rider, 2026-11-25; NOT started)
- **Decided (rider):** a tablet without GPS defaults to companion mode (media, clock, small map); **tablets only**. Phones keep today's
  behavior ("Searching for GPS"). Tablets with GPS, e.g. a cellular iPad, get the full car mode.
- (proposed) Companion: map north up with an accuracy circle, search and route preview; no speed, trips, auto-start or guidance; Garage by hand.
- (proposed) GPS is judged by fixes (speed, 25 m or better), not by model. Companion only if no good fix was ever seen, after about 3 min
  with the app open; keeps listening. Once GPS, never downgraded (tunnel = "GPS signal lost"). Layout changes only when parked or accepted.
- (proposed) Tablet or phone: Android: the display's smaller side >= 600 dp (maximum window metrics); iOS: the iPad idiom. Layout: the app's
  own window, so split screen gets the phone layout. 4.9: detection and location states; 5.0: car layouts, external receiver.

### 5.0 · Public keyless release (WIP, rider, 2026-11-25; NOT started)
- **Repo releases start at v5.0 (rider),** the first version with the API key fix. Until then testers get builds privately.
- **Key scrubbing in 4.8 or 4.9 (rider), if it breaks neither map nor navigation;** else the open services. Code check: without keys the app
  already uses them. (proposed) 4.8, with a check that fails a keyed public APK; repo release still v5.0.
- Keyless: a build option that leaves out the TomTom key (a key in an APK can be extracted). Replace the old key at v5.0. Map, routing
  and voice need no key; place search, name-only links and addresses use Photon, which finds fewer places.
- Check before release: search, map and routing on the keyless build; README version; debug keystore backed up.
- (proposed) A settings field for a user's own TomTom key. Fair-use limits of the free routing, search and map servers.
- `.dev/release.ps1` (local, untested) uploads the APK and notes with GitHub CLI. Before using it: dry run, and add a check that
  stops a keyed APK.
