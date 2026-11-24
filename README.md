# Native Auto

**Turn the phone on your motorcycle's handlebars into a dashboard.**

Native Auto shows speed, a map, music, turn-by-turn navigation, calls and trip history on one screen
built to be read at a glance and used with gloves on. It also keeps a Garage: your bikes, odometer, fuel
and service reminders. Landscape and portrait mounts are both supported.

Current version: **4.6.3** (Android 8.0+, `com.nativeauto`). An iOS port is in progress in [`ios/`](ios/).

## What it does

- **Dashboard of cards:** speed, map, media, navigation ("next turn"), clock, Garage and call controls.
  Pick a layout, drag cards to swap them, zoom one to full screen.
- **Trips:** start, pause and end a trip (or let auto-start do it), with distance, speed, time and a
  saved history you can export as GPX or back up.
- **Navigation:** search a place or open a Google Maps link, with free routing tuned for motorcycles.
- **Garage:** bikes, estimated odometer, fuel level and range, refuels and costs, service reminders.
- **Calls and music:** answer, decline and end calls and control playback from the dashboard.
- **Big touch targets, true-black OLED look**, adjustable accent colour, day theme, first-run setup.

Everything stays on the phone. There is no account and no analytics.

**What it is not:** a trip-planning or off-road navigation suite, a logbook, or a hardware add-on. It needs no
sensors, adapter or account. It is the screen you glance at while riding, on the bike you already have.

## Built with AI

Native Auto was built by one rider working with [Claude Code](https://claude.com/claude-code): the rider
sets the goals, tests on a real bike and decides; the AI writes most of the code. Design decisions and
the reasons for them are written down in [`DECISIONS.md`](DECISIONS.md) and [`CLAUDE.md`](CLAUDE.md), so you
can judge the choices yourself.

## Feedback

Ideas, bug reports and ride stories are very welcome: open an issue. I'm not taking code contributions
for now, so please don't open pull requests; every change goes through a test on the bike first.

## Build it

You're welcome to build and run it on your own phone. Requirements: Android Studio (JDK 17+), Android SDK 37.

```
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests
```

**Map keys (optional).** Put them in `local.properties` (never committed):

```
MAPS_API_KEY=your-google-maps-key
TOMTOM_API_KEY=your-tomtom-key
```

Without a Google Maps key the app uses MapLibre with OpenFreeMap tiles, and without a TomTom key place
search uses Photon (OpenStreetMap). Both fallbacks need no key. `local.defaults.properties` holds the
placeholders the build falls back to.

The working rules for the AI assistant are in [`CLAUDE.md`](CLAUDE.md).

## Credits

Routing and search: [Ferrostar](https://github.com/stadiamaps/ferrostar), FOSSGIS Valhalla, Photon.
Maps: [MapLibre](https://maplibre.org), [OpenFreeMap](https://openfreemap.org), © OpenStreetMap
contributors. Font: [Geist](https://vercel.com/font) (SIL OFL 1.1).

## Licence

Native Auto is my first published app and an idea I care about, so the code is shared to be read, not
reused. It is under the [PolyForm Strict License 1.0.0](LICENSE): you're welcome to read it, learn from it and
build it for your own noncommercial use, but please don't change it, redistribute it or build new apps on it.
If you'd like to use part of it, open an issue and ask; I'm happy to talk.

The Native Auto name and logo are mine and are not licensed. Third-party libraries and the Geist font keep their own licences.

Native Auto is an independent project. It is not affiliated with, endorsed by or connected to Google
(Android, Android Auto, Google Maps), Apple (CarPlay), TomTom or any motorcycle maker. Those names belong to
their owners.
